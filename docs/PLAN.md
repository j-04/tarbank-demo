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

This is the first protected vertical slice. It creates the identity schema, starts configured manager accounts, authenticates users, and lets an active manager onboard and retrieve an adult customer. It does not yet implement customer updates, status transitions, accounts, or money movement.

### 3.1 Create the identity and onboarding schema

- [ ] Add Liquibase changesets for users, managers, customers, audit events, and generic API-request idempotency records.
- [ ] Add the roles, lifecycle-status values, foreign keys, unique username constraint, customer-to-manager relationship, customer version, and required timestamps from the design.
- [ ] Add the unique identity-document constraint using document type, issuing country, and document lookup hash; never store a plaintext document number.
- [ ] Add database constraints for fixed-format fields such as country codes and bounded text fields where appropriate, while keeping age, document-validity, password-format, and cross-profile rules in service validation.
- [ ] Add the indexes needed now for login by username, customer retrieval, manager relationships, and idempotency lookup.
- [ ] Create separate User, Manager, and Customer entities. Keep Manager and Customer as one-to-one role-specific profiles rather than a nullable-field mega-entity.
- [ ] Model role and lifecycle status as explicit enums; do not accept arbitrary database strings in API or business logic.
- [ ] Validate in the service that a user has exactly one profile matching its role, because this cross-table invariant cannot be expressed fully by a normal foreign key.

### 3.2 Implement sensitive customer-data handling

- [ ] Normalize identity-document numbers consistently before calculating their lookup value.
- [ ] Encrypt each document number with application-level authenticated encryption and a configured key version before persistence.
- [ ] Calculate the HMAC-SHA-256 lookup hash with a separate configured secret and use only that hash for duplicate detection.
- [ ] Bind encryption and HMAC keys through validated configuration properties; fail startup if a required key is absent or malformed.
- [ ] Ensure entities, DTOs, exceptions, structured logs, and audit metadata never expose document numbers, ciphertext, lookup hashes, or encryption keys.
- [ ] Test that the persisted ciphertext is not plaintext and that the same normalized document is detected as a duplicate despite randomized encryption.

### 3.3 Seed manager principals safely

- [ ] Define configuration for one or more startup managers, including username, initial password, and display names.
- [ ] Run manager seeding only after Liquibase has completed.
- [ ] For each configured manager, create the User and Manager profile in one transaction when they do not already exist.
- [ ] Make seeding idempotent: an existing matching manager is retained and is not silently overwritten on every restart.
- [ ] BCrypt-hash manager passwords before persistence and never log configured credentials or hashes.
- [ ] Do not add a REST endpoint for manager creation, reassignment, portfolio management, or manager customer-account access boundaries.

### 3.4 Implement stateless JWT authentication

- [ ] Configure Spring Security as stateless and deny protected endpoints by default.
- [ ] Expose only login, health endpoints, and the intended documentation endpoint without a bearer token; keep all customer APIs manager-protected for this phase.
- [ ] Implement login with normalized username and password verification. Issue a signed JWT only for an ACTIVE user.
- [ ] Include a stable subject, role, unique jti, and configured expiration in the JWT. Default the expiration to one hour.
- [ ] Implement bearer-token authentication that verifies signature and expiry, checks the jti invalidation entry in Redis, and loads the current user status.
- [ ] Reject blocked or deactivated users before business logic. Create a principal that carries only the identifiers and role needed for authorization.
- [ ] Implement authenticated logout by storing the JWT jti in Redis with a TTL no longer than the token's remaining lifetime.
- [ ] Return the agreed login and logout error codes without revealing whether an invalid login failed because of an unknown username, wrong password, or inactive user.
- [ ] Defer Redis rate limiting to phase 9, but keep the authentication flow structured so a boundary limiter can be added without changing login business logic.

### 3.5 Implement manager-led customer onboarding

- [ ] Define request and response DTOs for POST /api/v1/customers and GET /api/v1/customers/{customerId}.
- [ ] Require a manager bearer token and a UUID v4 Idempotency-Key for customer creation.
- [ ] Validate manager-assigned username format, exactly 12 printable password characters, and that the password differs from the username.
- [ ] Validate required names, age of at least 18 at creation, optional non-expired document expiry date, E.164 phone number, ISO residence and issuing countries, address fields, and a valid IANA timezone.
- [ ] In one database transaction, reserve the generic idempotency record, create the User and Customer profile, assign the acting manager as manager_id, write the CUSTOMER_CREATED audit event, and finalize the saved response.
- [ ] Replay a completed identical creation request from its saved idempotency response. Reject reuse of the same key with different normalized input and handle a concurrent in-progress key according to the design.
- [ ] Return only the documented safe customer fields. Never return the supplied password, password hash, document number, encrypted document, or lookup hash.
- [ ] Implement manager retrieval of one customer using the safe customer response contract. Defer customer listing, profile updates, and lifecycle transitions to phase 4.

### 3.6 Test the protected vertical slice

- [ ] Add integration tests for startup manager seeding and safe repeated application startup.
- [ ] Test successful login, invalid credentials, expired or invalidated JWTs, and rejection of blocked or deactivated principals where applicable.
- [ ] Test that a manager can create and retrieve a customer and that a customer cannot use manager endpoints.
- [ ] Test validation failures for underage customers, duplicate username, duplicate normalized identity document, invalid phone number, document expiry, password, and timezone.
- [ ] Test customer-creation idempotency: completed replay, conflicting reuse, and concurrent in-progress behavior.
- [ ] Assert that API responses and captured logs omit credentials and identity-document secrets.
- [ ] Test that onboarding creates the customer and its audit event atomically.

### Completion checklist

- [ ] Liquibase creates the identity, audit, and generic idempotency schema on a fresh PostgreSQL database.
- [ ] Configured manager accounts are seeded once, stored with BCrypt password hashes, and can log in.
- [ ] Login returns a valid JWT for an ACTIVE user; logout invalidates that token until its natural expiration.
- [ ] An active manager can create and retrieve an eligible customer through the documented API contract.
- [ ] Customer responses and logs contain no passwords or identity-document secrets.
- [ ] Customer creation is atomic, audited, and safely idempotent.
- [ ] Commit the completed checkpoint with a message such as implement identity and customer onboarding.

## 4. Complete customer lifecycle management

This phase completes the manager-controlled lifecycle after onboarding: safe profile changes, password resets through the branch flow, and customer status changes. To make the required status cascade atomic, it brings forward the account persistence baseline only; public account management remains phase 5.

### 4.1 Add the account persistence dependency for lifecycle cascades

- [ ] Add the accounts Liquibase changeset with the account owner, currency, balance, lifecycle status, status-change metadata, management version, and timestamps defined in the design.
- [ ] Add the Account entity and repository methods needed to load a customer's non-deactivated accounts for a status change.
- [ ] Add a repository method that locks the affected account rows before their statuses are changed.
- [ ] Do not expose account creation, account detail, daily-limit, or account-status endpoints in this phase. Those remain phase 5.
- [ ] Update phase 5 to build its public account lifecycle on this persistence baseline rather than creating a second account schema.

### 4.2 Implement manager customer listing and safe reads

- [ ] Add manager-only GET /api/v1/customers with bounded cursor pagination and an optional lifecycle-status filter.
- [ ] Extend GET /api/v1/customers/{customerId} to return the current customer ETag in the customer-vN form.
- [ ] Keep customer lists and detail responses limited to safe fields; never disclose document numbers, lookup hashes, ciphertext, passwords, or password hashes.
- [ ] Keep manager authorization global as specified in the design: every ACTIVE manager may access every customer, without a portfolio restriction.
- [ ] Return the documented validation, authentication, authorization, and not-found error contracts.

### 4.3 Implement mutable profile updates

- [ ] Define the PATCH /api/v1/customers/{customerId} request DTO for the documented mutable profile fields: names, email, phone number, and current residential address.
- [ ] Reject changes to username, date of birth, timezone, identity-document data, manager relationship, role, and lifecycle status through this endpoint.
- [ ] Require an If-Match customer-vN header. Reject an absent header with 428 PRECONDITION_REQUIRED and a stale header with 412 PRECONDITION_FAILED.
- [ ] Load and update the customer in one transaction, increment the customer version exactly once, and return the next ETag.
- [ ] Reapply contact, address, and format validation to partial updates. Keep the field-error response safe and specific enough for a caller to correct input.
- [ ] Write a CUSTOMER_UPDATED audit event with the manager, customer, correlation ID, and safe change metadata. Do not store sensitive old or new values in audit metadata.

### 4.4 Implement customer status transitions and account cascades

- [ ] Define the PATCH /api/v1/customers/{customerId}/status DTO and allow only ACTIVE to BLOCKED, BLOCKED to ACTIVE, and ACTIVE or BLOCKED to DEACTIVATED.
- [ ] Reject every other transition, including any reactivation of a DEACTIVATED customer, with 409 INVALID_STATUS_TRANSITION.
- [ ] Require If-Match customer-vN and apply the transition in one transaction.
- [ ] For a block operation, lock and block every non-deactivated account owned by the customer.
- [ ] For an unblock operation, restore only the customer to ACTIVE. Do not automatically unblock any account.
- [ ] For deactivation, lock and permanently deactivate every non-deactivated account. Never permit reversal.
- [ ] Increment the customer version and every changed account management version. Record the acting manager and timestamp on each changed customer and account.
- [ ] Write one CUSTOMER_STATUS_CHANGED audit event and one ACCOUNT_STATUS_CHANGED audit event for each affected account, with old and new statuses and the same correlation ID.
- [ ] Use deterministic locking of the customer and affected account rows so this workflow remains safe when account-management endpoints arrive in phase 5.

### 4.5 Implement the manager-assisted password-reset flow

- [ ] Implement password reset as a manager-authorized branch-terminal operation, not a customer self-service or unauthenticated reset route.
- [ ] Validate the replacement password using the same exactly-12-printable-character rule and username comparison used during onboarding.
- [ ] BCrypt-hash the replacement password before persistence and ensure neither its value nor hash reaches a response, log, audit metadata, or exception.
- [ ] Record a CUSTOMER_PASSWORD_RESET audit event with the acting manager and correlation ID, without sensitive metadata.
- [ ] Add the restricted terminal/API contract to the API documentation before exposing the flow in the demo.

### 4.6 Enforce lifecycle status in active sessions

- [ ] Reuse the phase-3 per-request user-status lookup so a token issued before a customer is blocked or deactivated is rejected on its next protected request.
- [ ] Do not attempt to cancel a request that was already authenticated and began its database transaction before the status change; it follows the established request-entry authentication policy.
- [ ] Do not add a separate token-version scheme or bulk Redis invalidation for lifecycle changes; the current status check is the chosen enforcement mechanism.

### 4.7 Test customer lifecycle behavior

- [ ] Test customer listing, cursor and status-filter validation, safe read responses, and manager-only access.
- [ ] Test successful profile updates, immutable-field rejection, missing ETag, stale ETag, and concurrent updates.
- [ ] Test every allowed and forbidden customer-status transition.
- [ ] Create accounts through repository fixtures and verify blocking and deactivation cascade atomically, while unblocking leaves account statuses unchanged.
- [ ] Test that an already-issued customer token is denied on its next protected request after blocking or deactivation.
- [ ] Test the manager-assisted password reset flow, including validation, BCrypt storage, authorization, and audit logging.
- [ ] Test that customer, account, and audit changes roll back together if the transaction fails.

### Completion checklist

- [ ] Managers can list, retrieve, and safely update customers using the documented ETag contract.
- [ ] Customer status transitions are authorized, optimistic-concurrency-safe, audited, and follow the allowed state machine.
- [ ] Blocking and deactivation cascade to eligible accounts; unblocking does not restore accounts automatically.
- [ ] A blocked or deactivated customer cannot use a previously issued token on a new protected request.
- [ ] Password resets are manager-assisted, securely stored, and not public or self-service.
- [ ] Commit the completed checkpoint with a message such as implement customer lifecycle management.

## 5. Implement account lifecycle management

This phase exposes the account lifecycle built on the persistence baseline introduced in phase 4. It creates zero-balance EUR and USD accounts, provides safe manager and owner reads, and completes account-level status management. Daily-limit overrides and money operations remain later phases.

### 5.1 Complete the account model and account-number generation

- [ ] Complete the account migration with the unique immutable public account number, EUR/USD currency constraint, numeric(19,4) zero-balance default, non-negative balance check, and required manager and status metadata.
- [ ] Add a database check that enforces the public account-number shape: TB followed by exactly 14 digits.
- [ ] Use a database-backed sequence to reserve the numeric portion of an account number and left-pad it to 14 digits. Sequence gaps after a rolled-back creation are acceptable; duplicate numbers are not.
- [ ] Keep the public account number immutable and separate from the internal account ID.
- [ ] Model currency and account status as explicit enums. Never accept a client-provided balance, account number, lifecycle metadata, or management version.
- [ ] Keep balance updates out of the account management-version mechanism; money operations will lock and update balances in phase 6.

### 5.2 Implement manager account creation

- [ ] Define the POST /api/v1/customers/{customerId}/accounts request and safe response DTOs.
- [ ] Require an ACTIVE manager, a UUID v4 Idempotency-Key, and a target customer that is ACTIVE.
- [ ] Accept only EUR or USD and create the account with balance 0.0000 and ACTIVE status.
- [ ] In one database transaction, reserve the generic idempotency record, generate and persist the account, record the creating manager, write an ACCOUNT_CREATED audit event, and finalize the replay response.
- [ ] Scope account-creation idempotency to the manager, customer, and account-creation operation. Replay an identical completed request without creating another account; reject conflicting reuse or a concurrent in-progress request as defined by the generic idempotency policy.
- [ ] Do not expose customer account creation, account-number choice, currency changes, or starting-balance input.

### 5.3 Implement account lists and safe detail reads

- [ ] Add manager-only GET /api/v1/customers/{customerId}/accounts with bounded cursor pagination and the safe account-summary contract.
- [ ] Add customer-only GET /api/v1/accounts that lists every account owned by the authenticated customer, including its lifecycle status.
- [ ] Use a stable account-list ordering and opaque cursor based on the last returned account position.
- [ ] Add GET /api/v1/accounts/{accountNumber} for the account-owning customer or any ACTIVE manager.
- [ ] Enforce customer ownership in the service, not only in the controller. A customer must not read another customer's account.
- [ ] Return the current balance, currency, status, and account ETag in the account-vN form. Do not return internal account IDs or manager-only audit metadata.
- [ ] Return configured default withdrawal and transfer limits in the account detail response with null expiresAt values until phase 8 adds effective temporary overrides.
- [ ] Defer account-history reads until transaction entries exist in phase 8.

### 5.4 Implement account status management

- [ ] Define PATCH /api/v1/accounts/{accountNumber}/status as a manager-only operation that requires If-Match: account-vN.
- [ ] Allow only ACTIVE to BLOCKED, BLOCKED to ACTIVE, and ACTIVE or BLOCKED to DEACTIVATED.
- [ ] Reject every other transition, including DEACTIVATED reactivation, with 409 INVALID_STATUS_TRANSITION.
- [ ] Permit an account unblock only when its customer is ACTIVE. A manager must explicitly unblock each eligible account after its customer is restored.
- [ ] Use one transaction to verify the account and customer state, apply a valid transition, increment management_version once, and record the manager and timestamp.
- [ ] Write an ACCOUNT_STATUS_CHANGED audit event with old and new status, acting manager, target account, and correlation ID.
- [ ] Keep customer-level cascade behavior in phase 4 as the single owner of a customer-driven account status change; do not duplicate it in the account controller.

### 5.5 Test the account lifecycle slice

- [ ] Test account-number format, uniqueness, immutability, and concurrent account creation.
- [ ] Test supported currencies, zero balance, and rejection of unsupported currency or client-provided balance data.
- [ ] Test manager creation authorization, rejection for blocked or deactivated customers, and customer inability to create an account.
- [ ] Test account-creation idempotency for completed replay, conflicting reuse, and concurrent matching requests.
- [ ] Test manager and owner account lists, detail ownership checks, pagination validation, and safe response fields.
- [ ] Test account ETag generation, missing and stale If-Match behavior, every valid and invalid account transition, and the active-customer rule for unblocking.
- [ ] Test that account status changes and audit events commit or roll back together.
- [ ] Test that account detail reports configured default daily limits without creating an override record.

### Completion checklist

- [ ] A manager can create a zero-balance EUR or USD account for an ACTIVE customer through the documented idempotent API.
- [ ] Account numbers are unique, immutable, and match the TB plus 14-digit format.
- [ ] Managers can read any account; customers can read only their own accounts.
- [ ] Account status transitions use ETags, are audited, and cannot reactivate a deactivated account.
- [ ] Customer status rules and account status rules work together without automatic account restoration on customer unblock.
- [ ] No money movement or daily-limit override behavior is implemented in this phase.
- [ ] Commit the completed checkpoint with a message such as implement account lifecycle management.

## 6. Implement the money-operation core

This is the highest-risk implementation phase. It delivers correctly persisted simulated deposits, withdrawals, and same-currency transfers before adding the demo failure simulator, temporary daily-limit overrides, account-history endpoints, or rate limiting.

### 6.1 Add money-operation persistence

- [ ] Add Liquibase changesets for transactions, immutable transaction entries, dedicated money-operation idempotency records, and daily-limit usage.
- [ ] Reuse the audit-event table introduced in phase 3; do not create a second audit mechanism for money operations.
- [ ] Model transaction type and final status as explicit enums. Generate a UUID transaction ID in the application.
- [ ] Add transaction constraints for positive numeric(19,4) amount, supported currency, source and destination requirements by operation type, and a nullable failure code only when the transaction failed.
- [ ] Add transaction-entry constraints for non-zero signed deltas, non-negative balance_after, one entry per account per transaction, and the account-history index defined in the design.
- [ ] Add the unique money idempotency scope of customer, source or deposit-target account, operation type, and UUID v4 key.
- [ ] Add the daily-limit usage unique key of account, operation type, and customer-local date, plus the non-negative used amount constraint.
- [ ] Treat transaction entries as append-only in application code. No update or delete repository operation may be exposed for transactions, entries, or audit events.

### 6.2 Define money API contracts and authorization

- [ ] Define amount request DTOs for deposits and withdrawals, and a destination-account-number plus amount DTO for transfers.
- [ ] Accept decimal amounts with no more than four fractional digits, reject zero and negative values, and do not accept a client-supplied currency.
- [ ] Require a bearer token and UUID v4 Idempotency-Key for every money-operation endpoint.
- [ ] Authorize only the account-owning customer to deposit, withdraw, or transfer from the source account. Managers cannot use customer money-operation endpoints.
- [ ] Treat a deposit as an owner-only simulated top-up with a notional external source; no source account is required or created.
- [ ] Treat a withdrawal as an owner-only simulated withdrawal with a notional external recipient; no destination account is required or created.
- [ ] Require transfer source and destination accounts to differ, be ACTIVE, and use the same currency. The source account currency determines the operation currency.
- [ ] Keep controllers limited to DTO handling and delegation. Put ownership, state, balance, currency, and limit rules in the application service under the database locks.

### 6.3 Implement dedicated financial idempotency

- [ ] Build a canonical request hash from the normalized money input, including operation type, source or deposit target, destination when present, and amount.
- [ ] On the first request, create an IN_PROGRESS money idempotency record inside the same database transaction as the operation.
- [ ] On an identical completed or failed retry, return the retained HTTP status and response data while rebuilding the outer response envelope with the retry correlation ID.
- [ ] Reject a same-scope key with a different normalized request as 409 IDEMPOTENCY_CONFLICT.
- [ ] When matching callers overlap, wait for the first transaction and replay its final record. Respect the configured database lock timeout and return 409 REQUEST_IN_PROGRESS if it is reached first.
- [ ] Commit the final idempotency record before attempting to send the HTTP response. A process failure before commit leaves neither an operation nor a durable IN_PROGRESS record.
- [ ] Keep generic API-request idempotency separate from this financial table and service.

### 6.4 Make money concurrency explicit

- [ ] Use PostgreSQL READ COMMITTED isolation with explicit pessimistic row locks; do not use SERIALIZABLE as the default strategy.
- [ ] In one transaction, claim or check idempotency, lock the account rows, lock or atomically create the source daily-usage row when needed, validate under those locks, then write every durable outcome.
- [ ] For a transfer, lock source and destination account rows in ascending internal account-ID order, never source-then-destination order.
- [ ] For a withdrawal or transfer, atomically create or lock the matching daily-usage row through an upsert so concurrent first operations cannot create separate usage rows.
- [ ] Perform balance and remaining-limit checks only after the relevant account and usage locks are held.
- [ ] Do not use the account management version or ETag as a money-operation concurrency control. Money operations use database locks and dedicated idempotency instead.

### 6.5 Apply money and daily-limit rules

- [ ] Use the default withdrawal and transfer limit of 1,000.0000 in the account currency until phase 8 adds same-day overrides.
- [ ] Calculate the source customer's current local date from the immutable IANA timezone before locating daily usage.
- [ ] Require withdrawals to be at least 5.0000 and no greater than available balance or remaining withdrawal limit.
- [ ] Require transfers to be positive, no greater than available balance or remaining transfer limit, and same-currency.
- [ ] Do not consume daily-limit usage for deposits.
- [ ] Reject blocked or deactivated accounts before any money state is changed.
- [ ] Map business rejections to the documented stable codes, including ACCOUNT_NOT_ACTIVE, MINIMUM_WITHDRAWAL_AMOUNT, INSUFFICIENT_FUNDS, DAILY_LIMIT_EXCEEDED, and CURRENCY_MISMATCH.

### 6.6 Persist successful and failed outcomes atomically

- [ ] For a completed deposit, write one positive destination entry, update the account balance, create a COMPLETED transaction, write a safe audit event, and finalize idempotency in one transaction.
- [ ] For a completed withdrawal, write one negative source entry, update the balance and daily usage, create a COMPLETED transaction, write an audit event, and finalize idempotency in one transaction.
- [ ] For a completed transfer, write exactly two entries with equal absolute amount and opposite signs, update both balances, update source transfer usage, create a COMPLETED transaction, write an audit event, and finalize idempotency in one transaction.
- [ ] Store balance_after on each entry using the balance projection written by the same transaction.
- [ ] For an authenticated, well-formed request rejected by a business rule, persist a FAILED transaction, failure code, audit event, and failed idempotency outcome, but no entry, balance change, or daily-usage change.
- [ ] Do not create a transaction for malformed, unauthenticated, or unauthorized requests that do not reach a valid money-operation attempt.
- [ ] Send the final result to the internal NotificationService port only after the database transaction commits. Use the no-op or structured-log adapter; do not add durable notification delivery.

### 6.7 Leave controlled extension points for later phases

- [ ] Keep the money application service structured so a FailureSimulator port can inject before-transaction, before-commit, and after-commit response failures in phase 9.
- [ ] Keep the effective-limit lookup behind a small service so phase 8 can add temporary overrides without changing the locking algorithm.
- [ ] Do not expose transaction-history endpoints until phase 8 adds cursor pagination over the immutable entries.
- [ ] Do not add rate-limiting behavior here; phase 9 adds it at the request boundary without changing money business logic.

### 6.8 Verify the core slice before broader concurrency testing

- [ ] Add integration tests for successful deposit, withdrawal, and transfer, including exact transaction, entry, balance, usage, audit, and idempotency records.
- [ ] Test every operation against an inactive account, and test owner checks, self-transfer rejection, and cross-currency transfer rejection.
- [ ] Test insufficient funds, minimum withdrawal amount, positive amount validation, and default daily-limit enforcement.
- [ ] Test completed and failed idempotency replay, conflicting-key reuse, and no transaction entries or usage for a failed business operation.
- [ ] Test the entry invariants: one deposit or withdrawal entry, two balanced transfer entries, and balance_after matching the stored balance.
- [ ] Defer the high-contention and failure-simulator matrix to phases 7 and 9.

### Completion checklist

- [ ] Customers can perform idempotent simulated deposits, withdrawals, and same-currency transfers through the documented API.
- [ ] Every completed operation atomically writes its transaction, entries, balances, applicable daily usage, audit event, and final idempotency response.
- [ ] Every failed business operation retains a FAILED transaction and audit record without entries, balance movement, or daily-limit usage.
- [ ] Explicit database locks prevent the basic balance and daily-limit races; phase 7 proves this under contention.
- [ ] The money service exposes no customer-visible notification, history, temporary-override, or rate-limiting feature yet.
- [ ] Commit the completed checkpoint with a message such as implement atomic money operations.

## 7. Prove concurrency, idempotency, and persistence safety

This phase verifies the money-operation core against real PostgreSQL behavior. It uses deterministic integration tests rather than mocks or H2, because row locking, isolation, upserts, and constraint timing are the behaviors under test. The configurable demo failure simulator itself remains phase 9.

### 7.1 Build deterministic concurrent-test support

- [ ] Run concurrency tests against the PostgreSQL Testcontainers setup from phase 2, not against an in-memory database.
- [ ] Create active customers and accounts through fixtures with explicit balances, currencies, and timezones.
- [ ] Give every concurrent task its own application transaction and persistence context. Do not wrap the whole test in one transaction.
- [ ] Coordinate competing requests with latches or barriers so they reach the intended lock point together.
- [ ] Set a bounded database lock timeout and test-executor timeout so a deadlock or blocked test fails clearly instead of hanging the build.
- [ ] Inject a fixed clock and known customer-local timezone when a test depends on the daily usage date.
- [ ] Query PostgreSQL after all tasks finish; assert durable rows and balances rather than only service return values.

### 7.2 Prove balance and account-lock correctness

- [ ] Run two withdrawals from the same account whose combined amount exceeds its balance. Assert that at most one completes and the final balance never becomes negative.
- [ ] Run a withdrawal and a transfer from the same source account whose combined amount exceeds its balance. Assert that locking serializes the balance check and permits only valid outcomes.
- [ ] Run concurrent deposits into the same account and assert that all successful deltas appear once and the stored balance equals their sum.
- [ ] Run two transfers in opposite directions between the same accounts. Assert that both complete when funds allow and that deterministic ascending account-ID locking prevents a deadlock.
- [ ] Run concurrent transfers sharing a destination account and verify each successful transfer writes a correct destination balance_after value.
- [ ] Exercise a money operation racing with an account status change. Assert that the row locks yield one serial order and no completed movement uses an inactive account state.

### 7.3 Prove daily-usage concurrency

- [ ] Start concurrent first withdrawals or transfers for the same account, operation type, and local date when no daily-usage row exists.
- [ ] Assert that exactly one usage row is created and that its used amount equals the sum of successful operations.
- [ ] Run competing operations whose combined amount exceeds the default limit. Assert that no combination of successful requests exceeds the configured limit.
- [ ] Verify withdrawal and transfer usage are separate aggregates and do not consume one another's limits.
- [ ] Verify a deposit neither creates nor changes a daily-usage row.
- [ ] Repeat the daily-limit cases across a customer-local midnight boundary using the fixed clock to prove the date key, not server-local time, controls usage.

### 7.4 Prove financial idempotency under races and retries

- [ ] Submit the same money request concurrently with the same idempotency key. Assert that exactly one transaction and its entries are written and that every caller receives the same final result or the documented in-progress response.
- [ ] Submit the same key with different normalized input. Assert that the request is rejected with IDEMPOTENCY_CONFLICT and does not alter the original operation.
- [ ] Retry a completed successful operation after its account balance and version have changed. Assert that the stored result is replayed rather than applying another movement.
- [ ] Retry a failed business operation with the same key. Assert that its recorded failure is replayed and that no entries or usage are created.
- [ ] Verify that a failed transaction rollback removes an uncommitted idempotency reservation so a later valid first attempt can proceed.
- [ ] Defer the explicit post-commit response-loss simulation to phase 9, where the FailureSimulator supplies a controlled injection point.

### 7.5 Prove transaction and ledger invariants

- [ ] Assert that every completed deposit has one positive entry and every completed withdrawal has one negative entry with the correct balance_after.
- [ ] Assert that every completed transfer has exactly two entries, equal absolute deltas, opposite signs, and a zero sum.
- [ ] Assert that every failed business transaction has a failure code, audit event, and final idempotency outcome, but no entry, balance change, or daily-usage update.
- [ ] Assert that malformed, unauthenticated, and unauthorized requests leave no transaction record.
- [ ] Run a reconciliation query independent of application balance-maintenance code: for every account, balance equals COALESCE(SUM(transaction_entries.amount_delta), 0).
- [ ] Verify transaction entries and audit events are never modified or deleted by application repositories or services.

### 7.6 Test rollback behavior without the demo simulator

- [ ] Use a controlled test-only exception inside the money-operation transaction after records have been staged but before commit.
- [ ] Assert that the database rolls back transaction, entries, balances, daily usage, audit event, and idempotency record together.
- [ ] Verify that a later retry with the same key can proceed after that rollback.
- [ ] Keep this test seam test-only. The configurable before-transaction, before-commit, and after-commit simulator behavior belongs to phase 9.

### Completion checklist

- [ ] The concurrent tests are repeatable against PostgreSQL and have bounded execution time.
- [ ] No test demonstrates a negative balance, limit overrun, duplicate usage row, duplicate transaction, or opposite-direction transfer deadlock.
- [ ] Financial idempotency returns exactly one durable outcome for a matching request key.
- [ ] Reconciliation proves every stored account balance matches its immutable entry history after successful, failed, and concurrent operations.
- [ ] Transaction rollback leaves no partial money state.
- [ ] Commit the completed checkpoint with a message such as verify money-operation concurrency.

## 8. Complete daily-limit configuration and account history

This phase adds same-day limit increases on top of the default-limit and daily-usage foundation from phase 6. It also exposes account history from immutable transaction entries without changing money-operation locking or balances.

### 8.1 Add temporary limit overrides

- [ ] Add the account-limit-overrides Liquibase changeset with account, operation type, limit amount, customer-local effective date, expiry instant, updating user, timestamps, and the unique account-operation-date key.
- [ ] Add an expires_at index for future cleanup and retain the daily-usage date index from phase 6.
- [ ] Model override operation type as WITHDRAWAL or TRANSFER only. Do not allow a deposit limit override.
- [ ] Keep the override table separate from daily usage: an override defines the current-day allowance, while daily usage records consumed amount.
- [ ] Do not add a midnight reset job. An override becomes ineffective when its effective date is no longer the customer's current local date or its expires_at instant has passed.

### 8.2 Implement effective-limit evaluation

- [ ] Centralize effective-limit calculation in the limit service used by account detail, limit updates, and money operations.
- [ ] Convert the injected current instant to the account owner's immutable IANA timezone and derive the customer-local date.
- [ ] Use a current, unexpired override for that account, operation type, and local date when one exists; otherwise return the configured 1,000.0000 default in the account currency.
- [ ] Calculate expires_at as the next midnight in the customer timezone, using timezone-aware date-time conversion so daylight-saving changes are handled correctly.
- [ ] Keep expiry evaluation on the request path. Delayed scheduled cleanup must not leave an expired override effective.
- [ ] Extend account detail to return the current effective withdrawal and transfer limits and null expiresAt when a configured default applies.

### 8.3 Implement daily-limit updates

- [ ] Define PATCH /api/v1/accounts/{accountNumber}/daily-limits for the account-owning customer or any ACTIVE manager.
- [ ] Require both Idempotency-Key UUID v4 and If-Match account-vN. Perform idempotency replay lookup before the ETag check.
- [ ] Require at least one requested limit and validate decimal scale, positive amount, and the inclusive 1,000.0000 through 3,000.0000 range.
- [ ] Reject a requested limit below the current effective limit as DAILY_LIMIT_OUT_OF_RANGE. Treat an equal requested limit as an idempotent no-op that does not create a duplicate override or unnecessarily advance the account version.
- [ ] Permit a later request to increase the same account, operation type, and effective date by updating its existing override.
- [ ] Lock the account, verify owner or manager authority, check the ETag, calculate the current effective date, and write both requested limit changes atomically.
- [ ] Increment account management_version once for a successful state change and return the next ETag. Do not increment it when a request is a no-op.
- [ ] Reserve and finalize the generic API-request idempotency record in the same transaction, scoped to actor, daily-limit-update operation, account, and key.
- [ ] Store response data for replay, including the first execution's effective date and expiration. A retry after local midnight replays the original result rather than creating a new override.
- [ ] Write a safe DAILY_LIMIT_UPDATED audit event with the actor, account, correlation ID, and old and new limit values.

### 8.4 Keep limit updates compatible with money operations

- [ ] Preserve the phase-6 money-operation lock order: account rows first, then source daily-usage rows.
- [ ] Have a limit update lock the account before reading or writing overrides. It does not need to lock usage rows because lowering a limit is out of scope.
- [ ] Accept the serial outcome of a concurrent increase and money operation: the operation either evaluates the old limit before the increase or the new limit after it, but never sees a partial override.
- [ ] Do not alter historical daily usage or transaction entries when a limit is increased.

### 8.5 Implement account-history reads

- [ ] Define GET /api/v1/accounts/{accountNumber}/transactions for the account-owning customer or any ACTIVE manager.
- [ ] Enforce ownership in the service for customers and use the existing global manager authorization policy.
- [ ] Read only immutable transaction entries. Failed business operations have no entries and therefore do not appear in account history.
- [ ] Return the documented safe fields: transaction ID, type, completed status, signed amount delta, balance after, currency, and creation time.
- [ ] Use newest-first ordering by created_at DESC and id DESC.
- [ ] Implement an opaque cursor containing the last entry position and bind it to the account and active filter set.
- [ ] For the next page, query only rows older than the cursor tuple for the same account. New entries created after a traversal begins appear only in a new traversal.
- [ ] Support bounded page size and optional from and to timestamp filters. Validate the range and reject a cursor reused with different filters or a different account.
- [ ] Use the existing account_id, created_at DESC, id DESC index; do not load all account entries and paginate in memory.

### 8.6 Test limits and history

- [ ] Test default effective limits, valid same-day increases, both operation types independently, maximum limits, no-op equal requests, and rejected decreases.
- [ ] Test customer ownership, global manager access, missing or stale ETags, idempotency replay, conflicting key reuse, and an after-midnight retry.
- [ ] Test timezone and daylight-saving boundaries with a fixed clock, including computed expiry at the next local midnight.
- [ ] Test an increase racing with a withdrawal or transfer and verify a serial result with no partial override or incorrect usage.
- [ ] Test history authorization, page-size and filter validation, and safe response fields.
- [ ] Insert entries sharing the same created_at and verify cursor pages have no duplicate or skipped rows because id breaks the tie.
- [ ] Add an entry after the first page and verify it does not appear in that traversal's next page but does appear in a new traversal.
- [ ] Verify failed business transactions do not appear in history and that history reads do not modify entries, balances, usage, or timestamps.

### Completion checklist

- [ ] Customers and managers can raise a current-day withdrawal or transfer limit only within the documented range.
- [ ] Effective limits honor customer timezones, expire without a midnight reset job, and remain correct under concurrent money operations.
- [ ] Limit updates are ETag-protected, idempotent, atomic, and audited.
- [ ] Account history is authorized, indexed, newest-first, cursor-stable, and built only from immutable entries.
- [ ] No cleanup job deletes an override, idempotency record, usage aggregate, transaction, entry, or audit event in this phase.
- [ ] Commit the completed checkpoint with a message such as implement limits and account history.

## 9. Add demo resilience, protection, and observability

This phase adds the reliability and operability behavior required by the demo without deploying a full monitoring, tracing, logging, or notification platform. The service emits standard signals and fails safely when its PostgreSQL or Redis dependencies are unavailable.

### 9.1 Implement the configurable demo failure simulator

- [ ] Define a FailureSimulator port used only by deposit, withdrawal, and transfer orchestration.
- [ ] Bind configuration for enabled state, independent failure rate, and allowed injection points.
- [ ] Enable the simulator only in the demo profile with a default 15 percent failure chance. Keep it disabled in normal automated tests and production-like profiles.
- [ ] Implement BEFORE_TRANSACTION to fail before persistence begins and leave no database state.
- [ ] Implement DURING_TRANSACTION_BEFORE_COMMIT to throw from inside the money transaction and roll back transaction, entries, balances, daily usage, audit, and idempotency together.
- [ ] Implement AFTER_COMMIT_BEFORE_RESPONSE outside the committed transaction but before the HTTP response is delivered. The committed idempotency record must make the same-key retry replay the completed result.
- [ ] Let tests force an injection point or deterministic selection; never rely on random probability for a test assertion.
- [ ] Return only the existing safe INTERNAL_ERROR response for simulated infrastructure failure. Log the internal cause and correlation ID without exposing injection details to the client.
- [ ] Keep business-rule rejections distinct from simulator failures: valid business rejection persists its FAILED transaction, while a simulated pre-commit failure leaves no durable money-operation state.

### 9.2 Add bounded retention cleanup

- [ ] Implement a scheduled hourly maintenance job with configurable batch sizes and retention periods.
- [ ] Delete expired account-limit overrides in small batches. Their business expiration remains request-time evaluation, not deletion timing.
- [ ] Delete only finalized money idempotency records and finalized generic idempotency records after their retention deadlines. Never delete an active record as a recovery strategy.
- [ ] Optionally delete daily-limit usage aggregates older than configured retention, without affecting current-day checks.
- [ ] Never delete or modify transactions, transaction entries, or audit events.
- [ ] Use the expires_at and usage_date indexes; keep each cleanup transaction small enough not to block money operations.
- [ ] If multiple application instances are introduced, guard a cleanup run with a PostgreSQL advisory lock so one instance performs each cycle.
- [ ] Let Redis expire JWT invalidation keys through their existing TTL rather than scanning or deleting them in the cleanup job.
- [ ] Emit safe cleanup counts and failure logs; a failed cleanup run must not make expired overrides effective.

### 9.3 Add Redis-backed API rate limiting

- [ ] Implement a Redis token-bucket limiter with an atomic refill-and-consume operation so limits are consistent across future application instances.
- [ ] Configure capacity, refill rate, and endpoint-group policy through typed settings rather than hard-coded business logic.
- [ ] Apply a coarse anonymous limit by direct client IP address. Because this demo has no reverse proxy, do not trust forwarded-client-IP headers.
- [ ] For login, enforce both client-IP and normalized-username limits before password-hash verification.
- [ ] For authenticated endpoints, enforce a limit keyed by authenticated user identity.
- [ ] Apply a stricter additional limit to money operations keyed by source account identity.
- [ ] Return 429 RATE_LIMIT_EXCEEDED with an accurate Retry-After header and do not call business logic or persist state for a limited request.
- [ ] Keep circuit breakers out of inbound rate limiting. They are not a replacement for admission control.
- [ ] Treat Redis as a required security dependency. When it is unavailable, do not bypass JWT invalidation or rate limits.

### 9.4 Complete health and dependency behavior

- [ ] Configure internal-only Spring Actuator liveness and readiness endpoints.
- [ ] Report liveness when the process is running; make readiness require reachable PostgreSQL and Redis.
- [ ] Return or map affected application requests to 503 DEPENDENCY_UNAVAILABLE when PostgreSQL or Redis is unavailable.
- [ ] Keep credentials, connection strings, encryption keys, and detailed dependency errors out of health responses and logs.
- [ ] Verify the default NotificationService adapter runs only after a successful commit and that its failure is non-blocking for the completed money operation.

### 9.5 Emit useful, safe observability signals

- [ ] Configure structured Log4j output with timestamp, level, correlation ID, internal code, public error code, HTTP status, operation type, and safe outcome fields.
- [ ] Ensure logs never include passwords, JWTs, authorization headers, document values, document hashes, ciphertext, encryption keys, account numbers, or raw idempotency keys.
- [ ] Add Micrometer HTTP request count, error count, latency histogram, p99-supporting distribution, rate-limit rejection, cleanup outcome, dependency state, and money-operation outcome metrics.
- [ ] Keep metric labels low-cardinality. Never use account number, user ID, idempotency key, correlation ID, document identifier, or transaction ID as a metric tag.
- [ ] Add OpenTelemetry spans for inbound HTTP handling, PostgreSQL activity, Redis activity, and money-operation orchestration.
- [ ] Attach the correlation ID as a trace attribute while keeping the trace ID independent.
- [ ] Make metrics and tracing exporter configuration optional. Do not require a local Prometheus, Grafana, Jaeger, Elasticsearch, Logstash, or Kibana deployment.
- [ ] Expose only the intended internal Actuator and metrics endpoints in Docker Compose.

### 9.6 Verify resilience and operability behavior

- [ ] Test all three FailureSimulator injection points with a forced deterministic setting.
- [ ] For BEFORE_TRANSACTION, assert no durable state. For DURING_TRANSACTION_BEFORE_COMMIT, assert complete rollback. For AFTER_COMMIT_BEFORE_RESPONSE, assert one durable completion and successful same-key replay.
- [ ] Test that normal automated tests run with the simulator disabled.
- [ ] Test cleanup with expired and unexpired overrides, both finalized idempotency tables, old usage aggregates, and immutable ledger or audit records.
- [ ] Test rate-limit capacity, refill, Retry-After calculation, per-IP login protection, per-user protection, source-account money protection, and that limited requests do not reach business logic.
- [ ] Test Redis unavailability: readiness becomes unhealthy and protected requests fail safely rather than accepting a token or bypassing a limiter.
- [ ] Test PostgreSQL unavailability: readiness becomes unhealthy and data-dependent requests return the documented dependency error.
- [ ] Use an in-memory meter registry and trace exporter in tests to assert expected signals and to verify no prohibited high-cardinality tags or secret log fields are emitted.
- [ ] Test that notification-adapter failure after commit is logged or metered but does not change the stored money result.

### Completion checklist

- [ ] Every configurable simulator failure leaves either no state before commit or one replayable completed outcome after commit.
- [ ] Expired short-lived records are cleaned safely; immutable financial and audit history is retained.
- [ ] Redis rate limits basic abuse without weakening security when Redis is unavailable.
- [ ] The service has safe logs, metrics, traces, liveness, readiness, and dependency-failure behavior without requiring an external observability stack.
- [ ] Commit the completed checkpoint with a message such as add resilience and observability.

## 10. Prepare the demo for review

- Complete OpenAPI descriptions, examples, and response-error contracts.
- Run full integration tests through Testcontainers and the application through Docker Compose.
- Run and document the reproducible 50-concurrent-request load test with the failure simulator disabled.
- Verify that logs and API responses never expose passwords, JWTs, document values, lookup hashes, or encryption keys.

**Checkpoint:** a reviewer can configure secrets, start the stack, run migrations and tests, and demonstrate the main flows through Swagger or Postman.
