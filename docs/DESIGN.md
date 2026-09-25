# Design doc

## Project design

Project will be implemented using DDD approach to separate loosely coupled parts of the service in their own spaces.
There will be domain areas such as `security` under `com.tarbank.security` and `core` under `com.tarbank.core`.

DDD will help establish clean boundaries of domain areas.

Every domain area will contain their leayers of logic.
1. Tarbank Core:
    - `controller` layer; REST APIs functionality of the service
    - `api validation` layer; an implementetion of validation of incoming data
    - `service` layer; infrastructural and business logic of the service
    - `transaction validation` layer; goes alongside with `service` layer; provides logic that validates transaction data before executing it
    - `repository` layer; integrates with PostgreSQL using QuieryDSL with Hibernate as ORM core.

2. Security:
    - `controller` layer; REST APIs functionality of the service
    - `api validation` layer; an implementetion of validation of incoming data
    - `service` layer; infrastructural and business logic of the service
    - `repository` layer; integrates with Redis cache and PostgreSQL using QuieryDSL with Hibernate as ORM core.

## Entity design

A single `users` table holds both roles. Separate manager and customer credential tables would duplicate authentication data.

### Users

| Field | Type | Constraints | Description |
|---|---|---|---|
| id | bigserial | primary key | Internal identifier |
| role | varchar(20) | not null; `MANAGER` or `CUSTOMER` | Access role |
| username | varchar(100) | not null; unique | Login name |
| password_hash | varchar(255) | not null | Salted password hash |
| first_name | varchar(100) | not null | First name |
| middle_name | varchar(100) | nullable | Middle name |
| last_name | varchar(100) | not null | Last name |
| email | varchar(320) | nullable | Contact email |
| timezone | varchar(64) | required for customers | IANA timezone for daily limits |
| manager_id | bigint | nullable; FK `users.id`; manager only | Manager responsible for a customer |
| status | varchar(20) | not null; `ACTIVE`, `BLOCKED`, `DEACTIVATED` | Lifecycle state |
| created_at | timestamptz | not null | Creation time |
| updated_at | timestamptz | not null | Last update |
| deactivated_at | timestamptz | nullable | Deactivation time |

### Accounts

| Field | Type | Constraints | Description |
|---|---|---|---|
| id | bigserial | primary key | Internal identifier |
| account_number | varchar(16) | not null; unique; immutable; `TB` plus 14 digits | Public demo account number; final digit may use Luhn validation |
| customer_id | bigint | not null; FK `users.id`; customer only | Owner |
| currency | char(3) | not null; `EUR` or `USD`; immutable | Account currency |
| balance | numeric(19,4) | not null; default 0; check `balance >= 0` | Current balance projection |
| status | varchar(20) | not null; `ACTIVE`, `BLOCKED`, `DEACTIVATED` | Lifecycle state |
| created_by_manager_id | bigint | not null; FK `users.id`; manager only | Creating manager |
| created_at | timestamptz | not null | Creation time |
| updated_at | timestamptz | not null | Last update |
| deactivated_at | timestamptz | nullable | Deactivation time |

### Transactions

One transaction represents one requested deposit, withdrawal, or transfer. Its account history is represented by transaction entries.

| Field | Type | Constraints | Description |
|---|---|---|---|
| id | uuid | primary key | Public transaction identifier |
| type | varchar(20) | not null; `DEPOSIT`, `WITHDRAWAL`, `TRANSFER` | Operation type |
| status | varchar(20) | not null; `PENDING`, `COMPLETED`, `FAILED` | Outcome |
| amount | numeric(19,4) | not null; check `amount > 0` | Requested amount |
| currency | char(3) | not null; `EUR` or `USD` | Transaction currency |
| initiated_by_user_id | bigint | not null; FK `users.id` | Initiating customer |
| source_account_id | bigint | nullable; FK `accounts.id`; required for withdrawal/transfer | Debited account |
| destination_account_id | bigint | nullable; FK `accounts.id`; required for deposit/transfer | Credited account |
| failure_code | varchar(100) | nullable | Safe machine-readable failure reason |
| correlation_id | uuid | not null | Request correlation identifier |
| created_at | timestamptz | not null | Creation time |
| completed_at | timestamptz | nullable | Completion time |

### Transaction entries

A transfer has two entries under one transaction: a negative source entry and a positive destination entry.

| Field | Type | Constraints | Description |
|---|---|---|---|
| id | bigserial | primary key | Entry identifier |
| transaction_id | uuid | not null; FK `transactions.id` | Parent transaction |
| account_id | bigint | not null; FK `accounts.id` | Account history owner |
| amount_delta | numeric(19,4) | not null; check `amount_delta <> 0` | Signed movement |
| balance_after | numeric(19,4) | not null; check `balance_after >= 0` | Resulting balance |
| created_at | timestamptz | not null | Entry time |
|  |  | unique `(transaction_id, account_id)` | Prevent duplicate account entries |

### Idempotency records

| Field | Type | Constraints | Description |
|---|---|---|---|
| id | uuid | primary key | Record identifier |
| customer_id | bigint | not null; FK `users.id` | Requesting customer |
| account_id | bigint | not null; FK `accounts.id` | Source account, or deposit target |
| operation_type | varchar(20) | not null; `DEPOSIT`, `WITHDRAWAL`, `TRANSFER` | Requested operation |
| idempotency_key | varchar(255) | not null | Client retry key |
| request_hash | char(64) | not null | Detects key reuse with a different request |
| status | varchar(20) | not null; `IN_PROGRESS`, `COMPLETED`, `FAILED` | Processing state |
| transaction_id | uuid | nullable; FK `transactions.id` | Resulting transaction |
| response_status | smallint | nullable | Saved HTTP status |
| response_body | jsonb | nullable; no secrets | Saved retry response |
| expires_at | timestamptz | not null | Retention deadline |
| created_at | timestamptz | not null | Creation time |
| updated_at | timestamptz | not null | Last update |
|  |  | unique `(customer_id, account_id, operation_type, idempotency_key)` | Retry scope |

### Audit events

| Field | Type | Constraints | Description |
|---|---|---|---|
| id | uuid | primary key | Event identifier |
| actor_user_id | bigint | nullable; FK `users.id` | Actor |
| action | varchar(100) | not null | E.g. `CUSTOMER_BLOCKED`, `LIMIT_CHANGED` |
| target_type | varchar(50) | not null | Affected entity type |
| target_id | varchar(64) | not null | Affected entity identifier |
| correlation_id | uuid | not null | Request correlation identifier |
| metadata | jsonb | nullable; no credentials, tokens, or unnecessary PII | Safe audit context |
| created_at | timestamptz | not null | Event time |

### Account limit overrides

Overrides expire at midnight in the customer's timezone; defaults become effective again without a reset job.

| Field | Type | Constraints | Description |
|---|---|---|---|
| id | bigserial | primary key | Override identifier |
| account_id | bigint | not null; FK `accounts.id` | Account |
| operation_type | varchar(20) | not null; `WITHDRAWAL` or `TRANSFER` | Limit category |
| limit_amount | numeric(19,4) | not null; check `limit_amount > 0` | Temporary daily limit |
| effective_date | date | not null | Customer-local date |
| expires_at | timestamptz | not null | Customer-local midnight |
| updated_by_user_id | bigint | not null; FK `users.id` | Customer changing the limit |
| created_at | timestamptz | not null | Creation time |
|  |  | unique `(account_id, operation_type, effective_date)` | One override per account, operation, and day |

### Daily limit usage

This durable aggregate is updated in the same transaction as the corresponding money movement.

| Field | Type | Constraints | Description |
|---|---|---|---|
| id | bigserial | primary key | Usage identifier |
| account_id | bigint | not null; FK `accounts.id` | Account |
| operation_type | varchar(20) | not null; `WITHDRAWAL` or `TRANSFER` | Limit category |
| business_date | date | not null | Customer-local date |
| used_amount | numeric(19,4) | not null; default 0; check `used_amount >= 0` | Completed amount counted toward the limit |
| version | integer | not null; default 0 | Optimistic-lock version |
| updated_at | timestamptz | not null | Last update |
|  |  | unique `(account_id, operation_type, business_date)` | One aggregate per account, operation, and day |

## REST API design
