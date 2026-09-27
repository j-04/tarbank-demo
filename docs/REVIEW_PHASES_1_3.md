# Code review: implementation plan sections 1–3

This file reproduces the latest review findings in plain Markdown for another agent. Findings describe the code at the time of review; verify each against the current implementation before changing it. Line numbers refer to that reviewed snapshot.

The earlier section 1–2 fixes were present. Seven actionable findings remained.

## 1. [P2] Enforce the password policy for seeded managers

Location: `src/main/java/com/tarbank/security/application/ManagerSeeder.java:13`

Manager seeding hashes and stores passwords without enforcing exactly 12 printable characters or checking equality with the username. An isolated integration test confirmed that seeding a manager with password `x` succeeds.

Validate configured credentials before creating a manager, using the same password policy as customer onboarding. Diagnostics must never expose the password.

## 2. [P2] Translate concurrent uniqueness violations into conflicts

Location: `src/main/java/com/tarbank/customer/application/CustomerService.java:26`

Username and document prechecks cannot prevent races between requests with different idempotency keys. When another transaction commits a conflicting username after the precheck, PostgreSQL rejects `saveAndFlush`; `RequestIdempotencyService` wraps the exception and the API returns 500.

Reproduction: hold an uncommitted conflicting user insert, start onboarding with that username, wait until its insert blocks, then commit the competing transaction. Observed 500; expected 409 `USERNAME_ALREADY_EXISTS`.

Translate the named username and document constraints into their documented 409 responses after rollback, preserving atomic onboarding. Cover both unique constraints.

## 3. [P2] Replay completed onboarding before rechecking document expiry

Location: `src/main/java/com/tarbank/customer/application/CustomerService.java:25`

`create` calls `validate` before consulting the idempotency record. A completed onboarding request cannot replay once its document expires: the same key and body return 400 instead of the stored 201 response.

Reproduction: create a customer successfully, advance the service clock beyond the document expiry date, and repeat the original key and body. Observed 400; expected the original 201 result.

Keep parsing and normalization before lookup, but perform time-dependent eligibility checks only during first execution. A completed same-key retry must return the stored outcome.

## 4. [P2] Distinguish inactive users from invalid tokens

Location: `src/main/java/com/tarbank/security/application/JwtAuthenticationFilter.java:19`

Blocked and deactivated users follow the same exception path as invalid credentials and receive 401 `UNAUTHENTICATED`. The design requires 403 `ACCESS_DENIED` for inactive users presenting an otherwise valid token.

Reproduction: issue a token, block its user in the test database, then call a protected endpoint. Observed 401; expected 403.

Separate lifecycle rejection from invalid, expired, revoked, or credential-version-mismatched token handling.

## 5. [P2] Return a validation error for missing required headers

Location: `src/main/java/com/tarbank/common/http/ApiExceptionHandler.java:18`

An authenticated customer-creation request without `Idempotency-Key` raises `MissingRequestHeaderException`, falls into the catch-all handler, and returns 500 `INTERNAL_ERROR`.

Reproduction: submit an otherwise valid onboarding body and manager bearer token without the idempotency header. Observed 500; expected 400 `VALIDATION_ERROR`.

Handle required-header and other request-binding failures explicitly as client errors, preserving the standard correlation envelope.

## 6. [P2] Validate the full UUID v4 representation

Location: `src/main/java/com/tarbank/customer/api/CustomerController.java:17`

`UUID.fromString` accepts shortened components, and checking `version()` alone does not enforce a valid UUID v4 header.

Reproduction: submit an otherwise valid onboarding request with `Idempotency-Key: 1-1-4000-8000-1`. Observed 201 and customer creation; expected 400.

Validate the complete UUID representation and RFC variant before accepting the key. Add deterministic malformed-key coverage.

## 7. [P2] Make the invalid-idempotency-key test deterministic

Location: `src/integrationTest/java/com/tarbank/customer/CustomerOnboardingIntegrationTest.java:82–84`

`UUID.randomUUID().toString().replaceFirst("4", "1")` can replace a random hexadecimal digit before the version position, leaving a valid version-4 UUID. The test then incorrectly expects rejection.

This caused the repository integration suite to fail during review: expected 400, received 201.

Use a fixed non-v4 UUID or explicitly modify the version nibble.

## Verification performed

- `./gradlew --no-daemon test integrationTest`: 6 unit tests passed; 10 of 11 integration tests passed. The failing integration test is finding 7.
- Isolated review tests reproduced all six implementation defects above.
- Additional isolated tests passed for concurrent same-key replay, `REQUEST_IN_PROGRESS` lock-timeout handling, and credential-version invalidation.
- `docker compose config --quiet` passed.
- Review tests were added only to a temporary copy; project sources were not changed by the review.

The temporary review copy was `/tmp/tarbank-phase123-review-odhm6lk7` in WSL. It may still contain `src/integrationTest/java/com/tarbank/customer/ReviewBoundaryIntegrationTest.java`, including reproductions and positive checks. Do not depend on that temporary directory being retained; this document contains the findings and reproduction descriptions.
