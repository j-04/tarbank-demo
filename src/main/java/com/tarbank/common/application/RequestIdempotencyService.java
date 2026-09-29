package com.tarbank.common.application;

import com.tarbank.common.config.IdempotencyProperties;
import com.tarbank.common.domain.IdempotencyStatus;
import com.tarbank.common.http.ApiException;
import com.tarbank.common.persistence.ApiRequestIdempotencyRepository;
import com.tarbank.security.domain.UserEntity;
import jakarta.persistence.EntityManager;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;

@Service
public class RequestIdempotencyService {
    private final ApiRequestIdempotencyRepository records;

    private final IdempotencyProperties properties;

    private final JsonMapper json;

    private final EntityManager entityManager;

    public RequestIdempotencyService(ApiRequestIdempotencyRepository records,
                                     IdempotencyProperties properties,
                                     JsonMapper json,
                                     EntityManager entityManager) {
        this.records = records;
        this.properties = properties;
        this.json = json;
        this.entityManager = entityManager;
    }

    /**
     * Executes a non-credential request once and stores its successful response for exact replay.
     *
     * @param actor authenticated user that owns the idempotency namespace
     * @param operation stable operation name, such as account creation
     * @param scope business resource scope that prevents unrelated key collisions
     * @param key client-generated UUID reused only for an identical retry
     * @param requestHash canonical hash of all business-significant request fields
     * @param type response type used to deserialize a completed replay
     * @param successStatus HTTP status stored with the first successful response
     * @param firstExecution mutation invoked only when no completed record exists
     * @param <T> response body type
     * @return first or stored response, its status, and whether it was replayed
     */
    @Transactional
    public <T> Result<T> execute(UserEntity actor,
                                 String operation,
                                 String scope,
                                 UUID key,
                                 String requestHash,
                                 Class<T> type,
                                 int successStatus,
                                 Supplier<T> firstExecution) {
        return execute(actor, operation, scope, key, requestHash, null, type, successStatus, firstExecution);
    }

    /**
     * Implements the locking idempotency protocol, with optional migration from a legacy request
     * fingerprint. The in-progress row and the business mutation share one transaction, so a
     * rollback removes both and cannot leave a permanently incomplete request.
     *
     * @param actor authenticated user that owns the idempotency namespace
     * @param operation stable operation name
     * @param scope business resource scope
     * @param key client-generated idempotency UUID
     * @param requestHash current canonical request fingerprint
     * @param legacyRequestHash prior fingerprint accepted only for an in-place compatibility upgrade
     * @param type response type used to deserialize a completed replay
     * @param successStatus HTTP status persisted with a successful response
     * @param firstExecution mutation invoked exactly once for this actor, operation, scope, and key
     * @param <T> response body type
     * @return first or stored response, its status, and whether it was replayed
     */
    @Transactional
    public <T> Result<T> execute(UserEntity actor,
                                 String operation,
                                 String scope,
                                 UUID key,
                                 String requestHash,
                                 String legacyRequestHash,
                                 Class<T> type,
                                 int successStatus,
                                 Supplier<T> firstExecution) {
        try {
            entityManager.createNativeQuery("set local lock_timeout = '" + properties.lockTimeout()
                                                                                     .toMillis() + "ms'")
                         .executeUpdate();
            Instant now = Instant.now();
            records.insertInProgress(actor.getId(), operation, scope, key, requestHash,
                                     now.plus(properties.retention()), now);
            var record = records.findForUpdate(actor.getId(), operation, scope, key)
                                .orElseThrow(this::inProgress);
            if (!matches(record.getRequestHash(), requestHash)) {
                if (legacyRequestHash == null || !matches(record.getRequestHash(), legacyRequestHash)) {
                    throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT",
                                           "Idempotency key was reused with different input.", "TAR-IDEMPOTENCY-001");
                }
                record.replaceRequestHash(requestHash, now);
            }
            if (record.getStatus() == IdempotencyStatus.COMPLETED) {
                return new Result<>(deserialize(record.getResponseBody(), type), record.getResponseStatus(), true);
            }
            T response = firstExecution.get();
            record.complete(successStatus, serialize(response), Instant.now());
            return new Result<>(response, successStatus, false);
        } catch (CannotAcquireLockException exception) {
            throw inProgress();
        }
    }

    private <T> T deserialize(String responseBody,
                              Class<T> type) {
        try {
            return json.readValue(responseBody, type);
        } catch (Exception exception) {
            throw new IllegalStateException("Stored idempotency response is invalid.", exception);
        }
    }

    private String serialize(Object response) {
        try {
            return json.writeValueAsString(response);
        } catch (Exception exception) {
            throw new IllegalStateException("Idempotency response serialization failed.", exception);
        }
    }

    private boolean matches(String stored,
                            String supplied) {
        return MessageDigest.isEqual(
                stored.getBytes(StandardCharsets.US_ASCII), supplied.getBytes(StandardCharsets.US_ASCII));
    }

    private ApiException inProgress() {
        return new ApiException(HttpStatus.CONFLICT, "REQUEST_IN_PROGRESS",
                                "A matching request is still being processed.", "TAR-IDEMPOTENCY-002");
    }

    public record Result<T>(T body, int status, boolean replayed) {
    }
}
