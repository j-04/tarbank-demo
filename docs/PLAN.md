# Implementation plan

This plan delivers small, runnable slices instead of creating all entities, DTOs, controllers, repositories, services, and validators in separate large phases. Each slice includes its request and response DTOs, input validation, authorization, persistence, business logic, and tests.

Commit after each completed checkpoint. Do not implement deferred notification delivery, payment-network integration, currency conversion, or other stated non-goals.

## 1. Bootstrap the application

This phase creates a reproducible, containerized application shell. It deliberately excludes domain entities, Liquibase schema work, API endpoints, and business rules; those begin in later phases.

### 1.1 Create the build

- [ ] Create a Maven-based Spring Boot project targeting Java 25.
- [ ] Pin the Java, Spring Boot, and plugin versions in the build configuration.
- [ ] Add only the initial dependencies needed to start the service: Spring Web, Validation, Actuator, Security, Data JPA, Data Redis, PostgreSQL, Liquibase, Log4j, and test support.
- [ ] Configure the build to use Log4j rather than the default logging implementation.
- [ ] Add a minimal application class and verify that the project compiles and its unit-test task runs.

### 1.2 Establish the package layout

- [ ] Use one root package, for example com.tarbank, so Spring component scanning is predictable.
- [ ] Create top-level packages for common, security, customer, account, and money.
- [ ] Within each domain package, reserve api, application, domain, and persistence subpackages. Create implementation classes only when their phase needs them.
- [ ] Keep cross-cutting HTTP error handling, correlation-ID handling, configuration, and shared API models in common; do not put banking business logic there.

### 1.3 Define bootstrap configuration

- [ ] Add separate local and test application profiles.
- [ ] Bind database, Redis, JWT, encryption-key, and manager-seed settings through typed configuration properties.
- [ ] Validate required configuration on startup and fail safely with an actionable message when a required setting is absent.
- [ ] Add an .env.example file that names required local settings without containing real secrets.
- [ ] Keep actual passwords, JWT signing keys, and document-encryption keys out of source control.

### 1.4 Add common HTTP behavior

- [ ] Define the standard API error envelope with HTTP status, stable internal code, message, correlation ID, and optional field errors.
- [ ] Add global exception handling for malformed JSON, Bean Validation failures, unsupported methods, and unexpected errors.
- [ ] Add a correlation-ID filter: preserve a valid client-supplied X-Correlation-Id; otherwise generate a UUID.
- [ ] Return the correlation ID in every response and make it available to structured logs.
- [ ] Do not add domain-specific error codes or authorization rules yet; add them with their owning feature.

### 1.5 Containerize the local demo

- [ ] Add a multi-stage Dockerfile that builds the application image with a pinned Java 25 base image.
- [ ] Add a Compose service for the application, PostgreSQL, and Redis.
- [ ] Put the services on an isolated Compose network; expose only the application port for local use.
- [ ] Configure a named PostgreSQL volume so data survives a normal container restart.
- [ ] Configure the application through environment variables or a local, ignored environment file; never bake secrets into the image.
- [ ] Add health checks and service-start ordering appropriate for the application, PostgreSQL, and Redis.

### 1.6 Expose operational health

- [ ] Configure Spring Actuator liveness and readiness endpoints for internal use.
- [ ] Make readiness depend on PostgreSQL and Redis, because both are required by the design.
- [ ] Verify that the application becomes ready only after both dependencies are available.
- [ ] Keep a full metrics, tracing, rate-limiting, and dependency-failure implementation for phase 9.

### Completion checklist

- [ ] mvn test completes successfully from a clean checkout.
- [ ] docker compose config validates the Compose configuration.
- [ ] docker compose build creates the service image.
- [ ] docker compose up starts the service, PostgreSQL, and Redis.
- [ ] The liveness and readiness endpoints report healthy after startup.
- [ ] No real secret is present in tracked files.
- [ ] Commit the completed checkpoint with a message such as bootstrap application and compose runtime.

## 2. Establish database migrations and test infrastructure

This phase makes PostgreSQL schema evolution repeatable before any banking tables are introduced. Liquibase is the schema owner; JPA maps and validates that schema but must never create or alter it.

### 2.1 Configure Liquibase as the schema owner

- [ ] Add the Liquibase master changelog under the application resources and configure Spring Boot to run it at startup.
- [ ] Establish one naming convention for ordered versioned changelogs, for example a version or timestamp followed by a concise purpose.
- [ ] Add the initial changelog baseline. Do not add a no-op changeset merely to create a migration number.
- [ ] Configure every profile to use Liquibase for schema changes.
- [ ] Set Hibernate ddl-auto to validate once entities exist; do not use create, create-drop, or update outside an explicitly isolated local experiment.
- [ ] Treat an applied changeset as immutable. Correct a released schema through a new changeset rather than editing migration history.
- [ ] Reserve migrations for tables, constraints, indexes, database checks, and required reference data. Keep manager startup seeding in application code for phase 3.

### 2.2 Configure PostgreSQL connections

- [ ] Add typed datasource settings for the local Compose database and test database.
- [ ] Use PostgreSQL as the only supported runtime and integration-test database; do not introduce H2 or another database with different locking and numeric behavior.
- [ ] Pin the PostgreSQL container major version and use the same version for Docker Compose and Testcontainers.
- [ ] Make the application wait for a reachable database through normal connection and readiness behavior, not by relying only on Compose startup order.
- [ ] Confirm that the direct local profile and the Compose profile use environment-specific connection settings without duplicating application behavior.

### 2.3 Build the Testcontainers integration-test base

- [ ] Add a PostgreSQL Testcontainers dependency and a reusable test container configuration.
- [ ] Start the container before the Spring test application context and supply its JDBC connection properties dynamically.
- [ ] Ensure each integration-test run starts from a fresh database and applies the same Liquibase changelogs used in the demo runtime.
- [ ] Keep container image versions pinned and separate integration tests from fast unit tests.
- [ ] Add only generic test utilities in this phase, such as a clock or JSON helper. Add authenticated-request and seeded-user helpers with the identity feature in phase 3.

### 2.4 Verify migration behavior

- [ ] Add an integration test that starts the application against a fresh Testcontainers PostgreSQL instance.
- [ ] Assert that Liquibase completes successfully and records its applied changelogs.
- [ ] Verify that a second application startup against the same database does not reapply or alter completed migrations.
- [ ] Verify that an invalid or missing changelog fails startup clearly rather than allowing Hibernate to create a replacement schema.
- [ ] Confirm that the empty baseline contains no accidental banking tables; users, customers, accounts, and transaction tables arrive with their owning feature phases.

### Completion checklist

- [ ] The project starts against PostgreSQL with Liquibase enabled.
- [ ] No profile can automatically create or update the production-like schema through Hibernate.
- [ ] The Testcontainers integration test creates a fresh PostgreSQL database and applies the full migration set.
- [ ] Re-running the application against the same database is safe and does not change applied migrations.
- [ ] No H2-specific configuration or test assumptions are present.
- [ ] Commit the completed checkpoint with a message such as establish Liquibase migration baseline.

## 3. Implement identity, authentication, and customer creation

- Add migrations, entities, repositories, and role checks for users, managers, and customers.
- Seed manager accounts idempotently from deployment configuration.
- Implement BCrypt password storage, JWT login/logout, JWT invalidation in Redis, and current-user status checks.
- Implement manager customer creation and customer retrieval, including DTOs, input validation, document encryption, HMAC document lookup, and audit records.

**Checkpoint:** a seeded manager can log in, create an eligible customer, and retrieve that customer; customers cannot call manager endpoints.

## 4. Complete customer lifecycle management

- Add customer update, block, and deactivate operations.
- Enforce the 18-or-older rule, document-expiry validation, username and password rules, and immutable timezone policy.
- Record every manager lifecycle action in the audit trail.
- Ensure blocked or deactivated users cannot continue to use an already-issued token.

**Checkpoint:** lifecycle changes are authorized, audited, and enforced immediately on protected requests.

## 5. Implement account lifecycle management

- Add the accounts migration, entity, repository, account-number generator, and EUR/USD constraints.
- Implement manager account creation, account retrieval, status changes, daily-limit updates, ETags, and audits.
- Enforce customer-status cascades to accounts and customer ownership for customer-visible account endpoints.

**Checkpoint:** a manager can create and manage zero-balance accounts; a customer can access only their own accounts.

## 6. Implement the money-operation core

- Add migrations, entities, and repositories for transactions, immutable transaction entries, financial idempotency records, and audit events.
- Implement deposit, withdrawal, and same-currency transfer as complete vertical slices: controller, DTOs, input validation, authorization, service orchestration, and persistence.
- Use one database transaction per operation, deterministic ascending account-lock ordering, and locked balance checks.
- Reserve and finalize idempotency records within the same transaction as the side effect.

**Checkpoint:** balances cannot become negative, duplicate requests replay their original result, and a completed transfer has exactly two offsetting entries.

## 7. Add concurrency and failure tests before extending money features

- Test concurrent withdrawals from one account, concurrent transfers, and opposite-direction transfers.
- Test insufficient funds, blocked accounts, owner checks, same-key concurrent requests, and key reuse with different input.
- Add balance-reconciliation tests that compare every stored account balance with the sum of its transaction-entry deltas.

**Checkpoint:** concurrency, idempotency, and reconciliation tests pass reliably against PostgreSQL.

## 8. Implement daily limits and account history

- Add daily-limit usage and temporary-override migrations, entities, and repositories.
- Implement timezone-aware daily-limit evaluation with locked usage-row creation and updates.
- Add limit endpoints with ETags and generic API idempotency records.
- Implement newest-first cursor pagination for account history using (account_id, created_at DESC, id DESC).

**Checkpoint:** concurrent money operations cannot exceed a limit, and history pagination is stable when new entries arrive.

## 9. Add demo resilience, protection, and observability

- Add the disabled-by-default configurable failure simulator, including pre-commit and post-commit response-failure injection points.
- Add scheduled cleanup for expired limit overrides and completed idempotency records.
- Add Redis token-bucket rate limiting, including stricter authentication and money-operation rules.
- Add structured logging, Micrometer metrics, OpenTelemetry tracing, and dependency-aware readiness behavior.

**Checkpoint:** injected failures leave no partial movement; post-commit retries replay the result; dependency failures do not weaken security controls.

## 10. Prepare the demo for review

- Complete OpenAPI descriptions, examples, and response-error contracts.
- Run full integration tests through Testcontainers and the application through Docker Compose.
- Run and document the reproducible 50-concurrent-request load test with the failure simulator disabled.
- Verify that logs and API responses never expose passwords, JWTs, document values, lookup hashes, or encryption keys.

**Checkpoint:** a reviewer can configure secrets, start the stack, run migrations and tests, and demonstrate the main flows through Swagger or Postman.
