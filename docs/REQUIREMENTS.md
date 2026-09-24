# TARBANK

## Overview

Develop a service, in a programming language of your choice, that simulates basic banking operations. The service manages accounts and processes deposits, withdrawals, and transfers between accounts.

This test assesses problem-solving skills through a system that reflects real-world banking constraints. It also evaluates adherence to engineering best practices; incorporate anything relevant to both areas.

## Service delivery

- Provide a REST API.
- In-memory storage is sufficient; no database is required alongside the project, though one may be added at your discretion.

## Authentication and authorization

### Authentication

- Use JWT authentication as the basic security mechanism.
- Authenticate with a username and password; passwords must be hashed and salted.
- JWT expiry must be configurable and default to one hour.
- Support password reset.

### Roles and access

- Support two roles: `manager` and `customer`.
- A customer can view and manage only permitted resources of their own, and can deposit, withdraw, and transfer funds.
- A manager can create and manage customers and accounts, and can block or unblock them.
- APIs that require manager authorization must be accessible only to manager accounts, as customers cannot obtain certain services without a manager's authorization.

## Customer management

- Create a customer.
- Retrieve an existing customer.
- Update an existing customer.
- Soft-delete a customer and their accounts; this cannot be restored.
- Block a customer and their accounts; this can be restored.

## Account management

### Ownership and lifecycle

- A customer can own several accounts at the same time.
- A manager creates an account on behalf of a customer; customers cannot create accounts.
- Create an account with an initial deposit.
- Retrieve an existing account.
- Update an existing account.
- Soft-delete an account; this cannot be restored.
- Block an account; this can be restored.

### Banking operations

- Check an account balance.
- Deposit money into an account.
- Withdraw money from an account without allowing overdrafts.
- Transfer money between accounts.
- Funds cannot be deposited, withdrawn, or transferred to or from a blocked account.

## Money, currencies, and transaction rules

### Money and currencies

- Support different currencies; select an account's currency only during account creation.
- Cross-currency transfers are not supported, but the code must support integrating that feature.
- Use `DECIMAL(19, 4)` value precision.
- Amounts cannot be below zero.

### Transaction integrity and limits

- Transactions must be atomic. Concurrent transfers must not allow an account balance to go below zero.
- Support idempotency to prevent accidental duplicate operations.
- The daily amount that can be withdrawn or transferred from an account must be configurable.
  - Limits are per account.
  - Withdrawal and transfer limits are separate.
  - A customer's timezone defines a day.
  - Failed operations do not count toward limits.
  - Limits must remain durable under concurrent changes and use atomicity and idempotency.

## History, notifications, and observability

- Track account history.
- Provide an interface that supports customer- and service-level notifications.
- Provide monitoring tools for metrics, logging, tracing, and auditing.
  - Use structured logs with correlation or request IDs.
  - Provide metrics for latency, errors, and transaction outcomes.
  - Record audits for money movement and manager actions.
  - Do not log credentials, JWTs, or unnecessary personal data.

## Security and performance

- Take basic security steps to protect transaction information and personal data.
- Respond in less than two seconds.
- Handle 100 million users with minimal downtime.

## Technologies

### Backend tech stack

- Java 25 LTS
- Spring (Boot, Security, Data JPA): latest
- Hibernate (QueryDSL): latest
- Swagger OpenAPI: latest
- PostgreSQL: latest - general-purpose database
- Redis: latest - storing and invalidating JWT tokens
