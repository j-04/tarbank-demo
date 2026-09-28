# Implementation plan

This plan delivers small, runnable slices instead of creating all entities, DTOs, controllers, repositories, services, and validators in separate large phases. Each slice includes its request and response DTOs, input validation, authorization, persistence, business logic, and tests.

Commit after each completed checkpoint. Do not implement deferred notification delivery, payment-network integration, currency conversion, or other stated non-goals.

## 1. Bootstrap the application

This phase creates a reproducible, containerized application shell. It deliberately excludes domain entities, domain schema changes, API endpoints, and business rules; those begin in later phases.

### 1.1 Create the build

- [x] Create a Gradle-based Spring Boot project targeting Java 25.
- [x] Pin the Java, Spring Boot, and plugin versions in the build configuration.
- [x] Add only the initial dependencies needed to start the service: Spring Web, Validation, Actuator, Security, Data JPA, Data Redis, PostgreSQL, Liquibase, Log4j, and test support.
- [x] Create a valid, empty Liquibase master changelog and enable it at startup so the phase-1 application checkpoint can run before domain migrations exist.
- [x] Configure the build to use Log4j rather than the default logging implementation.
- [x] Add a minimal application class and verify that the project compiles and its unit-test task runs.

### 1.2 Establish the package layout

- [x] Use one root package, for example com.tarbank, so Spring component scanning is predictable.
- [x] Create top-level packages for common, security, customer, account, and money.
- [x] Within each domain package, reserve api, application, domain, and persistence subpackages. Create implementation classes only when their phase needs them.
- [x] Keep cross-cutting HTTP error handling, correlation-ID handling, configuration, and shared API models in common; do not put banking business logic there.

### 1.3 Define bootstrap configuration

- [x] Add separate local and test application profiles.
- [x] Bind database, Redis, JWT, encryption-key, and manager-seed settings through typed configuration properties.
- [x] Validate required configuration on startup and fail safely with an actionable message when a required setting is absent.
- [x] Add an .env.example file that names required local settings without containing real secrets.
- [x] Keep actual passwords, JWT signing keys, and document-encryption keys out of source control.

### 1.4 Add common HTTP behavior

- [x] Define the standard API error envelope with HTTP status, stable public error code, safe message, correlation ID, and optional field errors.
- [x] Keep internal TAR-AREA-NNN codes only in structured logs and support diagnostics; never include them in an API response.
- [x] Add global exception handling for malformed JSON, Bean Validation failures, unsupported methods, and unexpected errors.
- [x] Add a correlation-ID filter: preserve a valid client-supplied X-Correlation-Id; generate a UUID only when the header is absent; reject a supplied malformed value with 400 VALIDATION_ERROR.
- [x] Return the correlation ID in every response except a malformed supplied X-Correlation-Id: its error body has correlationId: null and its response omits X-Correlation-Id. Never echo the malformed value; make each accepted or generated ID available to structured logs.
- [x] Add tests that an absent correlation header receives a generated UUID in both the response header and body, while a malformed supplied header receives exactly 400 VALIDATION_ERROR, correlationId: null in the error body, no X-Correlation-Id response header, and no echo of the malformed value.
- [x] Do not add domain-specific error codes or authorization rules yet; add them with their owning feature.

### 1.5 Containerize the local demo

- [x] Add a multi-stage Dockerfile that builds the application image with a pinned Java 25 base image.
- [x] Add a Compose service for the application, PostgreSQL, and Redis.
- [x] Put the services on an isolated Compose network; expose only the application port for local use.
- [x] Configure a named PostgreSQL volume so data survives a normal container restart.
- [x] Configure the application through environment variables or a local, ignored environment file; never bake secrets into the image.
- [x] Add health checks and service-start ordering appropriate for the application, PostgreSQL, and Redis.

### 1.6 Expose operational health

- [x] Configure Spring Actuator liveness and readiness endpoints for internal use.
- [x] Make readiness depend on PostgreSQL and Redis, because both are required by the design.
- [x] Verify that the application becomes ready only after both dependencies are available.
- [x] Keep a full metrics, tracing, rate-limiting, and dependency-failure implementation for phase 9.

### Completion checklist

- [x] gradle test completes successfully from a clean checkout.
- [x] docker compose config validates the Compose configuration.
- [x] docker compose build creates the service image.
- [x] docker compose up starts the service, PostgreSQL, and Redis.
- [x] The liveness and readiness endpoints report healthy after startup.
- [x] No real secret is present in tracked files.
- [ ] Commit the completed checkpoint with a message such as bootstrap application and compose runtime.

## 2. Establish database migrations and test infrastructure

This phase makes PostgreSQL schema evolution repeatable before any banking tables are introduced. Liquibase is the schema owner; JPA maps and validates that schema but must never create or alter it.

### 2.1 Configure Liquibase as the schema owner

- [x] Use the valid empty Liquibase master changelog created in phase 1 and verify Spring Boot runs it at startup.
- [x] Establish one naming convention for ordered versioned changelogs, for example a version or timestamp followed by a concise purpose.
- [x] Add the first versioned changelog only when it owns a real schema change. Do not add a no-op changeset merely to create a migration number.
- [x] Configure every profile to use Liquibase for schema changes.
- [x] Set Hibernate ddl-auto to validate once entities exist; do not use create, create-drop, or update outside an explicitly isolated local experiment.
- [x] Treat an applied changeset as immutable. Correct a released schema through a new changeset rather than editing migration history.
- [x] Reserve migrations for tables, constraints, indexes, database checks, and required reference data. Keep manager startup seeding in application code for phase 3.

### 2.2 Configure PostgreSQL connections

- [x] Add typed datasource settings for the local Compose database and test database.
- [x] Use PostgreSQL as the only supported runtime and integration-test database; do not introduce H2 or another database with different locking and numeric behavior.
- [x] Pin the PostgreSQL container major version and use the same version for Docker Compose and Testcontainers.
- [x] Make the application wait for a reachable database through normal connection and readiness behavior, not by relying only on Compose startup order.
- [x] Confirm that the direct local profile and the Compose profile use environment-specific connection settings without duplicating application behavior.

### 2.3 Build the Testcontainers integration-test base

- [x] Add pinned PostgreSQL and Redis Testcontainers dependencies with reusable test container configuration.
- [x] Start both containers before the Spring test application context and supply JDBC and Redis connection properties dynamically.
- [x] Ensure each integration-test run starts from a fresh PostgreSQL database, applies the same Liquibase changelogs used in the demo runtime, and uses isolated Redis state.
- [x] Keep container image versions pinned and separate integration tests from fast unit tests.
- [x] Add only generic test utilities in this phase, such as a clock or JSON helper. Clear the dedicated Redis test database or namespace keys between tests. Add authenticated-request and seeded-user helpers with the identity feature in phase 3.

### 2.4 Verify migration behavior

- [x] Add an integration test that starts the application against fresh PostgreSQL and Redis Testcontainers.
- [x] Assert that Liquibase completes successfully and records its applied changelogs.
- [x] Verify that a second application startup against the same database does not reapply or alter completed migrations.
- [x] Verify that an invalid or missing changelog fails startup clearly rather than allowing Hibernate to create a replacement schema.
- [x] Confirm that the empty baseline contains no accidental banking tables; users, customers, accounts, and transaction tables arrive with their owning feature phases.

### Completion checklist

- [x] The project starts against PostgreSQL with Liquibase enabled.
- [x] No profile can automatically create or update the production-like schema through Hibernate.
- [x] The Testcontainers integration test creates a fresh PostgreSQL database, applies the full migration set, and uses isolated Redis state.
- [x] Re-running the application against the same database is safe and does not change applied migrations.
- [x] No H2-specific configuration or test assumptions are present.
- [ ] Commit the completed checkpoint with a message such as establish Liquibase migration baseline.

## 3. Implement identity, authentication, and customer creation

This is the first protected vertical slice. It creates the identity schema, starts configured manager accounts, authenticates users, and lets an active manager onboard and retrieve an adult customer. It does not yet implement customer updates, status transitions, accounts, or money movement.

### 3.1 Create the identity and onboarding schema

- [x] Add Liquibase changesets for users, managers, customers, audit events, and generic API-request idempotency records.
- [x] Add the roles, lifecycle-status values, credential version, foreign keys, unique username constraint, customer-to-manager relationship, customer version, and required timestamps from the design.
- [x] Add the unique identity-document constraint using document type, issuing country, and document lookup hash; never store a plaintext document number.
- [x] Add database constraints for fixed-format fields such as country codes and bounded text fields where appropriate, while keeping age, document-validity, password-format, and cross-profile rules in service validation.
- [x] Add the indexes needed now for login by username, customer retrieval, manager relationships, and idempotency lookup.
- [x] Create separate User, Manager, and Customer entities. Keep Manager and Customer as one-to-one role-specific profiles rather than a nullable-field mega-entity.
- [x] Model role and lifecycle status as explicit enums; do not accept arbitrary database strings in API or business logic.
- [x] Validate in the service that a user has exactly one profile matching its role, because this cross-table invariant cannot be expressed fully by a normal foreign key.

### 3.2 Implement sensitive customer-data handling

- [x] Normalize identity-document numbers consistently before calculating their lookup value.
- [x] Encrypt each document number with application-level authenticated encryption and a configured key version before persistence.
- [x] Calculate the HMAC-SHA-256 lookup hash with a separate configured secret and use only that hash for duplicate detection.
- [x] Bind encryption and HMAC keys through validated configuration properties; fail startup if a required key is absent or malformed.
- [x] Ensure entities, DTOs, exceptions, structured logs, and audit metadata never expose document numbers, ciphertext, lookup hashes, or encryption keys.
- [x] Test that the persisted ciphertext is not plaintext and that the same normalized document is detected as a duplicate despite randomized encryption.

### 3.3 Seed manager principals safely

- [x] Define configuration for one or more startup managers, including username, initial password, and display names.
- [x] Run manager seeding only after Liquibase has completed.
- [x] For each configured manager, create the User and Manager profile in one transaction when they do not already exist.
- [x] Make seeding idempotent: an existing matching manager is retained and is not silently overwritten on every restart.
- [x] BCrypt-hash manager passwords before persistence and never log configured credentials or hashes.
- [x] Do not add a REST endpoint for manager creation, reassignment, portfolio management, or manager customer-account access boundaries.

### 3.4 Implement stateless JWT authentication

- [x] Configure Spring Security as stateless and deny protected endpoints by default.
- [x] Expose only login, health endpoints, and the intended documentation endpoint without a bearer token; keep all customer APIs manager-protected for this phase.
- [x] Implement login with normalized username and password verification. Issue a signed JWT only for an ACTIVE user.
- [x] Include a stable subject, role, unique jti, credential-version claim, and configured expiration in the JWT. Default the expiration to one hour.
- [x] Implement bearer-token authentication that verifies signature and expiry, checks the jti invalidation entry in Redis, and loads the current user status and credential version.
- [x] Reject blocked or deactivated users before business logic. Create a principal that carries only the identifiers and role needed for authorization.
- [x] Implement authenticated logout by storing the JWT jti in Redis with a TTL no longer than the token's remaining lifetime.
- [x] Return the agreed login and logout error codes without revealing whether an invalid login failed because of an unknown username, wrong password, or inactive user.
- [x] Defer Redis rate limiting to phase 9, but keep the authentication flow structured so a boundary limiter can be added without changing login business logic.

### 3.5 Implement manager-led customer onboarding

- [x] Define request and response DTOs for POST /api/v1/customers and GET /api/v1/customers/{customerId}.
- [x] Require a manager bearer token and a UUID v4 Idempotency-Key for customer creation.
- [x] Validate manager-assigned username format, exactly 12 printable password characters, and that the password differs from the username.
- [x] Validate required names, age of at least 18 at creation, optional non-expired document expiry date, E.164 phone number, ISO residence and issuing countries, address fields, and a valid IANA timezone.
- [x] In one database transaction, reserve the generic idempotency record, create the User and Customer profile, assign the acting manager as manager_id, write the CUSTOMER_CREATED audit event, and finalize the saved response.
- [x] Replay a completed identical creation request from its saved idempotency response. Reject reuse of the same key with different normalized input and handle a concurrent in-progress key according to the design.
- [x] Return only the documented safe customer fields. Never return the supplied password, password hash, document number, encrypted document, or lookup hash.
- [x] Implement manager retrieval of one customer using the safe customer response contract. Defer customer listing, profile updates, and lifecycle transitions to phase 4.

### 3.6 Test the protected vertical slice

- [ ] Add integration tests for startup manager seeding and safe repeated application startup.
- [ ] Test successful login, invalid credentials, expired, invalidated, or credential-version-mismatched JWTs, and rejection of blocked or deactivated principals where applicable.
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

- [x] Add the accounts Liquibase changeset with the account owner, currency, balance, lifecycle status, status-change metadata, management version, and timestamps defined in the design.
- [x] Add the Account entity and repository methods needed to load a customer's non-deactivated accounts for a status change.
- [x] Add a repository method that locks the affected account rows before their statuses are changed.
- [x] Define one shared lifecycle locking protocol: lock the customer row first, then lock affected account rows in ascending internal account-ID order.
- [x] Require account creation and account unblocking in phase 5 to use that same customer-first protocol before they verify that a customer is ACTIVE.
- [x] Do not expose account creation, account detail, daily-limit, or account-status endpoints in this phase. Those remain phase 5.
- [x] Update phase 5 to build its public account lifecycle on this persistence baseline rather than creating a second account schema.

### 4.2 Implement manager customer listing and safe reads

- [x] Add the compatible QueryDSL JPA dependency, annotation processing, and generated-source build configuration.
- [x] Add manager-only GET /api/v1/customers with bounded cursor pagination and an optional lifecycle-status filter, implemented with QueryDSL predicates for the optional filter and keyset cursor.
- [x] Verify from a clean checkout that annotation processing generates and compiles the Q-types used by customer listing.
- [x] Extend GET /api/v1/customers/{customerId} to return the current customer ETag in the customer-vN form.
- [x] Keep customer lists and detail responses limited to safe fields; never disclose document numbers, lookup hashes, ciphertext, passwords, or password hashes.
- [x] Keep manager authorization global as specified in the design: every ACTIVE manager may access every customer, without a portfolio restriction.
- [x] Return the documented validation, authentication, authorization, and not-found error contracts.

### 4.3 Implement mutable profile updates

- [x] Define the PATCH /api/v1/customers/{customerId} request DTO for the documented mutable profile fields: names, email, phone number, and current residential address.
- [x] Reject changes to username, date of birth, timezone, identity-document data, manager relationship, role, and lifecycle status through this endpoint.
- [x] Require an If-Match customer-vN header. Reject an absent header with 428 PRECONDITION_REQUIRED and a stale header with 412 PRECONDITION_FAILED.
- [x] Load and update the customer in one transaction, increment the customer version exactly once, and return the next ETag.
- [x] Reapply contact, address, and format validation to partial updates. Keep the field-error response safe and specific enough for a caller to correct input.
- [x] Write a CUSTOMER_UPDATED audit event with the manager, customer, correlation ID, and safe change metadata. Do not store sensitive old or new values in audit metadata.

### 4.4 Implement customer status transitions and account cascades

- [x] Define the PATCH /api/v1/customers/{customerId}/status DTO and allow only ACTIVE to BLOCKED, BLOCKED to ACTIVE, and ACTIVE or BLOCKED to DEACTIVATED.
- [x] Reject every other transition, including any reactivation of a DEACTIVATED customer, with 409 INVALID_STATUS_TRANSITION.
- [x] Require If-Match customer-vN and apply the transition in one transaction.
- [x] For a block operation, lock and block every non-deactivated account owned by the customer.
- [x] For an unblock operation, restore only the customer to ACTIVE. Do not automatically unblock any account.
- [x] For deactivation, lock and permanently deactivate every non-deactivated account. Never permit reversal.
- [x] Increment the customer version and every changed account management version. Record the acting manager and timestamp on each changed customer and account.
- [x] Write one CUSTOMER_STATUS_CHANGED audit event and one ACCOUNT_STATUS_CHANGED audit event for each affected account, with old and new statuses and the same correlation ID.
- [x] Lock the customer row first and then affected account rows in ascending internal account-ID order, matching the shared protocol used by account creation and unblocking.

### 4.5 Implement the manager-assisted password-reset flow

- [x] Define POST /api/v1/customers/{customerId}/password-reset as a manager-authorized operation used by the assumed secure branch terminal; it is not a customer self-service or unauthenticated route.
- [x] Require Authorization: Bearer JWT, Content-Type: application/json, and Idempotency-Key: UUID v4. Accept only a newPassword field and return a safe PASSWORD_RESET status response.
- [x] Validate the replacement password using the same exactly-12-printable-character rule and username comparison used during onboarding.
- [x] In one transaction, reserve generic idempotency, lock the corresponding user row, BCrypt-hash the replacement password, write the hash and incremented credential version together, write a CUSTOMER_PASSWORD_RESET audit event, and finalize the replay response.
- [x] Include the credential version in every JWT and reject a token whose claim no longer matches the user's current version with 401 UNAUTHENTICATED. A completed reset therefore invalidates all previously issued customer tokens immediately.
- [x] Ensure neither the replacement password nor hash reaches a response, log, audit metadata, or exception.
- [x] Keep the physical branch terminal outside this project. It is an assumed authenticated manager client of this operation, not a separate application to implement.

### 4.6 Enforce lifecycle status in active sessions

- [x] Reuse the phase-3 per-request user-status lookup so a token issued before a customer is blocked or deactivated is rejected on its next protected request.
- [x] Do not attempt to cancel a request that was already authenticated and began its database transaction before the status change; it follows the established request-entry authentication policy.
- [x] Do not add a separate token-version scheme or bulk Redis invalidation for lifecycle changes; the current status check is the chosen enforcement mechanism. The credential version is reserved for password-reset invalidation.

### 4.7 Test customer lifecycle behavior

- [x] Test customer listing, cursor and status-filter validation, safe read responses, and manager-only access.
- [x] Test successful profile updates, immutable-field rejection, missing ETag, stale ETag, and concurrent updates.
- [x] Test every allowed and forbidden customer-status transition.
- [x] Create accounts through repository fixtures and verify blocking and deactivation cascade atomically, while unblocking leaves account statuses unchanged.
- [x] Test that an already-issued customer token is denied on its next protected request after blocking or deactivation.
- [x] Test the manager-assisted password reset flow, including validation, BCrypt storage, authorization, idempotency, audit logging, and immediate rejection of previously issued customer tokens. Race two different idempotency keys with different passwords, assert that the credential version increases twice and a token issued after the first reset is rejected after the second, and assert that a same-key replay neither changes the hash nor increments the version again.
- [x] Test that customer, account, and audit changes roll back together if the transaction fails.

### Completion checklist

- [x] Managers can list, retrieve, and safely update customers using the documented ETag contract.
- [x] Customer status transitions are authorized, optimistic-concurrency-safe, audited, and follow the allowed state machine.
- [x] Blocking and deactivation cascade to eligible accounts; unblocking does not restore accounts automatically.
- [x] A blocked or deactivated customer cannot use a previously issued token on a new protected request.
- [x] Password resets are manager-assisted, securely stored, and not public or self-service.
- [x] Commit the completed checkpoint with a message such as implement customer lifecycle management.

## 5. Implement account lifecycle management

This phase exposes the account lifecycle built on the persistence baseline introduced in phase 4. It creates zero-balance EUR and USD accounts, provides safe manager and owner reads, and completes account-level status management. Daily-limit overrides and money operations remain later phases.

### 5.1 Complete the account model and account-number generation

- [x] Complete the account migration with the unique immutable public account number, EUR/USD currency constraint, numeric(19,4) zero-balance default, non-negative balance check, and required manager and status metadata.
- [x] Add a database check that enforces the public account-number shape: TB followed by exactly 14 digits.
- [x] Use a database-backed sequence to reserve the numeric portion of an account number and left-pad it to 14 digits. Sequence gaps after a rolled-back creation are acceptable; duplicate numbers are not.
- [x] Keep the public account number immutable and separate from the internal account ID.
- [x] Model currency and account status as explicit enums. Never accept a client-provided balance, account number, lifecycle metadata, or management version.
- [x] Keep balance updates out of the account management-version mechanism; money operations will lock and update balances in phase 6.

### 5.2 Implement manager account creation

- [x] Define the POST /api/v1/customers/{customerId}/accounts request and safe response DTOs.
- [x] Require an ACTIVE manager, a UUID v4 Idempotency-Key, and a target customer that is ACTIVE. Lock the target customer row before that status check and before account creation.
- [x] Accept only EUR or USD and create the account with balance 0.0000 and ACTIVE status.
- [x] In one database transaction, reserve the generic idempotency record, generate and persist the account, record the creating manager, write an ACCOUNT_CREATED audit event, and finalize the replay response.
- [x] Scope account-creation idempotency to the manager, customer, and account-creation operation. Replay an identical completed request without creating another account; reject conflicting reuse or a concurrent in-progress request as defined by the generic idempotency policy.
- [x] Do not expose customer account creation, account-number choice, currency changes, or starting-balance input.

### 5.3 Implement account lists and safe detail reads

- [x] Add manager-only GET /api/v1/customers/{customerId}/accounts with bounded cursor pagination and the safe account-summary contract.
- [x] Add customer-only GET /api/v1/accounts that lists every account owned by the authenticated customer, including its lifecycle status.
- [x] Use a stable account-list ordering and opaque cursor based on the last returned account position.
- [x] Add GET /api/v1/accounts/{accountNumber} for the account-owning customer or any ACTIVE manager.
- [x] Enforce customer ownership in the service, not only in the controller. A customer must not read another customer's account.
- [x] Return the current balance, currency, status, and account ETag in the account-vN form. Do not return internal account IDs or manager-only audit metadata.
- [x] Return configured default withdrawal and transfer limits in the account detail response with null expiresAt values until phase 8 adds effective temporary overrides.
- [x] Defer account-history reads until transaction entries exist in phase 8.

### 5.4 Implement account status management

- [x] Define PATCH /api/v1/accounts/{accountNumber}/status as a manager-only operation that requires If-Match: account-vN.
- [x] Allow only ACTIVE to BLOCKED, BLOCKED to ACTIVE, and ACTIVE or BLOCKED to DEACTIVATED.
- [x] Reject every other transition, including DEACTIVATED reactivation, with 409 INVALID_STATUS_TRANSITION.
- [x] Permit an account unblock only when its customer is ACTIVE. A manager must explicitly unblock each eligible account after its customer is restored.
- [x] In one transaction, identify the account owner, lock the customer row first, then lock and re-read the target account, verify the customer and account state, apply a valid transition, increment management_version once, and record the manager and timestamp.
- [x] Write an ACCOUNT_STATUS_CHANGED audit event with old and new status, acting manager, target account, and correlation ID.
- [x] Keep customer-level cascade behavior in phase 4 as the single owner of a customer-driven account status change; do not duplicate it in the account controller.

### 5.5 Test the account lifecycle slice

- [x] Test account-number format, uniqueness, immutability, and concurrent account creation.
- [x] Test supported currencies, zero balance, and rejection of unsupported currency or client-provided balance data.
- [x] Test manager creation authorization, rejection for blocked or deactivated customers, and customer inability to create an account.
- [x] Test account-creation idempotency for completed replay, conflicting reuse, and concurrent matching requests.
- [x] Test manager and owner account lists, detail ownership checks, pagination validation, and safe response fields.
- [x] Test account ETag generation, missing and stale If-Match behavior, every valid and invalid account transition, and the active-customer rule for unblocking.
- [x] Race account creation and account unblocking with customer blocking and deactivation. Assert that no new or restored ACTIVE account can commit after the customer transition has completed.
- [x] Test that account status changes and audit events commit or roll back together.
- [x] Test that account detail reports configured default daily limits without creating an override record.

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
- [ ] Add transaction constraints for positive numeric(19,4) amount, supported currency, source and destination requirements by operation type, and failure_code required for FAILED transactions and null for COMPLETED transactions.
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
- [ ] Create active customers and zero-balance accounts through fixtures, then fund them through simulated deposits or matching funding transactions and entries. Never directly insert a nonzero balance without corresponding immutable history.
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

This final phase turns the implementation into a reproducible interview-demo handoff. It does not add product features: it verifies the delivered scope, makes setup and API behavior discoverable, and records one repeatable performance result.

### 10.1 Establish final automated verification gates

- [ ] Run the complete unit and integration-test suite from a clean checkout with the failure simulator disabled by default.
- [ ] Require Testcontainers integration tests to apply Liquibase migrations to fresh PostgreSQL and exercise authorization, lifecycle, idempotency, money locking, limits, history, cleanup, rate limiting, and failure-simulator behavior.
- [ ] Run the reconciliation query after the representative integration scenarios and fail the build if any stored account balance differs from the sum of immutable entry deltas.
- [ ] Validate the migration changelog, application configuration binding, and Docker Compose configuration as part of the repeatable verification commands.
- [ ] Keep test data isolated from local demo data. Tests must not depend on an already-running Compose database or Redis instance.
- [ ] Record a short command sequence in the README so a reviewer can run all checks without guessing the profile, prerequisites, or order.

### 10.2 Complete API documentation and examples

- [ ] Generate and expose Swagger OpenAPI documentation from the implemented REST endpoints.
- [ ] Verify that OpenAPI paths, headers, request bodies, success responses, error envelopes, status codes, and field constraints match REQUIREMENTS.md and DESIGN.md.
- [ ] Document bearer-token authentication, X-Correlation-Id behavior, ETag retrieval and If-Match use, and Idempotency-Key UUID v4 use.
- [ ] Include a clear retry example: generate a fresh UUID v4 for a new sensitive operation and reuse the exact same value only to retry that operation.
- [ ] For Postman, document the built-in GUID variable or an equivalent UUID v4 generator; for Swagger UI, instruct the user to paste a generated UUID and retain it for a replay demonstration.
- [ ] Provide safe sample requests for manager login, customer onboarding, account creation, customer login, deposit, withdrawal, transfer, limit increase, and history retrieval.
- [ ] Keep API examples free of real credentials, secrets, document values, and personally identifying data.

### 10.3 Finalize the Docker image and Compose demo runtime

- [ ] Verify the multi-stage Docker build uses pinned image versions and produces a runnable application image without build tooling or source files in the runtime layer.
- [ ] Run the service as a non-root container user where the chosen base image supports it.
- [ ] Verify Docker Compose starts only the application, PostgreSQL, and Redis required for the demo; do not add Prometheus, Grafana, Jaeger, Elasticsearch, or notification infrastructure.
- [ ] Keep PostgreSQL on its named volume and keep PostgreSQL and Redis off host-public ports unless a local troubleshooting need is explicitly documented.
- [ ] Supply configuration and secrets through environment variables or ignored local files. Keep .env.example limited to variable names and safe placeholders.
- [ ] Verify startup ordering, health checks, Liquibase migration execution, readiness, and a clean shutdown followed by a restart with persisted PostgreSQL data.
- [ ] Document the minimum prerequisites, image build command, Compose startup command, health URL, Swagger URL, logs command, and safe local-data reset procedure.

### 10.4 Run the reproducible load test

- [ ] Choose one versioned load-test runner and document it. For this demo, a small k6 scenario run from a pinned container image is sufficient and avoids adding a monitoring stack.
- [ ] Seed a known manager, active customers, and active EUR and USD zero-balance accounts, then fund the load-test accounts through simulated deposits or matching funding transactions and entries so reconciliation remains valid.
- [ ] Disable the failure simulator for the load test and use a rate-limit configuration that permits the controlled test workload without changing business behavior.
- [ ] Generate unique idempotency keys for each new money operation; reuse a key only in an explicit replay case.
- [ ] Exercise 50 concurrent requests across login, account list and detail reads, paginated history reads, deposits, withdrawals, and same-currency transfers.
- [ ] Give money operations isolated or carefully funded source accounts so expected business failures do not dominate the result.
- [ ] Include a warm-up period and enough successful samples to calculate meaningful p50, p95, p99, throughput, and error-rate values.
- [ ] Report latency per endpoint group, including paginated history, rather than hiding a slow endpoint in a blended aggregate.
- [ ] Require p99 below one second for the documented standard endpoint workload and record any expected or unexpected error rate separately.
- [ ] Capture the application image tag or Git commit, Java version, Docker resources, PostgreSQL and Redis versions, load-test command, configuration relevant to the test, and result timestamp with the result.

### 10.5 Perform the review walkthrough

- [ ] Start the stack from a documented clean state and confirm liveness, readiness, metrics, and Swagger availability.
- [ ] Demonstrate manager login, customer creation, customer retrieval, account creation, customer login, and account ownership enforcement.
- [ ] Demonstrate a successful deposit, withdrawal, and transfer, then retrieve the affected account histories.
- [ ] Demonstrate duplicate money-request replay with the same Idempotency-Key and conflicting reuse with changed input.
- [ ] Demonstrate an ETag-protected customer or account update and a stale-ETag rejection.
- [ ] Demonstrate a daily-limit increase and a rejected operation that exceeds the remaining limit.
- [ ] Demonstrate a blocked customer or account rejecting a new protected or money request.
- [ ] Demonstrate one controlled failure-simulator case and its expected rollback or post-commit replay behavior.
- [ ] Show structured logs, one metric view, and trace output by correlation ID without exposing sensitive data.

### 10.6 Conduct the final scope and security review

- [ ] Compare the implementation, OpenAPI contract, Docker configuration, and tests against every requirement and design decision.
- [ ] Confirm all deliberate non-goals remain absent: payment networks, cash systems, FX conversion, fees, interest, overdrafts, settlement states, full double-entry ledger, customer self-service profile management, and durable notification delivery.
- [ ] Scan tracked configuration, Docker files, test fixtures, logs, and generated API examples for passwords, JWTs, signing keys, database credentials, document values, document hashes, and encryption keys.
- [ ] Confirm internal management endpoints and dependency diagnostics do not leak sensitive configuration.
- [ ] Confirm no migration mutates prior applied changesets and no running profile enables Hibernate schema creation or update.
- [ ] Review Git status and commit only intended source, configuration templates, documentation, and test artifacts. Do not commit local volumes, environment files with secrets, load-test raw data containing sensitive values, or build outputs.

### Completion checklist

- [ ] A reviewer can clone the repository, set local configuration, build the Docker image, start the stack, and reach readiness and Swagger using the README alone.
- [ ] The complete automated suite passes against fresh PostgreSQL through Testcontainers.
- [ ] The manual walkthrough demonstrates the core roles, money safety, idempotency, limits, history, auditing, and failure behavior.
- [ ] A dated, reproducible 50-concurrent-request result documents p99 latency below one second for the defined workload.
- [ ] The final repository contains no secrets and implements only the agreed demo scope.
- [ ] Commit the completed checkpoint with a message such as prepare demo for review.
