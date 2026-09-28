package com.tarbank.money.persistence;

import com.tarbank.money.domain.MoneyOperationIdempotencyEntity;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

public interface MoneyOperationIdempotencyRepository
        extends Repository<MoneyOperationIdempotencyEntity, Long> {
    @Query(value = "select set_config('lock_timeout', :timeout, true)", nativeQuery = true)
    String setLocalLockTimeout(@Param("timeout") String timeout);

    @Modifying
    @Query(value = """
            insert into money_operation_idempotency
                (customer_id, account_id, operation_type, idempotency_key, request_hash, status,
                 expires_at, created_at, updated_at)
            values (:customerId, :accountId, :operationType, :key, :requestHash, 'IN_PROGRESS',
                    :expiresAt, :now, :now)
            on conflict (customer_id, account_id, operation_type, idempotency_key) do nothing
            """, nativeQuery = true)
    int insertInProgress(@Param("customerId") Long customerId,
                         @Param("accountId") Long accountId,
                         @Param("operationType") String operationType,
                         @Param("key") UUID key,
                         @Param("requestHash") String requestHash,
                         @Param("expiresAt") Instant expiresAt,
                         @Param("now") Instant now);
}
