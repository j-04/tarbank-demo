# Deferred implementation notes

## Notifications

### Current scope

Notifications are an extension point, not a user-facing feature in the demo.

- The core banking service exposes an internal NotificationService port.
- A money operation produces a notification request after it reaches a final completed or failed outcome.
- The request should contain the operation result, relevant recipient, transaction identifier, public outcome code, and correlation identifier.
- Notification requests and logs must not contain credentials, JWTs, identity-document data, or unnecessary personal data.
- The demo adapter may be a no-op or structured-log implementation.
- A notification failure must not roll back or change a committed money operation.
- The demo has no notification REST endpoints, notification inbox, persistence model, delivery channels, retry policy, or read-state tracking.

### Known limitation

Without a transactional outbox, notification delivery is best-effort. For example, the service can commit a transfer and then stop before the notification adapter receives its request. This is acceptable for the demo because notification delivery is not guaranteed.

### Future implementation

If durable notifications become a requirement:

1. Add a notification table for recipient, scope, type, message payload, delivery state, timestamps, and optional read state.
2. Add a transactional outbox record in the same database transaction as the money operation.
3. Publish outbox records asynchronously with retries, deduplication, monitoring, and a failure policy.
4. Add delivery adapters such as email, push, or an external service.
5. Add customer and manager notification retrieval APIs only after the notification model and authorization rules are defined.

### Open decisions

- Which money-operation outcomes notify the source customer, destination customer, or managers.
- Customer and service notification types and message templates.
- Delivery channels and customer communication preferences.
- Retention period, read state, and pagination for a future notification inbox.
- Whether failed business operations should create customer notifications, service notifications, or both.
