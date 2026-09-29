const summary = JSON.parse(open('./results/latest.json'));
const endpointMetrics = [
    'login_latency',
    'account_list_latency',
    'account_detail_latency',
    'history_latency',
    'deposit_latency',
    'withdrawal_latency',
    'transfer_latency',
];

for (const metricName of endpointMetrics) {
    const metric = summary.metrics[metricName];
    if (!metric) {
        throw new Error(`Summary is missing ${metricName}.`);
    }
    for (const statistic of ['med', 'p(95)', 'p(99)']) {
        if (typeof metric[statistic] !== 'number') {
            throw new Error(`Summary metric ${metricName} is missing numeric ${statistic}.`);
        }
    }
}

if (!summary.metrics.measured_requests || summary.metrics.measured_requests.count <= 1000) {
    throw new Error('Summary does not contain enough measured requests.');
}

export const options = {
    vus: 1,
    iterations: 1,
};

export default function () {
}
