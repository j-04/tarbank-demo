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

Customer usernames are manager-assigned, unique ASCII login identifiers. A separate foreigner flag is not needed: the identity-document issuing country captures the relevant distinction, without changing the username policy.

Each customer record stores one current residential address. Address history is out of scope.

### Sensitive-data protection

Storage encryption at rest protects customer data in deployment. Identity-document numbers receive additional application-level authenticated encryption. The application stores a versioned ciphertext and obtains encryption keys from deployment configuration; production deployments should supply those keys through managed secrets or key-management infrastructure.

A normalized document number is additionally protected with an HMAC-SHA-256 lookup hash. The unique constraint uses this hash rather than the ciphertext, which is intentionally non-deterministic. Document numbers and their lookup hashes must not be exposed through normal API responses or logs.

### Users

| Field | Type | Constraints | Description |
| --- | --- | --- | --- |
| id | bigserial | primary key | Internal identifier |
| role | varchar(20) | not null; `MANAGER` or `CUSTOMER` | Access role |
| username | varchar(100) | not null; unique; ASCII letters, digits, period, underscore, or hyphen | Login name; manager-assigned for customers |
| password_hash | varchar(255) | not null | Salted password hash |
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
| idempotency_key | varchar(255) | not null | Client-supplied request key |
| request_hash | varchar(64) | not null | Hash used to reject a key reused with different input |
| status | varchar(20) | not null; `IN_PROGRESS`, `COMPLETED`, or `FAILED` | Request-processing state |
| transaction_id | uuid | nullable; FK `transactions.id` | Resulting transaction |
| response_status | integer | nullable | HTTP response status retained for replay |
| response_body | jsonb | nullable | HTTP response body retained for replay |
| expires_at | timestamptz | not null | Expiry based on a configurable retention period |
| created_at | timestamptz | not null | Creation time |
| updated_at | timestamptz | not null | Last update |
| (customer_id, account_id, operation_type, idempotency_key) | - | unique | Idempotency scope |

### Audit events

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
| updated_by_manager_id | bigint | not null; FK managers.user_id | Manager who set the override |
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

This initial API slice covers authentication and the essential customer banking flow. All endpoints use JSON and are versioned under /api/v1. The server generates a correlation identifier for each request; it returns that value in the X-Correlation-Id response header and in every response body.

### Initial endpoints

| Endpoint | Authorization | Contract | JSON or outcome |
| --- | --- | --- | --- |
| POST /api/v1/auth/login | None | Request JSON | {<br>&nbsp;&nbsp;"username": "alice",<br>&nbsp;&nbsp;"password": "password"<br>} |
|  |  | 200 response JSON | {<br>&nbsp;&nbsp;"data": {<br>&nbsp;&nbsp;&nbsp;&nbsp;"accessToken": "jwt",<br>&nbsp;&nbsp;&nbsp;&nbsp;"tokenType": "Bearer",<br>&nbsp;&nbsp;&nbsp;&nbsp;"expiresInSeconds": 3600<br>&nbsp;&nbsp;},<br>&nbsp;&nbsp;"correlationId": "uuid"<br>} |
|  |  | Errors | 400 VALIDATION_ERROR; 401 INVALID_CREDENTIALS; 429 RATE_LIMIT_EXCEEDED |
| GET /api/v1/accounts/{accountNumber} | Account-owning customer or an authorized manager | Request JSON | No request body |
|  |  | 200 response JSON | {<br>&nbsp;&nbsp;"data": {<br>&nbsp;&nbsp;&nbsp;&nbsp;"accountNumber": "TB00000000000001",<br>&nbsp;&nbsp;&nbsp;&nbsp;"currency": "EUR",<br>&nbsp;&nbsp;&nbsp;&nbsp;"availableBalance": "125.5000",<br>&nbsp;&nbsp;&nbsp;&nbsp;"status": "ACTIVE"<br>&nbsp;&nbsp;},<br>&nbsp;&nbsp;"correlationId": "uuid"<br>} |
|  |  | Errors | 401 UNAUTHENTICATED; 403 ACCESS_DENIED; 404 RESOURCE_NOT_FOUND; 429 RATE_LIMIT_EXCEEDED |
| POST /api/v1/accounts/{accountNumber}/transfers | Account-owning customer | Request | Header: Idempotency-Key: opaque-client-key<br>Body:<br>{<br>&nbsp;&nbsp;"destinationAccountNumber": "TB00000000000002",<br>&nbsp;&nbsp;"amount": "25.0000"<br>} |
|  |  | 201 response JSON | {<br>&nbsp;&nbsp;"data": {<br>&nbsp;&nbsp;&nbsp;&nbsp;"transactionId": "uuid",<br>&nbsp;&nbsp;&nbsp;&nbsp;"status": "COMPLETED",<br>&nbsp;&nbsp;&nbsp;&nbsp;"sourceAccountNumber": "TB00000000000001",<br>&nbsp;&nbsp;&nbsp;&nbsp;"destinationAccountNumber": "TB00000000000002",<br>&nbsp;&nbsp;&nbsp;&nbsp;"amount": "25.0000",<br>&nbsp;&nbsp;&nbsp;&nbsp;"currency": "EUR",<br>&nbsp;&nbsp;&nbsp;&nbsp;"completedAt": "timestamp"<br>&nbsp;&nbsp;},<br>&nbsp;&nbsp;"correlationId": "uuid"<br>} |
|  |  | Errors | 400 VALIDATION_ERROR; 401 UNAUTHENTICATED; 403 ACCESS_DENIED; 404 RESOURCE_NOT_FOUND; 409 IDEMPOTENCY_CONFLICT or REQUEST_IN_PROGRESS; 422 INSUFFICIENT_FUNDS, DAILY_LIMIT_EXCEEDED, ACCOUNT_NOT_ACTIVE, or CURRENCY_MISMATCH; 429 RATE_LIMIT_EXCEEDED |

The transfer source account is the accountNumber path parameter. Its currency determines the transfer currency, so the request does not provide a separate currency field.

### Response contract

| Response type | HTTP status | Body shape |
| --- | --- | --- |
| Success | Any 2xx status | {<br>&nbsp;&nbsp;"data": {},<br>&nbsp;&nbsp;"correlationId": "uuid"<br>} |
| Error | Any 4xx or 5xx status | {<br>&nbsp;&nbsp;"error": {<br>&nbsp;&nbsp;&nbsp;&nbsp;"code": "STABLE_CODE",<br>&nbsp;&nbsp;&nbsp;&nbsp;"message": "Safe message",<br>&nbsp;&nbsp;&nbsp;&nbsp;"fieldErrors": [ { "field": "amount", "code": "POSITIVE_REQUIRED", "message": "Must be positive" } ]<br>&nbsp;&nbsp;},<br>&nbsp;&nbsp;"correlationId": "uuid"<br>} |

An error object has the following fields: code, message, and optional fieldErrors. Each fieldErrors item contains field, code, and message. Error messages must be safe for clients and must not reveal credentials, tokens, internal implementation details, or whether an unknown username exists.

### Idempotency keys

| Aspect | Contract |
| --- | --- |
| Applies to | Required for money-operation endpoints. The login and account-retrieval endpoints do not use an idempotency key. |
| Header format | Idempotency-Key is a client-generated opaque string up to 255 characters. |
| Scope | A key is scoped to customer, source account or deposit target account, and operation type. The same key may be used in a different scope. |
| Replay | A repeat request with the same scope and normalized request body returns the stored final HTTP status and response body. |
| Conflict | A repeat key in the same scope with a different request body returns 409 IDEMPOTENCY_CONFLICT. |
| Concurrent request | While the first request is in progress, a matching repeat request returns 409 REQUEST_IN_PROGRESS. |
| Retention | Records expire after the configurable idempotency retention period. |

| Status | Error code | Meaning |
| --- | --- | --- |
| 400 | VALIDATION_ERROR | Request data is malformed or violates an input constraint. |
| 401 | UNAUTHENTICATED or INVALID_CREDENTIALS | JWT is missing or invalid, or login credentials are invalid. |
| 403 | ACCESS_DENIED | Authenticated user does not own or may not manage the requested resource. |
| 404 | RESOURCE_NOT_FOUND | Requested resource does not exist or is not visible to the caller. |
| 409 | IDEMPOTENCY_CONFLICT or REQUEST_IN_PROGRESS | Idempotency key is reused with different input, or matching request is still being processed. |
| 422 | INSUFFICIENT_FUNDS, DAILY_LIMIT_EXCEEDED, ACCOUNT_NOT_ACTIVE, or CURRENCY_MISMATCH | Request is valid but cannot be completed under banking rules. |
| 429 | RATE_LIMIT_EXCEEDED | Rate limit has been exceeded; response includes Retry-After. |
| 500 | INTERNAL_ERROR | Unexpected failure; response contains no internal detail. |
