package com.tarbank.account.domain;

import com.tarbank.money.domain.LimitOperationType;
import com.tarbank.security.domain.UserEntity;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "account_limit_overrides")
public class AccountLimitOverrideEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", updatable = false)
    private AccountEntity account;

    @Enumerated(EnumType.STRING)
    @Column(name = "operation_type", nullable = false, length = 20, updatable = false)
    private LimitOperationType operationType;

    @Column(name = "limit_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal limitAmount;

    @Column(name = "effective_date", nullable = false, updatable = false)
    private LocalDate effectiveDate;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "updated_by_user_id")
    private UserEntity updatedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AccountLimitOverrideEntity() {
    }

    public AccountLimitOverrideEntity(AccountEntity account,
                                      LimitOperationType operationType,
                                      BigDecimal limitAmount,
                                      LocalDate effectiveDate,
                                      Instant expiresAt,
                                      UserEntity updatedBy,
                                      Instant now) {
        this.account = account;
        this.operationType = operationType;
        this.limitAmount = limitAmount;
        this.effectiveDate = effectiveDate;
        this.expiresAt = expiresAt;
        this.updatedBy = updatedBy;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void increaseTo(BigDecimal amount,
                           Instant expiration,
                           UserEntity actor,
                           Instant now) {
        limitAmount = amount;
        expiresAt = expiration;
        updatedBy = actor;
        updatedAt = now;
    }

    public BigDecimal getLimitAmount() {
        return limitAmount;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}