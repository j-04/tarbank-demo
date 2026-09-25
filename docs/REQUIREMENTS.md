# TARBANK

## Overview

Develop a service, in a programming language of your choice, that simulates basic banking operations. The service manages accounts and processes deposits, withdrawals, and transfers between accounts.

This test assesses problem-solving skills through a system that reflects real-world banking constraints. It also evaluates adherence to engineering best practices; incorporate anything relevant to both areas.

## Service delivery

- Provide a REST API.

## Access and identity

### Roles

- Support two roles: `manager` and `customer`.
- A customer can view and manage only their own permitted resources and can deposit, withdraw, and transfer funds.
- A manager can create and manage customers and accounts and can block or unblock them.
- APIs that require manager authorization must be accessible only to manager accounts because customers cannot obtain certain services without manager authorization.

### Authentication

- Use JWT authentication as the basic security mechanism.
- Authenticate with a unique username and password; passwords must be salted and hashed.
- JWT expiry must be configurable and default to one hour.
- Passwords and tokens must not be logged or stored in plain text.
- Seed one or more manager accounts at startup. Manager creation is out of scope for the REST API. Startup seeding must be idempotent and use configured credentials; passwords must be stored only as hashes.
- Support password resets only through a manager. For this demo, assume a password is set when a customer is created. It can be reset only by visiting an offline branch, where the customer enters a new password through a secure terminal.

## Customer lifecycle

A manager manages the customer record throughout its lifecycle.

- Create a customer.
- Retrieve an existing customer.
- Update an existing customer.
- Block a customer and their accounts; this can be restored.
- Deactivate a customer and their accounts; this cannot be reverted, and data remains visible to managers.

## Account lifecycle

A customer can own several accounts. A manager creates each account on behalf of the customer; customers cannot create accounts themselves.

When creating an account, select one supported currency. The account starts with a zero balance, and the currency cannot later be changed.

- Retrieve an existing account.
- Update an existing account: only daily limits can be changed; other critical information cannot be changed.
- Block an account; this can be restored.
- Deactivate an account; this cannot be reverted, and data remains visible to managers.

## Money movement

### Supported currencies and amounts

- Support EUR and USD.
- Use `DECIMAL(19, 4)` value precision for avoiding floating-point rounding errors.
- Amounts cannot be below zero. Deposits, withdrawals, and transfers of zero are not permitted; every money-moving operation requires a positive amount.
- Cross-currency deposits, withdrawals, and transfers are not supported, but the code must support future integration of that feature.

### Banking operations

- Check an account balance.
- Deposits are simulated top-ups and may be made only by the account owner.
- A customer can deposit into or withdraw only from their own account.
- Withdraw money from an account without allowing overdrafts.
- A customer can transfer money from their own account to another customer's account.
- Funds cannot be deposited, withdrawn, or transferred to or from a blocked account.

## Limits, consistency, and failure handling

### Daily limits

The daily amount that can be withdrawn or transferred from an account must be configurable.

- Limits are per account.
- Withdrawal and transfer limits are separate and receive independent defaults: EUR 1,000 and USD 1,000.
- Customers can update limits without manager approval. An updated limit applies until the end of the current day and resets to the default value at 12:00 a.m.
- A customer may increase a limit for the current day up to EUR 3,000 or USD 3,000.
- Set minimum withdrawal amounts of EUR 5 and USD 5.
- A customer's timezone defines a day.
- Failed operations do not count toward limits.
- Limits must remain durable under concurrent changes and be atomic and idempotent.

### Transaction safety

- Transactions must be atomic. Concurrent transfers and withdrawals must not allow an account balance to go below zero.
- Protect accounts from concurrent modifications through a high level of transaction isolation.
- Support idempotency to prevent accidental duplicate operations. A client sends an idempotency key for each money-moving operation, and the same key returns the original outcome.
- Scope idempotency keys by customer, source account, and operation type to prevent collisions.

### Demo failure simulation

- Include a configurable, demo-only failure simulator. By default, each operation has an equal 15% chance of failure to demonstrate that service and database state remain durable during failures.
- Both the failure rate and injection points must be configurable, and the simulator must be switchable. Failure may occur before, during, or after a transaction.
- Failures must never leave a partial money movement.
- If a commit succeeds but the response fails, retrying with the same idempotency key returns the completed result.
- Keep the simulator disabled in normal automated tests unless the test explicitly enables it.

## Customer communication and service records

### Account history and notifications

- Track account history.
- Managers can view blocked and deactivated customers, their accounts, and account histories.
- Provide an interface that supports customer- and service-level notifications.
- Trigger notifications for successful or unsuccessful deposit, withdrawal, and transfer operations.

### Observability and auditing

- Provide monitoring tools for metrics, logging, tracing, and auditing.
- Use structured logs with correlation or request IDs.
- Provide metrics for latency, errors, and transaction outcomes.
- Record audits for money movement and manager actions.
- Do not log credentials, JWTs, or unnecessary personal data.
- Observability stacks are optional integrations. The service should emit structured logs, metrics, and traces through standard instrumentation, without requiring Elasticsearch/Kibana, Prometheus/Grafana, or Jaeger to run locally.

## Security requirements

- Serve authenticated API traffic over HTTPS outside local development.
- Enforce server-side authentication, role checks, and customer ownership for every protected endpoint.
- Validate request inputs and reject malformed, invalid, or unauthorized requests without changing state.
- Store configuration secrets, including JWT signing keys and database credentials, outside source code and never expose them in API responses or logs.
- Rate-limit failed authentication attempts to reduce brute-force attacks.

## Performance requirements

- With the failure simulator disabled and under a normal demo load of 50 concurrent requests, standard API endpoints must respond in under one second at the p99 percentile.
- Paginate account-history responses to avoid unbounded API payloads.
- Record latency metrics, including the p99 percentile, for API endpoints.

## Technology stack

- Java 25 LTS
- Spring: Boot, Security, and Test (latest compatible versions)
- Hibernate with QueryDSL (latest compatible version)
- Swagger OpenAPI (latest compatible version)
- PostgreSQL: mandatory primary database
- Redis: mandatory for storing and invalidating JWT tokens
- JUnit (latest compatible version)
- Testcontainers for setting up a test PostgreSQL instance
- Log4j for logging
- Prometheus for monitoring
- OpenTelemetry for tracing

Use the latest compatible versions, and pin all dependency versions.
