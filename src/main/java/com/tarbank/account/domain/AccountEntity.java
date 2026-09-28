package com.tarbank.account.domain;

import com.tarbank.customer.domain.CustomerEntity;
import com.tarbank.security.domain.ManagerEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "accounts")
public class AccountEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_number", nullable = false, unique = true, length = 16, updatable = false)
    private String accountNumber;

    @ManyToOne(optional = false)
    @JoinColumn(name = "customer_id", updatable = false)
    private CustomerEntity customer;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3, columnDefinition = "char(3)", updatable = false)
    private Currency currency;

    @Column(nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal balance;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AccountStatus status;

    @ManyToOne
    @JoinColumn(name = "status_changed_by_manager_id")
    private ManagerEntity statusChangedByManager;

    @Column(name = "status_changed_at")
    private Instant statusChangedAt;

    @Version
    @Column(name = "management_version", nullable = false)
    private int managementVersion;

    @ManyToOne(optional = false)
    @JoinColumn(name = "created_by_manager_id", updatable = false)
    private ManagerEntity createdByManager;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deactivated_at")
    private Instant deactivatedAt;

    protected AccountEntity() {
    }

    public AccountEntity(String accountNumber,
                         CustomerEntity customer,
                         Currency currency,
                         ManagerEntity manager,
                         Instant now) {
        this.accountNumber = accountNumber;
        this.customer = customer;
        this.currency = currency;
        this.balance = BigDecimal.ZERO.setScale(4);
        this.status = AccountStatus.ACTIVE;
        this.createdByManager = manager;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void changeStatus(AccountStatus next,
                             ManagerEntity manager,
                             Instant now) {
        status = next;
        statusChangedByManager = manager;
        statusChangedAt = now;
        updatedAt = now;
        if (next == AccountStatus.DEACTIVATED) {
            deactivatedAt = now;
        }
    }

    public void markLimitsChanged(Instant now) {
        updatedAt = updatedAt.isBefore(now) ? now : updatedAt.plusNanos(1_000);
    }

    public Long getId() {
        return id;
    }

    public String getAccountNumber() {
        return accountNumber;
    }

    public CustomerEntity getCustomer() {
        return customer;
    }

    public Currency getCurrency() {
        return currency;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public AccountStatus getStatus() {
        return status;
    }

    public int getManagementVersion() {
        return managementVersion;
    }
}
