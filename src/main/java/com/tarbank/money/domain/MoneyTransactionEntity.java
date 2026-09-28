package com.tarbank.money.domain;

import com.tarbank.account.domain.AccountEntity;
import com.tarbank.account.domain.Currency;
import com.tarbank.customer.domain.CustomerEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "transactions")
public class MoneyTransactionEntity implements Persistable<UUID> {
    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private TransactionType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private TransactionStatus status;

    @Column(nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3, columnDefinition = "char(3)", updatable = false)
    private Currency currency;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "initiated_by_customer_id", updatable = false)
    private CustomerEntity initiatedByCustomer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_account_id", updatable = false)
    private AccountEntity sourceAccount;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "destination_account_id", updatable = false)
    private AccountEntity destinationAccount;

    @Column(name = "failure_code", length = 100, updatable = false)
    private String failureCode;

    @Column(name = "correlation_id", nullable = false, updatable = false)
    private UUID correlationId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "completed_at", nullable = false, updatable = false)
    private Instant completedAt;

    @Transient
    private boolean newRecord = true;

    protected MoneyTransactionEntity() {
    }

    public MoneyTransactionEntity(UUID id,
                                  TransactionType type,
                                  TransactionStatus status,
                                  BigDecimal amount,
                                  Currency currency,
                                  CustomerEntity initiatedByCustomer,
                                  AccountEntity sourceAccount,
                                  AccountEntity destinationAccount,
                                  String failureCode,
                                  UUID correlationId,
                                  Instant now) {
        this.id = id;
        this.type = type;
        this.status = status;
        this.amount = amount;
        this.currency = currency;
        this.initiatedByCustomer = initiatedByCustomer;
        this.sourceAccount = sourceAccount;
        this.destinationAccount = destinationAccount;
        this.failureCode = failureCode;
        this.correlationId = correlationId;
        this.createdAt = now;
        this.completedAt = now;
    }

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return newRecord;
    }

    @PostLoad
    @PostPersist
    void markPersisted() {
        newRecord = false;
    }
}
