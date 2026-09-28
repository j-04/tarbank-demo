package com.tarbank.common.application;

import com.tarbank.common.config.IdempotencyProperties;
import com.tarbank.common.domain.IdempotencyStatus;
import com.tarbank.common.http.ApiException;
import com.tarbank.common.persistence.ApiRequestIdempotencyRepository;
import com.tarbank.security.domain.UserEntity;
import jakarta.persistence.EntityManager;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;

@Service
public class RequestIdempotencyService {
    private final ApiRequestIdempotencyRepository records;

    private final IdempotencyProperties properties;

    private final JsonMapper json;

    private final EntityManager entityManager;

    public RequestIdempotencyService(ApiRequestIdempotencyRepository records, IdempotencyProperties properties,
                                     JsonMapper json, EntityManager entityManager) {
        this.records = records;
        this.properties = properties;
        this.json = json;
        this.entityManager = entityManager;
    }

    @Transactional
    public <T> Result<T> execute(UserEntity actor, String operation, String scope, UUID key, String requestHash,
                                 Class<T> type, int successStatus, Supplier<T> firstExecution) {
        try {
            entityManager.createNativeQuery("set local lock_timeout = '" + properties.lockTimeout().toMillis() + "ms'")
                    .executeUpdate();
            Instant now = Instant.now();
            records.insertInProgress(actor.getId(), operation, scope, key, requestHash, now.plus(properties.retention()), now);
            var record = records.findForUpdate(actor.getId(), operation, scope, key).orElseThrow(this::inProgress);
            if (!record.getRequestHash().equals(requestHash)) {
                throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT",
                                       "Idempotency key was reused with different input.", "TAR-IDEMPOTENCY-001");
            }
            if (record.getStatus() == IdempotencyStatus.COMPLETED) {
                return new Result<>(json.readValue(record.getResponseBody(), type), record.getResponseStatus(), true);
            }
            T response = firstExecution.get();
            record.complete(successStatus, json.writeValueAsString(response), Instant.now());
            return new Result<>(response, successStatus, false);
        } catch (ApiException | DataIntegrityViolationException exception) {
            throw exception;
        } catch (CannotAcquireLockException exception) {
            throw inProgress();
        } catch (Exception exception) {
            throw new IllegalStateException("Idempotent request processing failed.", exception);
        }
    }

    private ApiException inProgress() {
        return new ApiException(HttpStatus.CONFLICT, "REQUEST_IN_PROGRESS",
                                "A matching request is still being processed.", "TAR-IDEMPOTENCY-002");
    }

    public record Result<T>(T body, int status, boolean replayed) {
    }
}