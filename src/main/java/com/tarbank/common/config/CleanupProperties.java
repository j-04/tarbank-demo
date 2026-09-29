package com.tarbank.common.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.LocalDate;
import java.time.Period;
import java.time.temporal.ChronoUnit;

@Validated
@ConfigurationProperties("tarbank.cleanup")
public record CleanupProperties(
        boolean enabled,
        @NotBlank String cron,
        @Min(1) int batchSize,
        @NotNull Period usageRetention,
        long advisoryLockId) {
    private static final long MAX_RETENTION_DAYS = 3_650;

    public CleanupProperties {
        if (usageRetention != null) {
            long retentionDays = ChronoUnit.DAYS.between(LocalDate.of(2000, 1, 1),
                                                         LocalDate.of(2000, 1, 1).plus(usageRetention));
            if (retentionDays < 1 || retentionDays > MAX_RETENTION_DAYS) {
                throw new IllegalArgumentException("Cleanup usage retention must be between 1 and 3650 days.");
            }
        }
    }
}
