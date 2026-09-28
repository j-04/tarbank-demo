package com.tarbank.account.persistence;

import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.Projections;
import com.querydsl.jpa.impl.JPAQueryFactory;
import com.tarbank.account.domain.AccountLimitOverrideEntity;
import com.tarbank.account.domain.Currency;
import com.tarbank.account.domain.QAccountLimitOverrideEntity;
import com.tarbank.money.domain.LimitOperationType;
import com.tarbank.money.domain.QMoneyTransactionEntity;
import com.tarbank.money.domain.QTransactionEntryEntity;
import com.tarbank.money.domain.TransactionStatus;
import com.tarbank.money.domain.TransactionType;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class AccountFeatureQueryRepository {
    private final JPAQueryFactory queries;

    public AccountFeatureQueryRepository(JPAQueryFactory queries) {
        this.queries = queries;
    }

    public Optional<AccountLimitOverrideEntity> findOverride(Long accountId,
                                                             LimitOperationType operation,
                                                             LocalDate effectiveDate) {
        QAccountLimitOverrideEntity override = QAccountLimitOverrideEntity.accountLimitOverrideEntity;
        return Optional.ofNullable(
                queries.selectFrom(override)
                       .where(override.account.id.eq(accountId),
                              override.operationType.eq(operation),
                              override.effectiveDate.eq(effectiveDate))
                       .fetchOne());
    }

    public Optional<AccountLimitOverrideEntity> findEffectiveOverride(Long accountId,
                                                                      LimitOperationType operation,
                                                                      LocalDate effectiveDate,
                                                                      Instant now) {
        QAccountLimitOverrideEntity override = QAccountLimitOverrideEntity.accountLimitOverrideEntity;
        return Optional.ofNullable(
                queries.selectFrom(override)
                       .where(override.account.id.eq(accountId),
                              override.operationType.eq(operation),
                              override.effectiveDate.eq(effectiveDate),
                              override.expiresAt.gt(now))
                       .fetchOne());
    }

    public List<HistoryRow> findHistory(Long accountId,
                                        Instant from,
                                        Instant to,
                                        HistoryPosition position,
                                        int limit) {
        QTransactionEntryEntity entry = QTransactionEntryEntity.transactionEntryEntity;
        QMoneyTransactionEntity transaction = QMoneyTransactionEntity.moneyTransactionEntity;
        BooleanBuilder predicate = new BooleanBuilder(entry.account.id.eq(accountId));
        if (from != null) {
            predicate.and(entry.createdAt.goe(from));
        }
        if (to != null) {
            predicate.and(entry.createdAt.lt(to));
        }
        if (position != null) {
            predicate.and(entry.createdAt.lt(position.createdAt())
                                         .or(entry.createdAt.eq(position.createdAt())
                                                            .and(entry.id.lt(position.id()))));
        }
        return queries.select(Projections.constructor(
                              HistoryRow.class, entry.id, transaction.id, transaction.type,
                              transaction.status, entry.amountDelta, entry.balanceAfter,
                              transaction.currency, entry.createdAt))
                      .from(entry)
                      .join(entry.transaction, transaction)
                      .where(predicate)
                      .orderBy(entry.createdAt.desc(), entry.id.desc())
                      .limit(limit)
                      .fetch();
    }

    public record HistoryPosition(Instant createdAt, Long id) {
    }

    public record HistoryRow(Long entryId, UUID transactionId, TransactionType type,
                             TransactionStatus status, BigDecimal amountDelta,
                             BigDecimal balanceAfter, Currency currency, Instant createdAt) {
    }
}