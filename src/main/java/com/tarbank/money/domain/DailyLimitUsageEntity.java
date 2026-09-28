package com.tarbank.money.domain;

import com.tarbank.account.domain.AccountEntity;
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
@Table(name = "daily_limit_usage")
public class DailyLimitUsageEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", updatable = false)
    private AccountEntity account;

    @Enumerated(EnumType.STRING)
    @Column(name = "operation_type", nullable = false, length = 20, updatable = false)
    private LimitOperationType operationType;

    @Column(name = "usage_date", nullable = false, updatable = false)
    private LocalDate usageDate;

    @Column(name = "used_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal usedAmount;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected DailyLimitUsageEntity() {
    }

    public Long getId() {
        return id;
    }

    public BigDecimal getUsedAmount() {
        return usedAmount;
    }

    public void consume(BigDecimal amount,
                        Instant now) {
        usedAmount = usedAmount.add(amount);
        updatedAt = now;
    }
}
