package com.tarbank.common.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Period;

@Validated
@ConfigurationProperties("tarbank.cleanup")
public record CleanupProperties(
        boolean enabled,
        @NotBlank String cron,
        @Min(1) int batchSize,
        @NotNull Period usageRetention,
        long advisoryLockId) {
}
