package com.tarbank.common.application;

import com.tarbank.common.config.CleanupProperties;
import com.tarbank.common.observability.OperationalMetrics;
import com.tarbank.common.persistence.MaintenanceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

@Service
public class RetentionCleanupService {
    private final CleanupProperties properties;
    private final MaintenanceRepository maintenance;
    private final OperationalMetrics metrics;
    private final Clock clock;

    public RetentionCleanupService(CleanupProperties properties,
                                   MaintenanceRepository maintenance,
                                   OperationalMetrics metrics,
                                   Clock clock) {
        this.properties = properties;
        this.maintenance = maintenance;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Transactional
    public CleanupResult cleanup() {
        if (!maintenance.tryAdvisoryLock(properties.advisoryLockId())) {
            return CleanupResult.skipped();
        }
        Instant now = clock.instant();
        LocalDate usageCutoff = LocalDate.ofInstant(now, ZoneOffset.UTC)
                                         .minus(properties.usageRetention());
        int overrides = maintenance.deleteExpiredOverrides(now, properties.batchSize());
        int moneyIdempotency = maintenance.deleteExpiredMoneyIdempotency(now, properties.batchSize());
        int apiIdempotency = maintenance.deleteExpiredApiIdempotency(now, properties.batchSize());
        int usage = maintenance.deleteOldUsage(usageCutoff, properties.batchSize());
        metrics.cleanup("account_limit_overrides", overrides);
        metrics.cleanup("money_operation_idempotency", moneyIdempotency);
        metrics.cleanup("api_request_idempotency", apiIdempotency);
        metrics.cleanup("daily_limit_usage", usage);
        return new CleanupResult(true, overrides, moneyIdempotency, apiIdempotency, usage);
    }

    public record CleanupResult(boolean lockAcquired,
                                int overrides,
                                int moneyIdempotency,
                                int apiIdempotency,
                                int dailyUsage) {
        private static CleanupResult skipped() {
            return new CleanupResult(false, 0, 0, 0, 0);
        }
    }
}
