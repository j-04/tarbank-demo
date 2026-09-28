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
}