package com.tarbank.account.application;

import com.tarbank.account.domain.Currency;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.util.Map;

@Validated
@ConfigurationProperties("tarbank.account")
public record AccountProperties(@NotEmpty Map<Currency, @Valid DefaultLimits> defaultLimits) {
    public AccountProperties {
        defaultLimits = defaultLimits == null ? Map.of() : Map.copyOf(defaultLimits);
    }

    public DefaultLimits forCurrency(Currency currency) {
        DefaultLimits limits = defaultLimits.get(currency);
        if (limits == null) {
            throw new IllegalStateException("Default account limits are not configured.");
        }
        return limits;
    }

    public record DefaultLimits(
            @NotNull @DecimalMin("0.0001") @Digits(integer = 15, fraction = 4) BigDecimal withdrawal,
            @NotNull @DecimalMin("0.0001") @Digits(integer = 15, fraction = 4) BigDecimal transfer) {
    }
}
