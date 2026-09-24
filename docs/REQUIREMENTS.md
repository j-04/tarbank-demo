# TARBANK

## Overview

Develop a service, in a programming language of your choice, that simulates basic banking operations. The service manages accounts and processes deposits, withdrawals, and transfers between accounts.

This test assesses problem-solving skills through a system that reflects real-world banking constraints. It also evaluates adherence to engineering best practices; incorporate anything relevant to both areas.

## Service delivery

- Provide a REST API.
- Include a configurable, demo-only failure simulator. By default, each operation has an equal 15% chance of failure to demonstrate that service and database state remain durable during failures. Both the failure rate and injection points must be configurable, and the simulator must be switchable. Failure may occur before, during, or after a transaction.
- Failures must never leave a partial money movement, and that a request failing after a successful commit is recovered through the idempotency key. Keep the simulator disabled in normal automated tests unless the test explicitly enables it.
- Failures must never leave a partial money movement. If a commit succeeds but the response fails, retrying with the same idempotency key returns the completed result.

## Authentication and authorization

### Authentication

- Use JWT authentication as the basic security mechanism.
- Authenticate with a username and password; username must be unique; passwords must be salted and hashed.
- JWT expiry must be configurable and default to one hour.
- Support password resets only through a manager. For this demo, assume a password is set when a customer is created. It can be reset only by visiting an offline branch, where the customer enters a new password through a secure terminal.
- Create a manager account with a randomly generated password that must be stored safely. For this demo, there is no need to support a large number of manager accounts.
- Passwords and tokens must not be logged or stored in plain text.

### Roles and access

- Support two roles: `manager` and `customer`.
- A customer can view and manage only their own permitted resources and can deposit, withdraw, and transfer funds.
- A manager can create and manage customers and accounts and can block or unblock them.
- APIs that require manager authorization must be accessible only to manager accounts because customers cannot obtain certain services without manager authorization.

## Customer management

- Create a customer.
- Retrieve an existing customer.
- Update an existing customer.
- Deactivate a customer and their accounts; this cannot be reverted, and data remains visible to managers.
- Block a customer and their accounts; this can be restored.

## Account management

### Ownership and lifecycle

- A customer can own several accounts at the same time.
- A manager creates an account on behalf of a customer; customers cannot create accounts.
- Create an account.
- Retrieve an existing account.
- Update an existing account: only daily limits can be changed. An account can be blocked or deactivated; other critical information cannot be changed.
- Deactivate an account; this cannot be reverted, and data remains visible to managers.
- Block an account; this can be restored.
- A customer can deposit into or withdraw only from their own account. A customer can transfer money to another customer's account.

### Banking operations

- Check an account balance.
- Deposit money into an account.
- Withdraw money from an account without allowing overdrafts.
- Transfer money between accounts.
- Funds cannot be deposited, withdrawn, or transferred to or from a blocked account.

## Money, currencies, and transaction rules

### Money and currencies

- Accounts are created with a zero balance in the selected currency. Customers deposit money later.
- Support different currencies (USD, EUR, PHP); select an account's currency only during account creation.
- Cross-currency deposits, withdrawals, and transfers are not supported, but the code must support future integration of that feature.
- Use `DECIMAL(19, 4)` value precision.
- Amounts cannot be below zero. Deposits, withdrawals, and transfers of zero are not permitted. There should be some positive amount.
- Deposits are simulated top-ups and may be made only by the account owner.

### Transaction integrity and limits

- Transactions must be atomic. Concurrent transfers and withdrawals must not allow an account balance to go below zero. Accounts must be protected from concurrent modifications through a high level of transaction isolation.
- Support idempotency to prevent accidental duplicate operations. Client sends an idempotency key for money-moving operations and the same key returns the original outcome. Idempotency keys should be scoped by customer, source account, and operation type to prevent collisions.
- The daily amount that can be withdrawn or transferred from an account must be configurable.
  - Default limits are USD 1,000, EUR 1,000, and PHP 70,000. They are applied independently to each limit
  - Customers can update limits without manager approval. An updated limit applies until the end of the current day and resets to the default value at 12:00 a.m. It can be increased up to EUR 3,000, USD 3,000, or PHP 200,000 for the current day.
  - Limits are per account.
  - Withdrawal and transfer limits are separate.
  - There should be a minimum allowed withdrawal amount per currency. Use 5 EUR, 5 USD, and 100 PHP.
  - A customer's timezone defines a day.
  - Failed operations do not count toward limits.
  - Limits must remain durable under concurrent changes and be atomic and idempotent.

## History, notifications, and observability

- Track account history.
  - Managers can view blocked and deactivated customers, their accounts, and account histories.
- Provide an interface that supports customer- and service-level notifications.
  - Notifications are triggered by successful or unsuccessful deposit, withdrawal, and transfer operations.
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

## Technologies

### Backend tech stack

- Java 25 LTS
- Spring (Boot, Security, Data JPA, Test): latest
- Hibernate (QueryDSL): latest
- Swagger OpenAPI: latest
- PostgreSQL: latest - mandatory main database
- Redis: latest - mandatory for storing and invalidating JWT tokens
- JUnit: latest
- Testcontainers: latest - for setting up a test PostgreSQL instance
- Logging: Log4j
- Monitoring: Prometheus
- Tracing: OpenTelemetry

Use the latest compatible versions, but pin all dependency versions.