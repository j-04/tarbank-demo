# Design document

## Project design

The service is a modular monolith. Domain-driven design principles establish clear boundaries between domain areas; they do not imply a future microservice split.

Code is organized by domain area:

1. Tarbank Core
   - `controller` layer: REST API endpoints
   - `api validation` layer: validation of incoming data
   - `application service` layer: business operations and transaction orchestration
   - `transaction validation` layer: domain rules checked before a money operation
   - `repository` layer: PostgreSQL persistence through JPA/Hibernate; QueryDSL supports complex queries

2. Security
   - `controller` layer: authentication and authorization endpoints
   - `api validation` layer: validation of authentication input
   - `service` layer: authentication, authorization, JWT handling, and rate limiting
   - `repository` layer: PostgreSQL persists users and credentials; Redis stores JWT invalidation and rate-limit data

## API rate limiting

Rate limiting is enforced at the application boundary before a request reaches business logic. It complements, rather than replaces, upstream protection such as a reverse proxy, API gateway, or WAF.

The service uses a Redis-backed token-bucket limiter. Each check atomically refills and consumes tokens, so limits are shared correctly if multiple service instances are introduced. Limit values and token-bucket settings are configuration, not hard-coded business rules.

- A coarse anonymous limit is keyed by client IP address.
- Authentication attempts are limited by both client IP address and username. The IP limit protects password-hash processing; the username limit reduces targeted credential attacks.
- Authenticated requests are limited by user identity. Money-operation endpoints are additionally limited by source-account identity.
- A limited request returns HTTP 429 (Too Many Requests) with a Retry-After header and must not invoke business logic.

Circuit breakers are not used for inbound rate limiting. They remain an option for calls to a failing external dependency.

## Entity design

The shared users table stores authentication credentials and lifecycle data. One-to-one manager and customer profile tables hold role-specific data. Foreign keys can therefore target the correct profile type instead of a generic user.

The users.role value determines access control. The service validates that every user has exactly one matching profile, because a normal relational constraint cannot enforce that rule across these tables.

Customer usernames are manager-assigned, unique lowercase ASCII login identifiers. They contain 3 to 32 characters, start with a letter, and otherwise use only letters, digits, periods, underscores, or hyphens. A separate foreigner flag is not needed: the identity-document issuing country captures the relevant distinction, without changing the username policy.

Each customer record stores one current residential address. Address history is out of scope.

### Sensitive-data protection

Storage encryption at rest protects customer data in deployment. Identity-document numbers receive additional application-level authenticated encryption. The application stores a versioned ciphertext and obtains encryption keys from deployment configuration; production deployments should supply those keys through managed secrets or key-management infrastructure.

A normalized document number is additionally protected with an HMAC-SHA-256 lookup hash. The unique constraint uses this hash rather than the ciphertext, which is intentionally non-deterministic. Document numbers and their lookup hashes must not be exposed through normal API responses or logs.

### Users

| Field | Type | Constraints | Description |
| --- | --- | --- | --- |
| id | bigserial | primary key | Internal identifier |
| role | varchar(20) | not null; `MANAGER` or `CUSTOMER` | Access role |
| username | varchar(32) | not null; unique; lowercase 3 to 32 character username format | Login name; manager-assigned for customers |
| password_hash | varchar(255) | not null; BCrypt hash | Salted one-way password hash |
| first_name | varchar(100) | not null | First name |
| middle_name | varchar(100) | nullable | Middle name |
| last_name | varchar(100) | not null | Last name |
| status | varchar(20) | not null; `ACTIVE`, `BLOCKED`, or `DEACTIVATED` | Lifecycle state |
| created_at | timestamptz | not null | Creation time |
| updated_at | timestamptz | not null | Last update |
| deactivated_at | timestamptz | nullable | Deactivation time |

### Managers

| Field | Type | Constraints | Description |
| --- | --- | --- | --- |
| user_id | bigint | primary key; FK users.id; users.role must be `MANAGER`; validated by service | Manager profile |

### Customers

| Field | Type | Constraints | Description |
| --- | --- | --- | --- |
| user_id | bigint | primary key; FK users.id; users.role must be `CUSTOMER`; validated by service | Customer profile |
| date_of_birth | date | not null; customer must be at least 18 when created; validated by service | Date of birth |
| email | varchar(320) | nullable | Contact email |
| phone_number | varchar(16) | not null; normalized E.164 format; not unique | Contact phone number |
| residence_country | char(2) | not null; ISO 3166-1 alpha-2 | Country of residence |
| residence_city | varchar(100) | not null | City of residence |
| residence_postal_code | varchar(20) | not null | Postal code |
| residence_address_line_1 | varchar(255) | not null | Street address and number |
| residence_address_line_2 | varchar(255) | nullable | Additional address information |
| document_type | varchar(30) | not null | Type of identity document |
| document_issuing_country | char(2) | not null; ISO 3166-1 alpha-2 | Country that issued the identity document |
| document_number_encrypted | bytea | not null; application-level authenticated encryption | Versioned encrypted identity-document number |
| document_number_hash | char(64) | not null; HMAC-SHA-256 lookup hash | Used to detect duplicate identity documents |
| document_expires_on | date | nullable; if present, must be valid at customer creation; validated by service | Identity-document expiry date |
| timezone | varchar(64) | not null; immutable after customer creation | IANA timezone used for daily limits |
| manager_id | bigint | nullable; FK managers.user_id | Manager responsible for the customer |
| version | integer | not null; default 0; incremented on profile or status changes | Source for the customer ETag |
| status_changed_by_manager_id | bigint | nullable; FK managers.user_id | Manager who last changed customer status |
| status_changed_at | timestamptz | nullable | Time of the last customer status change |
| (document_type, document_issuing_country, document_number_hash) | - | unique | Prevent duplicate customer identity documents |

### Accounts

| Field | Type | Constraints | Description |
| --- | --- | --- | --- |
| id | bigserial | primary key | Internal identifier |
| account_number | varchar(16) | not null; unique; immutable; `TB` plus 14 digits | Public demo account number |
| customer_id | bigint | not null; FK customers.user_id | Account owner |
| currency | char(3) | not null; `EUR` or `USD`; immutable | Account currency |
| balance | numeric(19,4) | not null; default 0; greater than or equal to 0 | Current available balance |
| status | varchar(20) | not null; `ACTIVE`, `BLOCKED`, or `DEACTIVATED` | Account lifecycle state |
| status_changed_by_manager_id | bigint | nullable; FK managers.user_id | Manager who last changed account status |
| status_changed_at | timestamptz | nullable | Time of the last account status change |
| management_version | integer | not null; default 0; incremented on status or daily-limit changes, not money movements | Source for the account ETag |
| created_by_manager_id | bigint | not null; FK managers.user_id | Manager who created the account |
| created_at | timestamptz | not null | Creation time |
| updated_at | timestamptz | not null | Last update |
| deactivated_at | timestamptz | nullable | Deactivation time |

### Transactions

One transaction represents a requested deposit, withdrawal, or transfer. Transaction entries provide the account history.

| Field | Type | Constraints | Description |
| --- | --- | --- | --- |
| id | uuid | primary key | Public transaction identifier |
| type | varchar(20) | not null; `DEPOSIT`, `WITHDRAWAL`, or `TRANSFER` | Money-operation type |
| status | varchar(20) | not null; `COMPLETED` or `FAILED` | Final result of the synchronous operation |
| amount | numeric(19,4) | not null; greater than 0 | Operation amount |
| currency | char(3) | not null; `EUR` or `USD` | Operation currency |
| initiated_by_customer_id | bigint | not null; FK customers.user_id | Customer who initiated the operation |
| source_account_id | bigint | nullable; required for withdrawal and transfer; FK `accounts.id` | Debited account |
| destination_account_id | bigint | nullable; required for deposit and transfer; FK `accounts.id` | Credited account |
| failure_code | varchar(100) | nullable | Stable failure reason for a failed transaction |
| correlation_id | uuid | not null | Identifier used to correlate logs, audit events, and API responses |
| created_at | timestamptz | not null | Creation time |
| completed_at | timestamptz | nullable | Completion or failure time |

### Transaction entries

A transfer has two entries: a negative source entry and a positive destination entry. A deposit or withdrawal has one entry.

| Field | Type | Constraints | Description |
| --- | --- | --- | --- |
| id | bigserial | primary key | Internal identifier |
| transaction_id | uuid | not null; FK `transactions.id` | Parent transaction |
| account_id | bigint | not null; FK `accounts.id` | Account affected by the entry |
| amount_delta | numeric(19,4) | not null; not equal to 0 | Signed balance change |
| balance_after | numeric(19,4) | not null; greater than or equal to 0 | Account balance after this entry |
| created_at | timestamptz | not null | Creation time |
| (transaction_id, account_id) | - | unique | One entry per account in a transaction |

#### Transaction invariants

- Transaction entries are immutable.
- A completed transfer has exactly two entries: one negative source entry and one positive destination entry.
- The absolute amount of each transfer entry equals `transactions.amount`; the two entry deltas sum to zero.
- The source and destination accounts differ, use the transaction currency, and the source account belongs to the initiating customer.
- The application service validates these invariants and writes the transaction, entries, balance projection, daily-limit usage, idempotency outcome, and audit event in one database transaction.

### Idempotency records

| Field | Type | Constraints | Description |
| --- | --- | --- | --- |
| id | bigserial | primary key | Internal identifier |
| customer_id | bigint | not null; FK customers.user_id | Customer who made the request |
| account_id | bigint | not null; FK `accounts.id` | Source account, or deposit target |
| operation_type | varchar(20) | not null; `DEPOSIT`, `WITHDRAWAL`, or `TRANSFER` | Requested operation |
| idempotency_key | uuid | not null; UUID v4 | Client-generated request key |
| request_hash | varchar(64) | not null | Hash used to reject a key reused with different input |
| status | varchar(20) | not null; `IN_PROGRESS`, `COMPLETED`, or `FAILED` | Request-processing state |
| transaction_id | uuid | nullable; FK `transactions.id` | Resulting transaction |
| response_status | integer | nullable | HTTP response status retained for replay |
| response_body | jsonb | nullable | HTTP response body retained for replay |
| expires_at | timestamptz | not null | Expiry based on a configurable retention period |
| created_at | timestamptz | not null | Creation time |
| updated_at | timestamptz | not null | Last update |
| (customer_id, account_id, operation_type, idempotency_key) | - | unique | Idempotency scope |

### API request idempotency records

This generic record supports non-money requests that can create resources or have a retry-sensitive side effect. Money operations continue to use the dedicated idempotency records above. A new record starts as IN_PROGRESS. A matching request while it remains IN_PROGRESS returns 409 REQUEST_IN_PROGRESS; a completed record replays its stored final response. Expired records are removed by cleanup, so an abandoned reservation cannot block a key indefinitely.

| Field | Type | Constraints | Description |
| --- | --- | --- | --- |
| id | bigserial | primary key | Internal identifier |
| actor_user_id | bigint | not null; FK users.id | Authenticated actor who made the request |
| operation | varchar(100) | not null | API operation, such as CUSTOMER_CREATE or DAILY_LIMIT_UPDATE |
| resource_scope | varchar(255) | not null | Scope affected by the operation, such as customers or account number |
| idempotency_key | uuid | not null; UUID v4 | Client-generated request key |
| request_hash | varchar(64) | not null | Normalized request hash used to reject changed retries |
| status | varchar(20) | not null; IN_PROGRESS or COMPLETED | Request-processing state |
| response_status | integer | nullable | HTTP response status retained for replay |
| response_body | jsonb | nullable | HTTP response body retained for replay |
| expires_at | timestamptz | not null | Configurable retention deadline |
| created_at | timestamptz | not null | Creation time |
| updated_at | timestamptz | not null | Last state change |
| (actor_user_id, operation, resource_scope, idempotency_key) | - | unique | Generic idempotency scope |

### Audit events

Audit events preserve the complete manager-action history. A customer or account status transition writes an event with action CUSTOMER_STATUS_CHANGED or ACCOUNT_STATUS_CHANGED, the manager as actor, the old and new statuses in metadata, and the same database transaction as the status update. Blocking or deactivating a customer records the same manager as actor for every affected account status transition.

| Field | Type | Constraints | Description |
| --- | --- | --- | --- |
| id | bigserial | primary key | Internal identifier |
| actor_user_id | bigint | nullable; FK `users.id` | Actor, when known |
| action | varchar(100) | not null | Audited action |
| target_type | varchar(100) | not null | Type of affected resource |
| target_id | varchar(255) | not null | Identifier of affected resource |
| correlation_id | uuid | not null | Correlates the event with a request and transaction |
| metadata | jsonb | nullable | Additional audit context |
| created_at | timestamptz | not null | Event time |

### Account limit overrides

Temporary overrides expire at midnight in the customer's timezone. A later change for the same account, operation type, and effective date updates that day's existing override.

| Field | Type | Constraints | Description |
| --- | --- | --- | --- |
| id | bigserial | primary key | Internal identifier |
| account_id | bigint | not null; FK `accounts.id` | Account with the temporary limit |
| operation_type | varchar(20) | not null; `WITHDRAWAL` or `TRANSFER` | Operation constrained by the limit |
| limit_amount | numeric(19,4) | not null; greater than or equal to 5 | Temporary daily limit |
| effective_date | date | not null | Customer-local date to which the override applies |
| expires_at | timestamptz | not null | Midnight in the customer's timezone |
| updated_by_user_id | bigint | not null; FK users.id | Customer or manager who set the override |
| created_at | timestamptz | not null | Creation time |
| updated_at | timestamptz | not null | Last update |
| (account_id, operation_type, effective_date) | - | unique | One override for a given account, operation type, and day |

### Daily limit usage

This durable aggregate is updated in the same database transaction as the money operation. The money-operation transaction locks and updates the corresponding row; it does not use a separate optimistic-lock version.

| Field | Type | Constraints | Description |
| --- | --- | --- | --- |
| id | bigserial | primary key | Internal identifier |
| account_id | bigint | not null; FK `accounts.id` | Account whose limit is consumed |
| operation_type | varchar(20) | not null; `WITHDRAWAL` or `TRANSFER` | Operation constrained by the limit |
| usage_date | date | not null | Customer-local date |
| used_amount | numeric(19,4) | not null; default 0; greater than or equal to 0 | Amount used on this date |
| updated_at | timestamptz | not null | Last update |
| (account_id, operation_type, usage_date) | - | unique | One daily aggregate per account, operation type, and day |

## REST API

All endpoints use JSON and are versioned under /api/v1. A client may supply X-Correlation-Id as a UUID. When supplied, the service preserves that identifier unchanged in logs, response headers, response bodies, and downstream calls. When the header is absent, the service generates a UUID. An invalid correlation identifier is rejected with 400 VALIDATION_ERROR. Timestamps use ISO 8601 UTC strings. Money amounts are decimal strings with up to four fractional digits.

All endpoints accept the optional X-Correlation-Id UUID header. Protected endpoints require the Authorization: Bearer JWT header. No response returns a password, password hash, identity-document number, document lookup hash, or encryption material. Password creation and reset occur only through the manager-assisted secure branch terminal flow; there is no standalone public password-reset endpoint.

### Authentication

| Endpoint | Description |
| --- | --- |
| POST /api/v1/auth/login | Authenticates a user and issues a JWT access token. |
| POST /api/v1/auth/logout | Invalidates the current JWT so it cannot be used again. |

| Endpoint | Authorization | Headers | Query parameters | Request body | Successful response | Expected errors |
| --- | --- | --- | --- | --- | --- | --- |
| POST /api/v1/auth/login | None | Content-Type: application/json | None | {<br>&nbsp;&nbsp;"username": "alice",<br>&nbsp;&nbsp;"password": "DemoPass123!"<br>} | 200: {<br>&nbsp;&nbsp;"data": {<br>&nbsp;&nbsp;&nbsp;&nbsp;"accessToken": "jwt",<br>&nbsp;&nbsp;&nbsp;&nbsp;"tokenType": "Bearer",<br>&nbsp;&nbsp;&nbsp;&nbsp;"expiresInSeconds": 3600<br>&nbsp;&nbsp;},<br>&nbsp;&nbsp;"correlationId": "uuid"<br>} | 400 VALIDATION_ERROR; 401 INVALID_CREDENTIALS; 429 RATE_LIMIT_EXCEEDED |
| POST /api/v1/auth/logout | Authenticated user | Authorization: Bearer JWT | None | None | 200: {<br>&nbsp;&nbsp;"data": { "status": "LOGGED_OUT" },<br>&nbsp;&nbsp;"correlationId": "uuid"<br>} | 401 UNAUTHENTICATED; 429 RATE_LIMIT_EXCEEDED |

### Customer management

| Endpoint | Description |
| --- | --- |
| POST /api/v1/customers | Creates an adult customer during manager-assisted branch onboarding. |
| GET /api/v1/customers | Lists customers for managers, including blocked and deactivated records when requested. |
| GET /api/v1/customers/{customerId} | Retrieves one customer profile for a manager. |
| PATCH /api/v1/customers/{customerId} | Updates the mutable details of a customer profile. |
| PATCH /api/v1/customers/{customerId}/status | Blocks, unblocks, or permanently deactivates a customer and their accounts. |

Customer endpoints are for managers. Customer response bodies omit the identity-document number; managers may receive document type, issuing country, and expiry date only.

| Endpoint | Authorization | Headers | Query parameters | Request body | Successful response | Expected errors |
| --- | --- | --- | --- | --- | --- | --- |
| POST /api/v1/customers | Manager | Authorization: Bearer JWT<br>Content-Type: application/json<br>Idempotency-Key: UUID v4 | None | {<br>&nbsp;&nbsp;"username": "alice",<br>&nbsp;&nbsp;"password": "DemoPass123!",<br>&nbsp;&nbsp;"firstName": "Alice",<br>&nbsp;&nbsp;"lastName": "Example",<br>&nbsp;&nbsp;"dateOfBirth": "1990-01-01",<br>&nbsp;&nbsp;"email": "alice@example.test",<br>&nbsp;&nbsp;"phoneNumber": "+381601234567",<br>&nbsp;&nbsp;"residentialAddress": { "country": "RS", "city": "Belgrade", "postalCode": "11000", "line1": "Example 1" },<br>&nbsp;&nbsp;"identityDocument": { "type": "PASSPORT", "issuingCountry": "RS", "number": "A1234567", "expiresOn": "2030-01-01" },<br>&nbsp;&nbsp;"timezone": "Europe/Belgrade"<br>} | 201: {<br>&nbsp;&nbsp;"data": { "customerId": 42, "username": "alice", "status": "ACTIVE" },<br>&nbsp;&nbsp;"correlationId": "uuid"<br>} | 400 VALIDATION_ERROR; 409 USERNAME_ALREADY_EXISTS, IDENTITY_DOCUMENT_ALREADY_EXISTS, IDEMPOTENCY_CONFLICT, or REQUEST_IN_PROGRESS; 422 CUSTOMER_MUST_BE_ADULT |
| GET /api/v1/customers | Manager | Authorization: Bearer JWT | status, cursor, limit | None | 200: {<br>&nbsp;&nbsp;"data": { "items": [ { "customerId": 42, "username": "alice", "status": "ACTIVE" } ], "nextCursor": "opaque-cursor" },<br>&nbsp;&nbsp;"correlationId": "uuid"<br>} | 400 VALIDATION_ERROR; 401 UNAUTHENTICATED; 403 ACCESS_DENIED |
| GET /api/v1/customers/{customerId} | Manager | Authorization: Bearer JWT | None | None | 200: {<br>&nbsp;&nbsp;"data": { "customerId": 42, "username": "alice", "firstName": "Alice", "lastName": "Example", "dateOfBirth": "1990-01-01", "email": "alice@example.test", "phoneNumber": "+381601234567", "residentialAddress": { "country": "RS", "city": "Belgrade", "postalCode": "11000", "line1": "Example 1" }, "identityDocument": { "type": "PASSPORT", "issuingCountry": "RS", "expiresOn": "2030-01-01" }, "timezone": "Europe/Belgrade", "status": "ACTIVE" },<br>&nbsp;&nbsp;"correlationId": "uuid"<br>} | 401 UNAUTHENTICATED; 403 ACCESS_DENIED; 404 RESOURCE_NOT_FOUND |
| PATCH /api/v1/customers/{customerId} | Manager | Authorization: Bearer JWT<br>Content-Type: application/json<br>If-Match: customer-vN | None | Partial mutable customer profile, for example:<br>{<br>&nbsp;&nbsp;"phoneNumber": "+381601234568",<br>&nbsp;&nbsp;"residentialAddress": { "country": "RS", "city": "Novi Sad", "postalCode": "21000", "line1": "Example 2" }<br>} | 200: updated customer response | 400 VALIDATION_ERROR; 401 UNAUTHENTICATED; 403 ACCESS_DENIED; 404 RESOURCE_NOT_FOUND; 412 PRECONDITION_FAILED; 428 PRECONDITION_REQUIRED |
| PATCH /api/v1/customers/{customerId}/status | Manager | Authorization: Bearer JWT<br>Content-Type: application/json<br>If-Match: customer-vN | None | { "status": "BLOCKED" }<br>Allowed transitions: ACTIVE to BLOCKED, BLOCKED to ACTIVE, and ACTIVE or BLOCKED to DEACTIVATED. | 200: {<br>&nbsp;&nbsp;"data": { "customerId": 42, "status": "BLOCKED" },<br>&nbsp;&nbsp;"correlationId": "uuid"<br>} | 400 VALIDATION_ERROR; 401 UNAUTHENTICATED; 403 ACCESS_DENIED; 404 RESOURCE_NOT_FOUND; 412 PRECONDITION_FAILED; 428 PRECONDITION_REQUIRED; 409 INVALID_STATUS_TRANSITION |

### Account management and history

| Endpoint | Description |
| --- | --- |
| POST /api/v1/customers/{customerId}/accounts | Creates a zero-balance EUR or USD account for a customer. |
| GET /api/v1/customers/{customerId}/accounts | Lists accounts belonging to a manager-selected customer. |
| GET /api/v1/accounts | Lists the authenticated customer accounts. |
| GET /api/v1/accounts/{accountNumber} | Retrieves an account balance and current status. |
| PATCH /api/v1/accounts/{accountNumber}/daily-limits | Sets one or both daily limits for the current customer-local day. |
| PATCH /api/v1/accounts/{accountNumber}/status | Blocks, unblocks, or permanently deactivates an account. |
| GET /api/v1/accounts/{accountNumber}/transactions | Returns paginated account history. |

| Endpoint | Authorization | Headers | Query parameters | Request body | Successful response | Expected errors |
| --- | --- | --- | --- | --- | --- | --- |
| POST /api/v1/customers/{customerId}/accounts | Manager | Authorization: Bearer JWT<br>Content-Type: application/json<br>Idempotency-Key: UUID v4 | None | { "currency": "EUR" } | 201: {<br>&nbsp;&nbsp;"data": { "accountNumber": "TB00000000000001", "customerId": 42, "currency": "EUR", "availableBalance": "0.0000", "status": "ACTIVE" },<br>&nbsp;&nbsp;"correlationId": "uuid"<br>} | 400 VALIDATION_ERROR; 401 UNAUTHENTICATED; 403 ACCESS_DENIED; 404 RESOURCE_NOT_FOUND; 409 IDEMPOTENCY_CONFLICT or REQUEST_IN_PROGRESS |
| GET /api/v1/customers/{customerId}/accounts | Manager | Authorization: Bearer JWT | cursor, limit | None | 200: {<br>&nbsp;&nbsp;"data": { "items": [ { "accountNumber": "TB00000000000001", "currency": "EUR", "availableBalance": "0.0000", "status": "ACTIVE" } ], "nextCursor": "opaque-cursor" },<br>&nbsp;&nbsp;"correlationId": "uuid"<br>} | 401 UNAUTHENTICATED; 403 ACCESS_DENIED; 404 RESOURCE_NOT_FOUND |
| GET /api/v1/accounts | Customer | Authorization: Bearer JWT | cursor, limit | None | 200: customer account page using the account list response contract above | 401 UNAUTHENTICATED; 429 RATE_LIMIT_EXCEEDED |
| GET /api/v1/accounts/{accountNumber} | Account-owning customer or an authorized manager | Authorization: Bearer JWT | None | None | 200: {<br>&nbsp;&nbsp;"data": { "accountNumber": "TB00000000000001", "currency": "EUR", "availableBalance": "125.5000", "status": "ACTIVE" },<br>&nbsp;&nbsp;"correlationId": "uuid"<br>} | 401 UNAUTHENTICATED; 403 ACCESS_DENIED; 404 RESOURCE_NOT_FOUND; 429 RATE_LIMIT_EXCEEDED |
| PATCH /api/v1/accounts/{accountNumber}/daily-limits | Account-owning customer or an authorized manager | Authorization: Bearer JWT<br>Content-Type: application/json<br>Idempotency-Key: UUID v4<br>If-Match: account-vN | None | At least one limit is required:<br>{<br>&nbsp;&nbsp;"withdrawalLimit": "500.0000",<br>&nbsp;&nbsp;"transferLimit": "750.0000"<br>} | 200: {<br>&nbsp;&nbsp;"data": { "accountNumber": "TB00000000000001", "withdrawalLimit": "500.0000", "transferLimit": "750.0000", "expiresAt": "timestamp" },<br>&nbsp;&nbsp;"correlationId": "uuid"<br>} | 400 VALIDATION_ERROR; 401 UNAUTHENTICATED; 403 ACCESS_DENIED; 404 RESOURCE_NOT_FOUND; 409 IDEMPOTENCY_CONFLICT or REQUEST_IN_PROGRESS; 412 PRECONDITION_FAILED; 428 PRECONDITION_REQUIRED; 422 DAILY_LIMIT_OUT_OF_RANGE |
| PATCH /api/v1/accounts/{accountNumber}/status | Manager | Authorization: Bearer JWT<br>Content-Type: application/json<br>If-Match: account-vN | None | { "status": "BLOCKED" }<br>Allowed transitions: ACTIVE to BLOCKED, BLOCKED to ACTIVE, and ACTIVE or BLOCKED to DEACTIVATED. | 200: {<br>&nbsp;&nbsp;"data": { "accountNumber": "TB00000000000001", "status": "BLOCKED" },<br>&nbsp;&nbsp;"correlationId": "uuid"<br>} | 400 VALIDATION_ERROR; 401 UNAUTHENTICATED; 403 ACCESS_DENIED; 404 RESOURCE_NOT_FOUND; 412 PRECONDITION_FAILED; 428 PRECONDITION_REQUIRED; 409 INVALID_STATUS_TRANSITION |
| GET /api/v1/accounts/{accountNumber}/transactions | Account-owning customer or an authorized manager | Authorization: Bearer JWT | cursor, limit, from, to | None | 200: {<br>&nbsp;&nbsp;"data": { "items": [ { "transactionId": "uuid", "type": "TRANSFER", "status": "COMPLETED", "amountDelta": "-25.0000", "balanceAfter": "100.5000", "currency": "EUR", "createdAt": "timestamp" } ], "nextCursor": "opaque-cursor" },<br>&nbsp;&nbsp;"correlationId": "uuid"<br>} | 400 VALIDATION_ERROR; 401 UNAUTHENTICATED; 403 ACCESS_DENIED; 404 RESOURCE_NOT_FOUND |

### Money operations

| Endpoint | Description |
| --- | --- |
| POST /api/v1/accounts/{accountNumber}/deposits | Simulates an owner top-up into an active account. |
| POST /api/v1/accounts/{accountNumber}/withdrawals | Withdraws money from an active account within the available balance and daily limit. |
| POST /api/v1/accounts/{accountNumber}/transfers | Transfers money from an owned active account to another active account in the same currency. |

All money-operation endpoints require an Idempotency-Key UUID v4 header. The key uses the idempotency scope and replay rules defined below.

| Endpoint | Authorization | Headers | Query parameters | Request body | Successful response | Expected errors |
| --- | --- | --- | --- | --- | --- | --- |
| POST /api/v1/accounts/{accountNumber}/deposits | Account-owning customer | Authorization: Bearer JWT<br>Content-Type: application/json<br>Idempotency-Key: UUID v4 | None | { "amount": "25.0000" } | 201: {<br>&nbsp;&nbsp;"data": { "transactionId": "uuid", "status": "COMPLETED", "accountNumber": "TB00000000000001", "amount": "25.0000", "currency": "EUR", "balanceAfter": "150.5000", "completedAt": "timestamp" },<br>&nbsp;&nbsp;"correlationId": "uuid"<br>} | 400 VALIDATION_ERROR; 401 UNAUTHENTICATED; 403 ACCESS_DENIED; 404 RESOURCE_NOT_FOUND; 409 IDEMPOTENCY_CONFLICT or REQUEST_IN_PROGRESS; 422 ACCOUNT_NOT_ACTIVE; 429 RATE_LIMIT_EXCEEDED |
| POST /api/v1/accounts/{accountNumber}/withdrawals | Account-owning customer | Authorization: Bearer JWT<br>Content-Type: application/json<br>Idempotency-Key: UUID v4 | None | { "amount": "25.0000" } | 201: {<br>&nbsp;&nbsp;"data": { "transactionId": "uuid", "status": "COMPLETED", "accountNumber": "TB00000000000001", "amount": "25.0000", "currency": "EUR", "balanceAfter": "100.5000", "completedAt": "timestamp" },<br>&nbsp;&nbsp;"correlationId": "uuid"<br>} | 400 VALIDATION_ERROR; 401 UNAUTHENTICATED; 403 ACCESS_DENIED; 404 RESOURCE_NOT_FOUND; 409 IDEMPOTENCY_CONFLICT or REQUEST_IN_PROGRESS; 422 MINIMUM_WITHDRAWAL_AMOUNT, INSUFFICIENT_FUNDS, DAILY_LIMIT_EXCEEDED, or ACCOUNT_NOT_ACTIVE; 429 RATE_LIMIT_EXCEEDED |
| POST /api/v1/accounts/{accountNumber}/transfers | Account-owning customer | Authorization: Bearer JWT<br>Content-Type: application/json<br>Idempotency-Key: UUID v4 | None | {<br>&nbsp;&nbsp;"destinationAccountNumber": "TB00000000000002",<br>&nbsp;&nbsp;"amount": "25.0000"<br>} | 201: {<br>&nbsp;&nbsp;"data": { "transactionId": "uuid", "status": "COMPLETED", "sourceAccountNumber": "TB00000000000001", "destinationAccountNumber": "TB00000000000002", "amount": "25.0000", "currency": "EUR", "completedAt": "timestamp" },<br>&nbsp;&nbsp;"correlationId": "uuid"<br>} | 400 VALIDATION_ERROR; 401 UNAUTHENTICATED; 403 ACCESS_DENIED; 404 RESOURCE_NOT_FOUND; 409 IDEMPOTENCY_CONFLICT or REQUEST_IN_PROGRESS; 422 INSUFFICIENT_FUNDS, DAILY_LIMIT_EXCEEDED, ACCOUNT_NOT_ACTIVE, or CURRENCY_MISMATCH; 429 RATE_LIMIT_EXCEEDED |

The source account is the accountNumber path parameter. The source account currency determines the operation currency, so money-operation requests do not include a separate currency field.

### Notification extension

Notifications remain an internal extension point for this demo. The core service sends a final money-operation outcome to a NotificationService port after the database transaction completes. The default adapter may be a no-op or structured-log implementation, and an adapter failure must not alter a committed money operation.

There is no notification REST endpoint, inbox, persistence model, delivery channel, or delivery guarantee. Durable delivery requires a future transactional-outbox design; see docs/NOTES.md.

### Update concurrency and non-money idempotency

The API applies a control that matches the operation type. An idempotency replay lookup runs before a version check, so a valid retry returns its stored result even after the resource version changes. A new request with a stale ETag returns 412 PRECONDITION_FAILED.

| Operation | Required request headers | Server behavior |
| --- | --- | --- |
| POST /customers | Idempotency-Key: UUID v4 | Uses generic request idempotency scoped to the manager and customer-creation operation. |
| POST /customers/{customerId}/accounts | Idempotency-Key: UUID v4 | Uses generic request idempotency scoped to the manager, customer, and account-creation operation. |
| PATCH /customers/{customerId} and /status | If-Match: customer-vN | Uses the customer version. A successful update increments the version and returns the next ETag. |
| PATCH /accounts/{accountNumber}/daily-limits | Idempotency-Key: UUID v4 and If-Match: account-vN | Uses generic request idempotency and the account management version in one database transaction. |
| PATCH /accounts/{accountNumber}/status | If-Match: account-vN | Uses the account management version. A successful update increments the version and returns the next ETag. |
| Money operations | Idempotency-Key: UUID v4 | Uses the dedicated money-operation idempotency record and financial transaction locking. |

GET customer and account detail responses return ETag headers based on their corresponding versions. The service calculates the customer-local effective date for a daily-limit update at its first execution and stores the replay result, so a retry after midnight does not create a new override.

### Response contract

| Response type | HTTP status | Body shape |
| --- | --- | --- |
| Success | Any 2xx status | {<br>&nbsp;&nbsp;"data": {},<br>&nbsp;&nbsp;"correlationId": "uuid"<br>} |
| Error | Any 4xx or 5xx status | {<br>&nbsp;&nbsp;"error": {<br>&nbsp;&nbsp;&nbsp;&nbsp;"code": "STABLE_CODE",<br>&nbsp;&nbsp;&nbsp;&nbsp;"message": "Safe message",<br>&nbsp;&nbsp;&nbsp;&nbsp;"fieldErrors": [ { "field": "amount", "code": "POSITIVE_REQUIRED", "message": "Must be positive" } ]<br>&nbsp;&nbsp;},<br>&nbsp;&nbsp;"correlationId": "uuid"<br>} |

An error object has the following fields: code, message, and optional fieldErrors. Each fieldErrors item contains field, code, and message. Error messages must be safe for clients and must not reveal credentials, tokens, internal implementation details, or whether an unknown username exists.

### Idempotency keys

| Aspect | Contract |
| --- | --- |
| Applies to | Required for money operations, customer creation, account creation, and daily-limit updates. Other current endpoints rely on their idempotent desired-state semantics or ETag preconditions. |
| Header format | Idempotency-Key is a client-generated UUID v4. |
| Money-operation scope | Customer, source account or deposit target account, and operation type. |
| Generic-request scope | Authenticated actor, API operation, resource scope, and idempotency key. |
| Replay | A repeat request with the same scope and normalized request body returns the stored final HTTP status and response body. |
| Conflict | A repeat key in the same scope with a different request body returns 409 IDEMPOTENCY_CONFLICT. |
| Concurrent request | While the first request is in progress, a matching repeat request returns 409 REQUEST_IN_PROGRESS. |
| Retention | Records expire after the configurable idempotency retention period. |

### Internal service codes

Public API error codes are stable and safe for clients. Internal service codes classify the underlying event for logs and support; they are not returned to clients. A client receives the public error code and correlationId only.

Internal codes use the form TAR-AREA-NNN. Structured logs for an error include internalCode, publicErrorCode, HTTP status, and correlationId, but no credentials, tokens, or sensitive customer data.

| Internal code | Public error code | HTTP status | Meaning |
| --- | --- | --- | --- |
| TAR-API-001 | VALIDATION_ERROR | 400 | Request binding or validation failed. |
| TAR-AUTH-001 | INVALID_CREDENTIALS | 401 | Login credentials were rejected. |
| TAR-CUSTOMER-001 | CUSTOMER_MUST_BE_ADULT | 422 | Customer creation was rejected because the customer is underage. |
| TAR-TRANSFER-001 | INSUFFICIENT_FUNDS | 422 | Transfer cannot be completed because funds are insufficient. |
| TAR-IDEMPOTENCY-001 | IDEMPOTENCY_CONFLICT | 409 | Idempotency key was reused with different input. |
| TAR-INFRA-001 | INTERNAL_ERROR | 500 | Unexpected application, database, or dependency failure. |

| Status | Error code | Meaning |
| --- | --- | --- |
| 400 | VALIDATION_ERROR | Request data is malformed or violates an input constraint. |
| 401 | UNAUTHENTICATED or INVALID_CREDENTIALS | JWT is missing or invalid, or login credentials are invalid. |
| 403 | ACCESS_DENIED | Authenticated user does not own or may not manage the requested resource. |
| 404 | RESOURCE_NOT_FOUND | Requested resource does not exist or is not visible to the caller. |
| 409 | USERNAME_ALREADY_EXISTS, IDENTITY_DOCUMENT_ALREADY_EXISTS, IDEMPOTENCY_CONFLICT, REQUEST_IN_PROGRESS, or INVALID_STATUS_TRANSITION | A unique value conflicts, a duplicate request conflicts, a matching request is in progress, or a lifecycle transition is invalid. |
| 412 | PRECONDITION_FAILED | The supplied ETag is stale. |
| 428 | PRECONDITION_REQUIRED | An ETag is required for this update. |
| 422 | CUSTOMER_MUST_BE_ADULT, MINIMUM_WITHDRAWAL_AMOUNT, INSUFFICIENT_FUNDS, DAILY_LIMIT_EXCEEDED, DAILY_LIMIT_OUT_OF_RANGE, ACCOUNT_NOT_ACTIVE, or CURRENCY_MISMATCH | Request is valid but cannot be completed under banking rules. |
| 429 | RATE_LIMIT_EXCEEDED | Rate limit has been exceeded; response includes Retry-After. |
| 500 | INTERNAL_ERROR | Unexpected failure; response contains no internal detail. |
