package com.tarbank.money.domain;

import com.tarbank.account.domain.AccountEntity;
import com.tarbank.customer.domain.CustomerEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "money_operation_idempotency")
public class MoneyOperationIdempotencyEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", updatable = false)
    private CustomerEntity customer;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", updatable = false)
    private AccountEntity account;

    @Enumerated(EnumType.STRING)
    @Column(name = "operation_type", nullable = false, length = 20, updatable = false)
    private TransactionType operationType;

    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private UUID idempotencyKey;

    @Column(name = "request_hash", nullable = false, length = 64, updatable = false)
    private String requestHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MoneyIdempotencyStatus status;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "transaction_id")
    private MoneyTransactionEntity transaction;

    @Column(name = "response_status")
    private Integer responseStatus;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_body", columnDefinition = "jsonb")
    private String responseBody;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected MoneyOperationIdempotencyEntity() {
    }

    public String getRequestHash() {
        return requestHash;
    }

    public MoneyIdempotencyStatus getStatus() {
        return status;
    }

    public Integer getResponseStatus() {
        return responseStatus;
    }

    public String getResponseBody() {
        return responseBody;
    }

    public void finalizeWith(MoneyTransactionEntity transaction,
                             MoneyIdempotencyStatus status,
                             int responseStatus,
                             String responseBody,
                             Instant now) {
        this.transaction = transaction;
        this.status = status;
        this.responseStatus = responseStatus;
        this.responseBody = responseBody;
        this.updatedAt = now;
    }
}
