package com.tarbank.common.config;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties("tarbank.idempotency")
public record IdempotencyProperties(
        @NotNull Duration retention,
        @NotNull Duration lockTimeout,
        String requestFingerprintHmacKey) {
    public IdempotencyProperties {
        requirePositive(retention, "Idempotency retention");
        requirePositive(lockTimeout, "Idempotency lock timeout");
        if (retention != null && lockTimeout != null && lockTimeout.compareTo(retention) >= 0) {
            throw new IllegalArgumentException("Idempotency lock timeout must be shorter than retention.");
        }
    }

    private static void requirePositive(Duration value,
                                        String label) {
        if (value != null && (value.isZero() || value.isNegative())) {
            throw new IllegalArgumentException(label + " must be positive.");
        }
    }
}
