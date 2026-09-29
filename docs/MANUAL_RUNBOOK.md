# Manual environment and cURL examples

This guide contains a complete local-only `.env` configuration and copy-paste requests. The values are intentionally predictable demo values. Never use them outside a local disposable environment.

## 1. Copy this configuration into `.env`

```dotenv
TARBANK_APP_PORT=8080
TARBANK_IMAGE_TAG=manual

TARBANK_DATABASE_NAME=tarbank
TARBANK_DATABASE_USERNAME=tarbank
TARBANK_DATABASE_PASSWORD=local-only-postgres-password
TARBANK_DATABASE_URL=jdbc:postgresql://postgres:5432/tarbank

TARBANK_REDIS_HOST=redis
TARBANK_REDIS_PORT=6379
TARBANK_REDIS_PASSWORD=local-only-redis-password

TARBANK_JWT_SIGNING_KEY=local-only-jwt-signing-key-000000000001
TARBANK_DOCUMENT_ENCRYPTION_KEY=local-only-document-encryption-key-000002
TARBANK_DOCUMENT_LOOKUP_HMAC_KEY=local-only-document-lookup-hmac-key-00003
TARBANK_IDEMPOTENCY_FINGERPRINT_HMAC_KEY=local-only-idempotency-fingerprint-key-0004

TARBANK_MANAGER_USERNAME=manager
TARBANK_MANAGER_PASSWORD=Manager1234!
TARBANK_MANAGER_FIRST_NAME=Demo
TARBANK_MANAGER_LAST_NAME=Manager

TARBANK_FAILURE_SIMULATOR_ENABLED=false
TARBANK_FAILURE_SIMULATOR_FAILURE_RATE=0.0
TARBANK_FAILURE_SIMULATOR_ALLOWED_POINTS=BEFORE_TRANSACTION,DURING_TRANSACTION_BEFORE_COMMIT,AFTER_COMMIT_BEFORE_RESPONSE
```

If the manager was previously seeded with another password, recreate the local volumes once. This permanently deletes existing local demo data:

```bash
docker compose down --volumes
docker compose up --detach --build
docker compose ps
```

Wait until the application is healthy:

```bash
until docker compose exec -T app \
  wget -qO- http://127.0.0.1:8081/actuator/health/readiness; do
  sleep 2
done
```

Swagger UI is available at <http://localhost:8080/swagger-ui/index.html>.

## 2. Terminal walkthrough

Run these blocks in order from the repository root. They automatically retain tokens, IDs, ETags, and account numbers in shell variables. `jq` is required.

### Set reusable values

```bash
export BASE_URL=http://localhost:8080
export MANAGER_PASSWORD='Manager1234!'
export CUSTOMER_PASSWORD='DemoPass123!'

RUN_ID=$(date +%s)
CUSTOMER_USERNAME="manual${RUN_ID}"
DOCUMENT_NUMBER="MANUAL-${RUN_ID}"

uuid() {
  cat /proc/sys/kernel/random/uuid
}
```

### Manager login

```bash
MANAGER_TOKEN=$(curl --fail-with-body --silent --show-error \
  --request POST \
  --url "$BASE_URL/api/v1/auth/login" \
  --header 'Content-Type: application/json' \
  --data "$(jq -n --arg password "$MANAGER_PASSWORD" \
    '{username:"manager",password:$password}')" \
  | jq -er '.data.accessToken')

echo 'Manager logged in.'
```

### Create a customer

```bash
CUSTOMER_KEY=$(uuid)
CUSTOMER_BODY=$(jq -n \
  --arg username "$CUSTOMER_USERNAME" \
  --arg password "$CUSTOMER_PASSWORD" \
  --arg document "$DOCUMENT_NUMBER" \
  '{
    username:$username,
    password:$password,
    firstName:"Manual",
    lastName:"Customer",
    dateOfBirth:"1990-01-01",
    email:"manual.customer@example.test",
    phoneNumber:"+381601234567",
    residentialAddress:{
      country:"RS",city:"Belgrade",postalCode:"11000",line1:"Manual address"
    },
    identityDocument:{
      type:"PASSPORT",issuingCountry:"RS",number:$document,expiresOn:"2035-01-01"
    },
    timezone:"Europe/Belgrade"
  }')

CUSTOMER_RESPONSE=$(curl --fail-with-body --silent --show-error \
  --request POST \
  --url "$BASE_URL/api/v1/customers" \
  --header "Authorization: Bearer $MANAGER_TOKEN" \
  --header "Idempotency-Key: $CUSTOMER_KEY" \
  --header 'Content-Type: application/json' \
  --data "$CUSTOMER_BODY")

echo "$CUSTOMER_RESPONSE" | jq
CUSTOMER_ID=$(jq -er '.data.customerId' <<<"$CUSTOMER_RESPONSE")
```

### Get the customer and capture its ETag

```bash
curl --silent --show-error --include \
  --request GET \
  --url "$BASE_URL/api/v1/customers/$CUSTOMER_ID" \
  --header "Authorization: Bearer $MANAGER_TOKEN"

CUSTOMER_ETAG=$(curl --fail-with-body --silent --show-error \
  --dump-header - --output /dev/null \
  --request GET \
  --url "$BASE_URL/api/v1/customers/$CUSTOMER_ID" \
  --header "Authorization: Bearer $MANAGER_TOKEN" \
  | awk -F': ' 'tolower($1)=="etag" {gsub("\r",""); print $2}')

echo "Customer ID: $CUSTOMER_ID; ETag: $CUSTOMER_ETAG"
```

### Update the customer

```bash
curl --silent --show-error --include \
  --request PATCH \
  --url "$BASE_URL/api/v1/customers/$CUSTOMER_ID" \
  --header "Authorization: Bearer $MANAGER_TOKEN" \
  --header "If-Match: $CUSTOMER_ETAG" \
  --header 'Content-Type: application/json' \
  --data '{"middleName":"Demo","email":"manual.updated@example.test"}'
```

Expected: HTTP `200`. Repeating this command with the same ETag returns HTTP `412`.

### Create two EUR accounts and one USD account

```bash
create_account() {
  local currency="$1"
  curl --fail-with-body --silent --show-error \
    --request POST \
    --url "$BASE_URL/api/v1/customers/$CUSTOMER_ID/accounts" \
    --header "Authorization: Bearer $MANAGER_TOKEN" \
    --header "Idempotency-Key: $(uuid)" \
    --header 'Content-Type: application/json' \
    --data "$(jq -n --arg currency "$currency" '{currency:$currency}')" \
    | jq -er '.data.accountNumber'
}

SOURCE_ACCOUNT=$(create_account EUR)
DESTINATION_ACCOUNT=$(create_account EUR)
USD_ACCOUNT=$(create_account USD)

printf 'Source: %s\nDestination: %s\nUSD: %s\n' \
  "$SOURCE_ACCOUNT" "$DESTINATION_ACCOUNT" "$USD_ACCOUNT"
```

### Customer login

```bash
CUSTOMER_TOKEN=$(curl --fail-with-body --silent --show-error \
  --request POST \
  --url "$BASE_URL/api/v1/auth/login" \
  --header 'Content-Type: application/json' \
  --data "$(jq -n --arg username "$CUSTOMER_USERNAME" --arg password "$CUSTOMER_PASSWORD" \
    '{username:$username,password:$password}')" \
  | jq -er '.data.accessToken')

echo 'Customer logged in.'
```

### List the customer's accounts

```bash
curl --silent --show-error --include \
  --request GET \
  --url "$BASE_URL/api/v1/accounts?limit=20" \
  --header "Authorization: Bearer $CUSTOMER_TOKEN"
```

### Deposit money

```bash
DEPOSIT_KEY=$(uuid)

curl --silent --show-error --include \
  --request POST \
  --url "$BASE_URL/api/v1/accounts/$SOURCE_ACCOUNT/deposits" \
  --header "Authorization: Bearer $CUSTOMER_TOKEN" \
  --header "Idempotency-Key: $DEPOSIT_KEY" \
  --header 'Content-Type: application/json' \
  --data '{"amount":"100.0000"}'
```

Expected: HTTP `201`, balance `100.0000`.

Replay the exact command with the same `DEPOSIT_KEY`; it returns the original transaction without depositing twice. Change the amount but keep the same key to see HTTP `409 IDEMPOTENCY_CONFLICT`:

```bash
curl --silent --show-error --include \
  --request POST \
  --url "$BASE_URL/api/v1/accounts/$SOURCE_ACCOUNT/deposits" \
  --header "Authorization: Bearer $CUSTOMER_TOKEN" \
  --header "Idempotency-Key: $DEPOSIT_KEY" \
  --header 'Content-Type: application/json' \
  --data '{"amount":"101.0000"}'
```

### Withdraw money

```bash
curl --silent --show-error --include \
  --request POST \
  --url "$BASE_URL/api/v1/accounts/$SOURCE_ACCOUNT/withdrawals" \
  --header "Authorization: Bearer $CUSTOMER_TOKEN" \
  --header "Idempotency-Key: $(uuid)" \
  --header 'Content-Type: application/json' \
  --data '{"amount":"5.0000"}'
```

Expected: HTTP `201`, source balance `95.0000`.

### Transfer money

```bash
curl --silent --show-error --include \
  --request POST \
  --url "$BASE_URL/api/v1/accounts/$SOURCE_ACCOUNT/transfers" \
  --header "Authorization: Bearer $CUSTOMER_TOKEN" \
  --header "Idempotency-Key: $(uuid)" \
  --header 'Content-Type: application/json' \
  --data "$(jq -n --arg destination "$DESTINATION_ACCOUNT" \
    '{destinationAccountNumber:$destination,amount:"10.0000"}')"
```

Expected: HTTP `201`; source balance `85.0000`, destination balance `10.0000`.

### Get account details and history

```bash
curl --silent --show-error --include \
  --request GET \
  --url "$BASE_URL/api/v1/accounts/$SOURCE_ACCOUNT" \
  --header "Authorization: Bearer $CUSTOMER_TOKEN"

curl --silent --show-error --include \
  --request GET \
  --url "$BASE_URL/api/v1/accounts/$SOURCE_ACCOUNT/transactions?limit=20" \
  --header "Authorization: Bearer $CUSTOMER_TOKEN"
```

### Increase daily limits

```bash
ACCOUNT_ETAG=$(curl --fail-with-body --silent --show-error \
  --dump-header - --output /dev/null \
  --request GET \
  --url "$BASE_URL/api/v1/accounts/$SOURCE_ACCOUNT" \
  --header "Authorization: Bearer $CUSTOMER_TOKEN" \
  | awk -F': ' 'tolower($1)=="etag" {gsub("\r",""); print $2}')

curl --silent --show-error --include \
  --request PATCH \
  --url "$BASE_URL/api/v1/accounts/$SOURCE_ACCOUNT/daily-limits" \
  --header "Authorization: Bearer $CUSTOMER_TOKEN" \
  --header "If-Match: $ACCOUNT_ETAG" \
  --header "Idempotency-Key: $(uuid)" \
  --header 'Content-Type: application/json' \
  --data '{"withdrawalLimit":"1500.0000","transferLimit":"1500.0000"}'
```

Expected: HTTP `200`; both limits are effective until midnight in the customer's timezone.

### Demonstrate a currency mismatch

```bash
curl --silent --show-error --include \
  --request POST \
  --url "$BASE_URL/api/v1/accounts/$SOURCE_ACCOUNT/transfers" \
  --header "Authorization: Bearer $CUSTOMER_TOKEN" \
  --header "Idempotency-Key: $(uuid)" \
  --header 'Content-Type: application/json' \
  --data "$(jq -n --arg destination "$USD_ACCOUNT" \
    '{destinationAccountNumber:$destination,amount:"1.0000"}')"
```

Expected: HTTP `422` with `CURRENCY_MISMATCH`; balances do not change.

### Logout

```bash
curl --silent --show-error --include \
  --request POST \
  --url "$BASE_URL/api/v1/auth/logout" \
  --header "Authorization: Bearer $CUSTOMER_TOKEN"

curl --silent --show-error --include \
  --request GET \
  --url "$BASE_URL/api/v1/accounts" \
  --header "Authorization: Bearer $CUSTOMER_TOKEN"
```

Expected: logout returns HTTP `200`; reuse of the token returns HTTP `401`.

## 3. Postman-ready cURL list

Create a Postman environment containing these variables:

| Variable | Initial value |
| --- | --- |
| `baseUrl` | `http://localhost:8080` |
| `managerToken` | Set from manager login response |
| `customerId` | Set from customer creation response |
| `customerToken` | Set from customer login response |
| `sourceAccount` | Set from first EUR account response |
| `destinationAccount` | Set from second EUR account response |
| `usdAccount` | Set from USD account response |
| `accountEtag` | Copy the `ETag` response header from Get account |
| `depositKey` | Any generated UUID v4; retain it for replay |

In Postman, select **Import**, choose **Raw text**, and paste one command at a time.

### Manager login

```bash
curl --location '{{baseUrl}}/api/v1/auth/login' \
  --header 'Content-Type: application/json' \
  --data '{"username":"manager","password":"Manager1234!"}'
```

### Create customer

Change the username and document number before repeating this request on the same database.

```bash
curl --location '{{baseUrl}}/api/v1/customers' \
  --header 'Authorization: Bearer {{managerToken}}' \
  --header 'Idempotency-Key: {{$guid}}' \
  --header 'Content-Type: application/json' \
  --data-raw '{
    "username":"manual.customer",
    "password":"DemoPass123!",
    "firstName":"Manual",
    "lastName":"Customer",
    "dateOfBirth":"1990-01-01",
    "email":"manual.customer@example.test",
    "phoneNumber":"+381601234567",
    "residentialAddress":{
      "country":"RS",
      "city":"Belgrade",
      "postalCode":"11000",
      "line1":"Manual address"
    },
    "identityDocument":{
      "type":"PASSPORT",
      "issuingCountry":"RS",
      "number":"MANUAL-DOCUMENT-001",
      "expiresOn":"2035-01-01"
    },
    "timezone":"Europe/Belgrade"
  }'
```

### Get customer

```bash
curl --location '{{baseUrl}}/api/v1/customers/{{customerId}}' \
  --header 'Authorization: Bearer {{managerToken}}'
```

### Create an account

Import this request three times: twice with `EUR` and once with `USD`. Save each returned account number in the corresponding Postman variable.

```bash
curl --location '{{baseUrl}}/api/v1/customers/{{customerId}}/accounts' \
  --header 'Authorization: Bearer {{managerToken}}' \
  --header 'Idempotency-Key: {{$guid}}' \
  --header 'Content-Type: application/json' \
  --data '{"currency":"EUR"}'
```

### Customer login

```bash
curl --location '{{baseUrl}}/api/v1/auth/login' \
  --header 'Content-Type: application/json' \
  --data '{"username":"manual.customer","password":"DemoPass123!"}'
```

### List owned accounts

```bash
curl --location '{{baseUrl}}/api/v1/accounts?limit=20' \
  --header 'Authorization: Bearer {{customerToken}}'
```

### Deposit

```bash
curl --location '{{baseUrl}}/api/v1/accounts/{{sourceAccount}}/deposits' \
  --header 'Authorization: Bearer {{customerToken}}' \
  --header 'Idempotency-Key: {{depositKey}}' \
  --header 'Content-Type: application/json' \
  --data '{"amount":"100.0000"}'
```

### Withdraw

```bash
curl --location '{{baseUrl}}/api/v1/accounts/{{sourceAccount}}/withdrawals' \
  --header 'Authorization: Bearer {{customerToken}}' \
  --header 'Idempotency-Key: {{$guid}}' \
  --header 'Content-Type: application/json' \
  --data '{"amount":"5.0000"}'
```

### Transfer

```bash
curl --location '{{baseUrl}}/api/v1/accounts/{{sourceAccount}}/transfers' \
  --header 'Authorization: Bearer {{customerToken}}' \
  --header 'Idempotency-Key: {{$guid}}' \
  --header 'Content-Type: application/json' \
  --data '{"destinationAccountNumber":"{{destinationAccount}}","amount":"10.0000"}'
```

### Get account and capture ETag

```bash
curl --location '{{baseUrl}}/api/v1/accounts/{{sourceAccount}}' \
  --header 'Authorization: Bearer {{customerToken}}'
```

### Increase daily limits

```bash
curl --request PATCH \
  --url '{{baseUrl}}/api/v1/accounts/{{sourceAccount}}/daily-limits' \
  --header 'Authorization: Bearer {{customerToken}}' \
  --header 'If-Match: {{accountEtag}}' \
  --header 'Idempotency-Key: {{$guid}}' \
  --header 'Content-Type: application/json' \
  --data '{"withdrawalLimit":"1500.0000","transferLimit":"1500.0000"}'
```

### Get transaction history

```bash
curl --location '{{baseUrl}}/api/v1/accounts/{{sourceAccount}}/transactions?limit=20' \
  --header 'Authorization: Bearer {{customerToken}}'
```

### Currency mismatch

```bash
curl --location '{{baseUrl}}/api/v1/accounts/{{sourceAccount}}/transfers' \
  --header 'Authorization: Bearer {{customerToken}}' \
  --header 'Idempotency-Key: {{$guid}}' \
  --header 'Content-Type: application/json' \
  --data '{"destinationAccountNumber":"{{usdAccount}}","amount":"1.0000"}'
```

### Logout

```bash
curl --request POST \
  --url '{{baseUrl}}/api/v1/auth/logout' \
  --header 'Authorization: Bearer {{customerToken}}'
```
