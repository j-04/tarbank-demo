package com.tarbank.money.application;

import com.tarbank.account.application.AccountProperties;
import com.tarbank.account.domain.AccountEntity;
import com.tarbank.account.domain.Currency;
import com.tarbank.account.persistence.AccountFeatureQueryRepository;
import com.tarbank.money.domain.LimitOperationType;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

@Service
public class EffectiveLimitService {
    private final AccountProperties properties;

    private final AccountFeatureQueryRepository queries;

    private final Clock clock;

    public EffectiveLimitService(AccountProperties properties,
                                 AccountFeatureQueryRepository queries,
                                 Clock clock) {
        this.properties = properties;
        this.queries = queries;
        this.clock = clock;
    }

    public EffectiveLimit effectiveLimit(AccountEntity account,
                                         LimitOperationType operation) {
        return effectiveLimit(account, operation, window(account.getCustomer()
                                                                .getTimezone()));
    }

    public EffectiveLimit effectiveLimit(AccountEntity account,
                                         LimitOperationType operation,
                                         LimitWindow window) {
        return queries.findEffectiveOverride(
                              account.getId(), operation, window.effectiveDate(), window.now())
                      .map(override -> new EffectiveLimit(
                              override.getLimitAmount(), override.getExpiresAt()))
                      .orElseGet(() -> new EffectiveLimit(
                              defaultLimit(account.getCurrency(), operation), null));
    }

    public BigDecimal defaultLimit(Currency currency,
                                   LimitOperationType operation) {
        AccountProperties.DefaultLimits limits = properties.forCurrency(currency);
        return operation == LimitOperationType.WITHDRAWAL ? limits.withdrawal() : limits.transfer();
    }

    public LimitWindow window(String timezone) {
        return window(timezone, clock.instant());
    }

    public LimitWindow window(String timezone,
                              Instant now) {
        ZoneId zone = ZoneId.of(timezone);
        LocalDate effectiveDate = LocalDate.ofInstant(now, zone);
        Instant expiresAt = effectiveDate.plusDays(1)
                                         .atStartOfDay(zone)
                                         .toInstant();
        return new LimitWindow(now, effectiveDate, expiresAt);
    }

    public Instant now() {
        return clock.instant();
    }

    public record EffectiveLimit(BigDecimal amount, Instant expiresAt) {
    }

    public record LimitWindow(Instant now, LocalDate effectiveDate, Instant expiresAt) {
    }
}
