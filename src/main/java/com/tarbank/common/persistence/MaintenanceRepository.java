package com.tarbank.common.persistence;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;

@Repository
public class MaintenanceRepository {
    private final EntityManager entityManager;

    public MaintenanceRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public boolean tryAdvisoryLock(long lockId) {
        Object result = entityManager.createNativeQuery("select pg_try_advisory_xact_lock(:lockId)")
                                     .setParameter("lockId", lockId)
                                     .getSingleResult();
        return Boolean.TRUE.equals(result);
    }

    public int deleteExpiredOverrides(Instant now, int batchSize) {
        return entityManager.createNativeQuery("""
                                    delete from account_limit_overrides
                                    where id in (
                                        select id from account_limit_overrides
                                        where expires_at <= :now
                                        order by expires_at, id
                                        limit :batchSize
                                    )
                                    """)
                            .setParameter("now", now)
                            .setParameter("batchSize", batchSize)
                            .executeUpdate();
    }

    public int deleteExpiredMoneyIdempotency(Instant now, int batchSize) {
        return entityManager.createNativeQuery("""
                                    delete from money_operation_idempotency
                                    where id in (
                                        select id from money_operation_idempotency
                                        where expires_at <= :now and status in ('COMPLETED', 'FAILED')
                                        order by expires_at, id
                                        limit :batchSize
                                    )
                                    """)
                            .setParameter("now", now)
                            .setParameter("batchSize", batchSize)
                            .executeUpdate();
    }

    public int deleteExpiredApiIdempotency(Instant now, int batchSize) {
        return entityManager.createNativeQuery("""
                                    delete from api_request_idempotency
                                    where id in (
                                        select id from api_request_idempotency
                                        where expires_at <= :now and status = 'COMPLETED'
                                        order by expires_at, id
                                        limit :batchSize
                                    )
                                    """)
                            .setParameter("now", now)
                            .setParameter("batchSize", batchSize)
                            .executeUpdate();
    }

    public int deleteOldUsage(LocalDate cutoff, int batchSize) {
        return entityManager.createNativeQuery("""
                                    delete from daily_limit_usage
                                    where id in (
                                        select id from daily_limit_usage
                                        where usage_date < :cutoff
                                        order by usage_date, id
                                        limit :batchSize
                                    )
                                    """)
                            .setParameter("cutoff", cutoff)
                            .setParameter("batchSize", batchSize)
                            .executeUpdate();
    }
}
