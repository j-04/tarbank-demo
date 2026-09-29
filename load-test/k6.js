import http from 'k6/http';
import { check } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

const baseUrl = __ENV.BASE_URL || 'http://host.docker.internal:8080';
const managerUsername = __ENV.MANAGER_USERNAME || 'manager';
const managerPassword = __ENV.MANAGER_PASSWORD;
const seedUsers = Number(__ENV.SEED_USERS || 50);

const measuredRequests = new Counter('measured_requests');
const endpointErrors = new Rate('endpoint_errors');
const loginLatency = new Trend('login_latency', true);
const accountListLatency = new Trend('account_list_latency', true);
const accountDetailLatency = new Trend('account_detail_latency', true);
const historyLatency = new Trend('history_latency', true);
const depositLatency = new Trend('deposit_latency', true);
const withdrawalLatency = new Trend('withdrawal_latency', true);
const transferLatency = new Trend('transfer_latency', true);

export const options = {
    scenarios: {
        warm_up: {
            executor: 'constant-vus',
            exec: 'warmup',
            vus: 10,
            duration: '10s',
            gracefulStop: '5s',
        },
        measured_standard_endpoints: {
            executor: 'constant-vus',
            exec: 'measured',
            vus: 50,
            startTime: '15s',
            duration: '60s',
            gracefulStop: '5s',
        },
    },
    summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
    thresholds: {
        measured_requests: ['count>1000'],
        endpoint_errors: ['rate<0.01'],
        login_latency: ['p(99)<1000'],
        account_list_latency: ['p(99)<1000'],
        account_detail_latency: ['p(99)<1000'],
        history_latency: ['p(99)<1000'],
        deposit_latency: ['p(99)<1000'],
        withdrawal_latency: ['p(99)<1000'],
        transfer_latency: ['p(99)<1000'],
    },
};

let customerToken;

export function setup() {
    if (!managerPassword) {
        throw new Error('MANAGER_PASSWORD is required.');
    }
    const managerToken = login(managerUsername, managerPassword, false);
    const runId = `${Date.now()}`.slice(-9);
    const users = [];

    for (let index = 0; index < seedUsers; index += 1) {
        const suffix = `${index}`.padStart(2, '0');
        const username = `load${runId}${suffix}`;
        const password = 'LoadPass123!';
        const customer = requestJson(
            'POST',
            '/api/v1/customers',
            managerToken,
            {
                username,
                password,
                firstName: 'Load',
                lastName: `User${suffix}`,
                dateOfBirth: '1990-01-01',
                email: `${username}@example.test`,
                phoneNumber: '+381601234567',
                residentialAddress: {
                    country: 'RS',
                    city: 'Belgrade',
                    postalCode: '11000',
                    line1: 'Demo load-test address',
                },
                identityDocument: {
                    type: 'LOAD_TEST',
                    issuingCountry: 'RS',
                    number: `LOAD-${runId}-${suffix}`,
                    expiresOn: '2035-01-01',
                },
                timezone: 'Europe/Belgrade',
            },
            201,
            true,
        );
        const source = createAccount(customer.data.customerId, 'EUR', managerToken);
        const destination = createAccount(customer.data.customerId, 'EUR', managerToken);
        const usd = createAccount(customer.data.customerId, 'USD', managerToken);
        const token = login(username, password, false);
        requestJson('POST', `/api/v1/accounts/${source}/deposits`, token,
            { amount: '1000.0000' }, 201, true);
        users.push({ username, password, source, destination, usd });
    }
    return { users };
}

export function warmup(data) {
    const user = currentUser(data);
    if (!customerToken) {
        customerToken = login(user.username, user.password, false);
    }

    switch ((__ITER + __VU) % 4) {
        case 0:
            customerToken = login(user.username, user.password, false);
            break;
        case 1:
            getJson('/api/v1/accounts?limit=20', customerToken, 200, null,
                'GET /api/v1/accounts', false);
            break;
        case 2:
            getJson(`/api/v1/accounts/${user.source}`, customerToken, 200, null,
                'GET /api/v1/accounts/{accountNumber}', false);
            break;
        default:
            getJson(`/api/v1/accounts/${user.source}/transactions?limit=10`, customerToken, 200, null,
                'GET /api/v1/accounts/{accountNumber}/transactions', false);
            break;
    }
}

export function measured(data) {
    const user = currentUser(data);
    if (!customerToken) {
        customerToken = login(user.username, user.password, false);
    }

    runMeasuredOperation(user);
}

function currentUser(data) {
    return data.users[(__VU - 1) % data.users.length];
}

function runMeasuredOperation(user) {
    const operation = (__ITER + __VU) % 100;
    if (operation === 0) {
        customerToken = login(user.username, user.password, true);
    } else if (operation <= 25) {
        getJson('/api/v1/accounts?limit=20', customerToken, 200,
            accountListLatency, 'GET /api/v1/accounts', true);
    } else if (operation <= 50) {
        getJson(`/api/v1/accounts/${user.source}`, customerToken, 200,
            accountDetailLatency, 'GET /api/v1/accounts/{accountNumber}', true);
    } else if (operation <= 75) {
        getJson(`/api/v1/accounts/${user.source}/transactions?limit=10`, customerToken, 200,
            historyLatency, 'GET /api/v1/accounts/{accountNumber}/transactions', true);
    } else if (operation <= 83) {
        timedJson('POST', `/api/v1/accounts/${user.source}/deposits`, customerToken,
            { amount: '0.0500' }, 201, depositLatency,
            'POST /api/v1/accounts/{accountNumber}/deposits', true);
    } else if (operation <= 91) {
        timedJson('POST', `/api/v1/accounts/${user.source}/withdrawals`, customerToken,
            { amount: '5.0000' }, 201, withdrawalLatency,
            'POST /api/v1/accounts/{accountNumber}/withdrawals', true);
    } else {
        timedJson('POST', `/api/v1/accounts/${user.source}/transfers`, customerToken,
            { destinationAccountNumber: user.destination, amount: '0.0100' }, 201,
            transferLatency, 'POST /api/v1/accounts/{accountNumber}/transfers', true);
    }
}

function createAccount(customerId, currency, token) {
    return requestJson('POST', `/api/v1/customers/${customerId}/accounts`, token,
        { currency }, 201, true).data.accountNumber;
}

function login(username, password, recordMetric) {
    const response = http.post(`${baseUrl}/api/v1/auth/login`, JSON.stringify({ username, password }), {
        headers: jsonHeaders(),
        tags: { name: 'POST /api/v1/auth/login' },
    });
    const valid = record(response, 200, recordMetric ? loginLatency : null,
        'POST /api/v1/auth/login', recordMetric);
    if (!valid) {
        throw new Error(`Login failed with HTTP ${response.status}.`);
    }
    return response.json('data.accessToken');
}

function getJson(path, token, expectedStatus, metric, name, measuredRequest) {
    const response = http.get(`${baseUrl}${path}`, {
        headers: authorizedHeaders(token),
        tags: { name },
    });
    record(response, expectedStatus, metric, name, measuredRequest);
}

function timedJson(method, path, token, body, expectedStatus, metric, name, measuredRequest) {
    const response = http.request(method, `${baseUrl}${path}`, JSON.stringify(body), {
        headers: authorizedHeaders(token, true),
        tags: { name },
    });
    record(response, expectedStatus, metric, name, measuredRequest);
}

function requestJson(method, path, token, body, expectedStatus, idempotent) {
    const metricPath = path.replace(/\/customers\/[0-9]+/g, '/customers/{customerId}')
                           .replace(/TB[0-9]{14}/g, '{accountNumber}');
    const response = http.request(method, `${baseUrl}${path}`, JSON.stringify(body), {
        headers: authorizedHeaders(token, idempotent),
        tags: { name: `${method} ${metricPath}` },
    });
    if (!record(response, expectedStatus, null, `${method} ${metricPath}`, false)) {
        throw new Error(`${method} ${path} failed with HTTP ${response.status}.`);
    }
    return response.json();
}

function record(response, expectedStatus, metric, name, measuredRequest) {
    const valid = response.status === expectedStatus;
    if (measuredRequest) {
        check(response, {
            [`${name} returns ${expectedStatus}`]: value => value.status === expectedStatus,
        });
        measuredRequests.add(1);
        endpointErrors.add(!valid);
        if (metric) {
            metric.add(response.timings.duration);
        }
    }
    return valid;
}

function authorizedHeaders(token, idempotent) {
    const headers = jsonHeaders();
    headers.Authorization = `Bearer ${token}`;
    if (idempotent) {
        headers['Idempotency-Key'] = uuidV4();
    }
    return headers;
}

function jsonHeaders() {
    return {
        'Content-Type': 'application/json',
        'X-Correlation-Id': uuidV4(),
    };
}

function uuidV4() {
    return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, character => {
        const random = Math.floor(Math.random() * 16);
        const value = character === 'x' ? random : (random & 0x3) | 0x8;
        return value.toString(16);
    });
}
