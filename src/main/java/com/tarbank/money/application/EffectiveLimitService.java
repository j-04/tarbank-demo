package com.tarbank.money.application;

import com.tarbank.account.application.AccountProperties;
import com.tarbank.account.domain.Currency;
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

    private final Clock clock;

    public EffectiveLimitService(AccountProperties properties,
                                 Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public BigDecimal defaultLimit(Currency currency,
                                   LimitOperationType operation) {
        AccountProperties.DefaultLimits limits = properties.forCurrency(currency);
        return operation == LimitOperationType.WITHDRAWAL ? limits.withdrawal() : limits.transfer();
    }

    public LocalDate usageDate(String timezone) {
        return LocalDate.ofInstant(clock.instant(), ZoneId.of(timezone));
    }

    public Instant now() {
        return clock.instant();
    }
}
