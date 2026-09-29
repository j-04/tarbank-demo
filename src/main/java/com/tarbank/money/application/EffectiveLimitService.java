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

    /**
     * Resolves the limit applicable at the current customer-local day. A non-expired override wins;
     * otherwise the configured currency default is returned.
     *
     * @param account account whose currency and override are evaluated
     * @param operation withdrawal or transfer limit to resolve
     * @return effective amount and the instant at which that decision expires
     */
    public EffectiveLimit effectiveLimit(AccountEntity account,
                                         LimitOperationType operation) {
        return effectiveLimit(account, operation, window(account.getCustomer()
                                                                .getTimezone()));
    }

    /**
     * Resolves the limit using a caller-supplied window so limit lookup and usage accounting share
     * the same instant and effective local date inside a money transaction.
     *
     * @param account account whose currency and override are evaluated
     * @param operation withdrawal or transfer limit to resolve
     * @param window time window already calculated for the operation
     * @return effective amount and the instant at which that decision expires
     */
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

    /**
     * Converts a UTC instant into the customer's local banking day. The returned expiry is the
     * next local midnight converted back to an instant, including daylight-saving transitions.
     *
     * @param timezone IANA timezone stored on the customer profile
     * @param now instant at which the limit decision is evaluated
     * @return current instant, effective local date, and next-local-midnight expiry
     */
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
