package com.tarbank.common.persistence;

import com.tarbank.common.domain.ApiRequestIdempotencyEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface ApiRequestIdempotencyRepository extends JpaRepository<ApiRequestIdempotencyEntity, Long> {
    @Modifying
    @Query(value = "insert into api_request_idempotency (actor_user_id, operation, resource_scope, idempotency_key, request_hash, status, expires_at, created_at, updated_at) values (:actorId, :operation, :scope, :key, :hash, 'IN_PROGRESS', :expiresAt, :now, :now) on conflict (actor_user_id, operation, resource_scope, idempotency_key) do nothing", nativeQuery = true)
    int insertInProgress(@Param("actorId") Long actorId,
                         @Param("operation") String operation,
                         @Param("scope") String scope,
                         @Param("key") UUID key,
                         @Param("hash") String hash,
                         @Param("expiresAt") Instant expiresAt,
                         @Param("now") Instant now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select record from ApiRequestIdempotencyEntity record where record.actor.id=:actorId and record.operation=:operation and record.resourceScope=:scope and record.idempotencyKey=:key")
    Optional<ApiRequestIdempotencyEntity> findForUpdate(@Param("actorId") Long actorId,
                                                        @Param("operation") String operation,
                                                        @Param("scope") String scope,
                                                        @Param("key") UUID key);
}