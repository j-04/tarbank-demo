package com.tarbank.money.domain;

import com.tarbank.account.domain.AccountEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "transaction_entries")
public class TransactionEntryEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "transaction_id", updatable = false)
    private MoneyTransactionEntity transaction;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", updatable = false)
    private AccountEntity account;

    @Column(name = "amount_delta", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal amountDelta;

    @Column(name = "balance_after", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal balanceAfter;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected TransactionEntryEntity() {
    }

    public TransactionEntryEntity(MoneyTransactionEntity transaction,
                                  AccountEntity account,
                                  BigDecimal amountDelta,
                                  BigDecimal balanceAfter,
                                  Instant createdAt) {
        this.transaction = transaction;
        this.account = account;
        this.amountDelta = amountDelta;
        this.balanceAfter = balanceAfter;
        this.createdAt = createdAt;
    }
}
