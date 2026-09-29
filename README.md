# Tarbank demo

Tarbank is a Java 25 and Spring Boot 4 banking-service demo. It provides manager-assisted customer and account administration, JWT-authenticated customer access, idempotent deposits, withdrawals and same-currency transfers, daily limits, immutable transaction history, auditing, Redis rate limiting, structured logs, metrics, traces, health probes, and controlled failure simulation.

The supported local deployment is Docker Compose with exactly three services: the application, PostgreSQL, and Redis. PostgreSQL uses a named volume; PostgreSQL, Redis, and the management port are not published to the host.

## Prerequisites

- Docker Engine with Docker Compose v2.
- `curl` and `jq` for the walkthrough examples.
- Java 25 only when running Gradle outside Docker.

## Configure and start

Copy the safe template and replace every `replace-with-...` value. Secrets must remain in the ignored `.env` file.

```bash
cp .env.example .env
docker compose config --quiet
docker compose up --build --detach
docker compose ps
```

The configured manager password must contain exactly 12 printable characters. JWT, document-encryption, document-lookup, and idempotency-fingerprint keys must be distinct local secrets with at least 32 characters.

The application starts only after PostgreSQL and Redis are healthy. Liquibase applies the versioned schema before Hibernate validates it. Wait for the application health check, then inspect readiness from inside the container because the management server is loopback-only:

```bash
docker compose ps --status running
docker compose exec app wget -qO- http://127.0.0.1:8081/actuator/health/liveness
docker compose exec app wget -qO- http://127.0.0.1:8081/actuator/health/readiness
```

Local URLs:

- API: `http://localhost:8080/api/v1`
- Swagger UI: `http://localhost:8080/swagger-ui/index.html`
- OpenAPI JSON: `http://localhost:8080/v3/api-docs`

Use `TARBANK_APP_PORT` in `.env` to change the host application port. HTTPS termination is required outside local development.

## Automated verification

The repeatable verification command runs unit and fresh Testcontainers integration tests, Liquibase migrations, configuration binding, reconciliation assertions, Docker Compose validation, and the Git whitespace check:

```bash
./scripts/verify.sh
```

Tests use disposable PostgreSQL and Redis containers and never depend on Compose data. The representative money scenarios fail when any stored account balance differs from the sum of immutable transaction-entry deltas.

## API conventions

- All operations accept an optional `X-Correlation-Id` UUID. A valid supplied value is returned unchanged; otherwise the service generates one.
- Protected operations require `Authorization: Bearer <JWT>`.
- Customer/account creation, password reset, daily-limit updates, and all money operations require a client-generated UUID v4 `Idempotency-Key`.
- Reuse the exact same key only when retrying the same operation with identical input. A changed request with the same scoped key returns `409 IDEMPOTENCY_CONFLICT`.
- Customer and account updates require the latest `ETag` value in `If-Match`. A stale version returns `412 PRECONDITION_FAILED`; a missing header returns `428 PRECONDITION_REQUIRED`.
- Success bodies use `{ "data": ..., "correlationId": "uuid" }`. Errors use `{ "error": { "code": "...", "message": "...", "fieldErrors": [...] }, "correlationId": "uuid" }`.
- Amounts have up to four decimal places. Account history is cursor-paginated.

Swagger UI's value fields do not generate idempotency keys. Generate a UUID v4, paste it into `Idempotency-Key`, and retain it for a replay. In Postman use `{{$guid}}` for a new operation, save the resolved value in an environment variable, and reuse that saved value only for the retry.

## Safe walkthrough

Disable random failure injection for a deterministic walkthrough and recreate only the application container:

```bash
TARBANK_FAILURE_SIMULATOR_ENABLED=false docker compose up --detach --build --force-recreate app
export BASE_URL=http://localhost:8080
export CUSTOMER_PASSWORD='DemoPass123!'
read -rsp 'Manager password: ' MANAGER_PASSWORD; echo
```

Authenticate the seeded manager without printing the password or token:

```bash
MANAGER_TOKEN=$(curl --fail-with-body --silent --show-error \
  -H 'Content-Type: application/json' \
  -d "$(jq -n --arg username manager --arg password "$MANAGER_PASSWORD" \
       '{username:$username,password:$password}')" \
  "$BASE_URL/api/v1/auth/login" | jq -r '.data.accessToken')
```

Create an adult customer with a demo-only identity placeholder:

```bash
CUSTOMER_KEY=$(cat /proc/sys/kernel/random/uuid)
CUSTOMER_RESPONSE=$(curl --fail-with-body --silent --show-error \
  -H "Authorization: Bearer $MANAGER_TOKEN" \
  -H "Idempotency-Key: $CUSTOMER_KEY" \
  -H 'Content-Type: application/json' \
  -d "$(jq -n --arg password "$CUSTOMER_PASSWORD" '{
    username:"demo.customer",password:$password,firstName:"Demo",lastName:"Customer",
    dateOfBirth:"1990-01-01",email:"demo.customer@example.test",phoneNumber:"+381601234567",
    residentialAddress:{country:"RS",city:"Belgrade",postalCode:"11000",line1:"Demo address"},
    identityDocument:{type:"DEMO",issuingCountry:"RS",number:"DEMO-DOCUMENT-001",expiresOn:"2035-01-01"},
    timezone:"Europe/Belgrade"
  }')" "$BASE_URL/api/v1/customers")
CUSTOMER_ID=$(jq -r '.data.customerId' <<<"$CUSTOMER_RESPONSE")
```

Create two EUR accounts for transfer demonstrations:

```bash
create_account() {
  curl --fail-with-body --silent --show-error \
    -H "Authorization: Bearer $MANAGER_TOKEN" \
    -H "Idempotency-Key: $(cat /proc/sys/kernel/random/uuid)" \
    -H 'Content-Type: application/json' \
    -d '{"currency":"EUR"}' \
    "$BASE_URL/api/v1/customers/$CUSTOMER_ID/accounts" | jq -r '.data.accountNumber'
}
SOURCE_ACCOUNT=$(create_account)
DESTINATION_ACCOUNT=$(create_account)
```

Authenticate the customer and perform the three money operations with a fresh key for each new request:

```bash
CUSTOMER_TOKEN=$(curl --fail-with-body --silent --show-error \
  -H 'Content-Type: application/json' \
  -d "$(jq -n --arg username demo.customer --arg password "$CUSTOMER_PASSWORD" \
       '{username:$username,password:$password}')" \
  "$BASE_URL/api/v1/auth/login" | jq -r '.data.accessToken')

DEPOSIT_KEY=$(cat /proc/sys/kernel/random/uuid)
curl --fail-with-body -H "Authorization: Bearer $CUSTOMER_TOKEN" \
  -H "Idempotency-Key: $DEPOSIT_KEY" -H 'Content-Type: application/json' \
  -d '{"amount":"100.0000"}' "$BASE_URL/api/v1/accounts/$SOURCE_ACCOUNT/deposits"

curl --fail-with-body -H "Authorization: Bearer $CUSTOMER_TOKEN" \
  -H "Idempotency-Key: $(cat /proc/sys/kernel/random/uuid)" -H 'Content-Type: application/json' \
  -d '{"amount":"5.0000"}' "$BASE_URL/api/v1/accounts/$SOURCE_ACCOUNT/withdrawals"

curl --fail-with-body -H "Authorization: Bearer $CUSTOMER_TOKEN" \
  -H "Idempotency-Key: $(cat /proc/sys/kernel/random/uuid)" -H 'Content-Type: application/json' \
  -d "$(jq -n --arg destination "$DESTINATION_ACCOUNT" \
       '{destinationAccountNumber:$destination,amount:"10.0000"}')" \
  "$BASE_URL/api/v1/accounts/$SOURCE_ACCOUNT/transfers"
```

Replay the deposit by repeating its exact key and body; it returns the original result without another balance change. Reusing `DEPOSIT_KEY` with a different amount demonstrates `409 IDEMPOTENCY_CONFLICT`.

Retrieve the account `ETag`, increase today's limits, and fetch paginated history:

```bash
ACCOUNT_ETAG=$(curl --silent --dump-header - --output /dev/null \
  -H "Authorization: Bearer $CUSTOMER_TOKEN" \
  "$BASE_URL/api/v1/accounts/$SOURCE_ACCOUNT" | awk -F': ' 'tolower($1)=="etag" {gsub("\r",""); print $2}')

curl --fail-with-body -X PATCH -H "Authorization: Bearer $CUSTOMER_TOKEN" \
  -H "If-Match: $ACCOUNT_ETAG" \
  -H "Idempotency-Key: $(cat /proc/sys/kernel/random/uuid)" \
  -H 'Content-Type: application/json' -d '{"withdrawalLimit":"1500.0000"}' \
  "$BASE_URL/api/v1/accounts/$SOURCE_ACCOUNT/daily-limits"

curl --fail-with-body -H "Authorization: Bearer $CUSTOMER_TOKEN" \
  "$BASE_URL/api/v1/accounts/$SOURCE_ACCOUNT/transactions?limit=10"
```

To demonstrate stale optimistic concurrency, repeat the limit request with a new idempotency key but the old `ACCOUNT_ETAG`; it returns `412 PRECONDITION_FAILED`. Manager status changes use the same retrieve-ETag then `If-Match` pattern.

## Controlled failure demonstration

Run only one deterministic failure point, submit a money request, then restore normal settings. The `BEFORE_TRANSACTION` example returns the safe `INTERNAL_ERROR` envelope and leaves no transaction, entries, balance, usage, audit, or idempotency state.

```bash
TARBANK_FAILURE_SIMULATOR_ENABLED=true \
TARBANK_FAILURE_SIMULATOR_FAILURE_RATE=1.0 \
TARBANK_FAILURE_SIMULATOR_ALLOWED_POINTS=BEFORE_TRANSACTION \
docker compose up --detach --force-recreate app

# Submit one money request with a fresh Idempotency-Key, then restore deterministic mode.
TARBANK_FAILURE_SIMULATOR_ENABLED=false docker compose up --detach --force-recreate app
```

The automated suite separately verifies failure before transaction, failure inside the transaction, and failure after commit with successful same-key replay.

## Logs, metrics, and traces

```bash
docker compose logs --follow app
docker compose exec app wget -qO- http://127.0.0.1:8081/actuator/metrics/http.server.requests
docker compose exec app wget -qO- http://127.0.0.1:8081/actuator/metrics/tarbank.money.operations
```

Logs are structured JSON. Correlation IDs are also attached to OpenTelemetry spans. Remote metric and trace exporters are optional; the local stack requires no Prometheus, Grafana, Jaeger, Elasticsearch, or Logstash service.

## Reproducible 50-worker load test

The versioned scenario uses `grafana/k6:2.3.0`, seeds 50 isolated customers through the REST API, creates EUR and USD accounts, and funds the EUR source accounts through deposits. It runs a separate 10-second read-only warm-up, waits five seconds for those workers to stop, and then holds 50 active request workers without think-time for a 60-second measured interval.

The standard measured mix is 1 percent login, 75 percent account and history reads, and 24 percent money operations. Only the measured interval contributes to the endpoint trends, check count, and error rate. The runner exports median, p95, and p99 values and validates that every endpoint group contains those statistics. It exits nonzero when an acceptance threshold fails.

Start an application configured for the controlled workload:

```bash
TARBANK_FAILURE_SIMULATOR_ENABLED=false \
TARBANK_RATE_LIMIT_ANONYMOUS_CAPACITY=100000 \
TARBANK_RATE_LIMIT_ANONYMOUS_REFILL_TOKENS_PER_SECOND=100000 \
TARBANK_RATE_LIMIT_LOGIN_IP_CAPACITY=100000 \
TARBANK_RATE_LIMIT_LOGIN_IP_REFILL_TOKENS_PER_SECOND=100000 \
TARBANK_RATE_LIMIT_LOGIN_USERNAME_CAPACITY=100000 \
TARBANK_RATE_LIMIT_LOGIN_USERNAME_REFILL_TOKENS_PER_SECOND=100000 \
TARBANK_RATE_LIMIT_AUTHENTICATED_CAPACITY=100000 \
TARBANK_RATE_LIMIT_AUTHENTICATED_REFILL_TOKENS_PER_SECOND=100000 \
TARBANK_RATE_LIMIT_MONEY_ACCOUNT_CAPACITY=100000 \
TARBANK_RATE_LIMIT_MONEY_ACCOUNT_REFILL_TOKENS_PER_SECOND=100000 \
docker compose up --detach --build --force-recreate app

export MANAGER_PASSWORD
./load-test/run.sh
```

The ignored raw summary is written to `load-test/results/latest.json`. The dated, sanitized result and environment, including any unmet threshold, are recorded in `docs/PERFORMANCE.md`.

## Persistence, shutdown, and reset

Verify PostgreSQL persistence without removing the named volume:

```bash
docker compose stop
docker compose start
docker compose exec app wget -qO- http://127.0.0.1:8081/actuator/health/readiness
```

Normal cleanup preserves database data:

```bash
docker compose down
```

The following command permanently removes the local demo PostgreSQL volume. Use it only when an intentional clean reset is required:

```bash
docker compose down --volumes
```
