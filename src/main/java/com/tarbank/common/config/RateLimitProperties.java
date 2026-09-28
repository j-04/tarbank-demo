package com.tarbank.common.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("tarbank.rate-limit")
public record RateLimitProperties(
        boolean enabled,
        @Valid @NotNull Policy anonymous,
        @Valid @NotNull Policy loginIp,
        @Valid @NotNull Policy loginUsername,
        @Valid @NotNull Policy authenticated,
        @Valid @NotNull Policy moneyAccount) {

    public record Policy(
            @Min(1) int capacity,
            @DecimalMin(value = "0.0", inclusive = false) double refillTokensPerSecond) {
    }
}
