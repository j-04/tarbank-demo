package com.tarbank.money.persistence;

import com.querydsl.core.types.Projections;
import com.querydsl.jpa.impl.JPAQueryFactory;
import com.tarbank.account.domain.AccountEntity;
import com.tarbank.account.domain.QAccountEntity;
import com.tarbank.money.domain.DailyLimitUsageEntity;
import com.tarbank.money.domain.LimitOperationType;
import com.tarbank.money.domain.MoneyOperationIdempotencyEntity;
import com.tarbank.money.domain.QDailyLimitUsageEntity;
import com.tarbank.money.domain.QMoneyOperationIdempotencyEntity;
import com.tarbank.money.domain.TransactionType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class MoneyQueryRepository {
    private final JPAQueryFactory queries;

    private final EntityManager entityManager;

    public MoneyQueryRepository(JPAQueryFactory queries,
                                EntityManager entityManager) {
        this.queries = queries;
        this.entityManager = entityManager;
    }

    public Optional<AccountScope> findAccountScope(String accountNumber) {
        QAccountEntity account = QAccountEntity.accountEntity;
        return Optional.ofNullable(
                queries.select(Projections.constructor(
                                       AccountScope.class, account.id, account.customer.userId,
                                       account.accountNumber))
                       .from(account)
                       .where(account.accountNumber.eq(accountNumber))
                       .fetchOne());
    }

    public Optional<Long> findAccountId(String accountNumber) {
        QAccountEntity account = QAccountEntity.accountEntity;
        return Optional.ofNullable(
                queries.select(account.id)
                       .from(account)
                       .where(account.accountNumber.eq(accountNumber))
                       .fetchOne());
    }

    public Optional<AccountEntity> lockAccount(Long accountId) {
        QAccountEntity account = QAccountEntity.accountEntity;
        AccountEntity locked = queries.selectFrom(account)
                                      .join(account.customer)
                                      .fetchJoin()
                                      .where(account.id.eq(accountId))
                                      .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                                      .fetchOne();
        if (locked != null) entityManager.refresh(locked);
        return Optional.ofNullable(locked);
    }

    public List<AccountEntity> lockAccounts(Long firstId,
                                            Long secondId) {
        QAccountEntity account = QAccountEntity.accountEntity;
        List<AccountEntity> locked = queries.selectFrom(account)
                                            .join(account.customer)
                                            .fetchJoin()
                                            .where(account.id.in(firstId, secondId))
                                            .orderBy(account.id.asc())
                                            .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                                            .fetch();
        locked.forEach(entityManager::refresh);
        return locked;
    }

    public Optional<MoneyOperationIdempotencyEntity> lockIdempotency(Long customerId,
                                                                     Long accountId,
                                                                     TransactionType type,
                                                                     UUID key) {
        QMoneyOperationIdempotencyEntity record =
                QMoneyOperationIdempotencyEntity.moneyOperationIdempotencyEntity;
        return Optional.ofNullable(
                queries.selectFrom(record)
                       .where(record.customer.userId.eq(customerId),
                              record.account.id.eq(accountId),
                              record.operationType.eq(type),
                              record.idempotencyKey.eq(key))
                       .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                       .fetchOne());
    }

    public Optional<DailyLimitUsageEntity> lockUsage(Long accountId,
                                                     LimitOperationType operation,
                                                     LocalDate usageDate) {
        QDailyLimitUsageEntity usage = QDailyLimitUsageEntity.dailyLimitUsageEntity;
        return Optional.ofNullable(
                queries.selectFrom(usage)
                       .where(usage.account.id.eq(accountId),
                              usage.operationType.eq(operation),
                              usage.usageDate.eq(usageDate))
                       .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                       .fetchOne());
    }

    public void updateBalance(Long accountId,
                              BigDecimal balance,
                              Instant now) {
        QAccountEntity account = QAccountEntity.accountEntity;
        long updated = queries.update(account)
                              .set(account.balance, balance)
                              .set(account.updatedAt, now)
                              .where(account.id.eq(accountId))
                              .execute();
        if (updated != 1) throw new IllegalStateException("Locked account could not be updated.");
    }

    public void detach(Object entity) {
        entityManager.detach(entity);
    }

    public record AccountScope(Long id, Long customerId, String accountNumber) {
    }
}
