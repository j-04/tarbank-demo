# Project notes

This document records deliberate trade-offs and deferred work that are useful during an implementation review. It is not a list of incomplete requirements.

## Performance acceptance

The reproducible load test keeps login visible but evaluates it separately from standard business endpoints:

- The measured workload holds 50 active workers and includes login, account reads, paginated history, deposits, withdrawals, and same-currency transfers.
- Account, history, and money-operation endpoint groups must remain below one second at p99.
- Login must remain below two seconds at p99. Credential verification deliberately uses CPU-intensive password hashing, so applying the same latency target as a database-backed read would encourage an unsafe reduction in password-hashing cost.
- Login latency is never removed from the report or hidden in an aggregate.

The recorded 2026-09-29 run reports login at 1,652.20 ms p99 and all six business endpoint groups below 339 ms p99. It therefore passes the clarified thresholds. The original invocation returned a nonzero status under the former shared one-second threshold; the recorded measurements were not changed when the criteria were clarified. See `docs/PERFORMANCE.md` for the complete environment and results.

## Notification extension point

### Implemented behavior

Notifications are an internal extension point, not a user-facing feature:

- `NotificationService` accepts an `Outcome` containing the transaction identifier, operation type, final status, and optional public failure code.
- `MoneyOperationService` registers notification dispatch with Spring transaction synchronization. Dispatch occurs only after the transaction commits, for both completed and persisted failed business outcomes.
- `LoggingNotificationService` is the default adapter. It writes a structured operational event without credentials, JWTs, identity-document data, account numbers, customer identifiers, or message content.
- Adapter exceptions are caught after commit, recorded through `tarbank.notifications{outcome="failure"}`, and logged without changing the stored transaction or API result.
- Integration coverage verifies that an adapter failure cannot undo the balance, transaction, entry, or audit record and that immutable financial history remains protected.

### Deliberate limitations

The demo has no notification REST API, inbox, notification table, recipient resolution, message templates, delivery channels, retry policy, read state, or delivery guarantee. The current port does not carry a recipient, communication address, or rendered message.

Without a transactional outbox, dispatch is best-effort. The database can commit successfully and the process can stop before the adapter receives the outcome. This is acceptable because durable notification delivery is explicitly outside the demo scope.

### Path to durable delivery

If durable notifications become a requirement:

1. Define notification types, recipients, authorization rules, safe payloads, retention, and delivery expectations.
2. Add notification and outbox records in the same database transaction as the final money-operation result.
3. Publish outbox records asynchronously with idempotent consumers, retries, monitoring, and a dead-letter or terminal-failure policy.
4. Add delivery adapters such as email, push, or an external notification service without coupling them to the money transaction.
5. Add customer or manager retrieval APIs only after the persistence model and ownership rules are defined.

### Open product decisions

- Which successful and failed outcomes notify the source customer, destination customer, or service operators.
- Whether business rejections create customer notifications, service notifications, or both.
- Message templates, localization, delivery channels, and customer communication preferences.
- Retention, pagination, read state, redaction, and deletion rules for a future inbox.
- Required delivery guarantees, retry limits, and operational ownership of permanently failed deliveries.
