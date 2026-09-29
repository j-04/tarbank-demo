package com.tarbank.common.application;

import com.tarbank.common.config.CleanupProperties;
import com.tarbank.common.observability.OperationalMetrics;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class RetentionCleanupJob {
    private static final Logger LOGGER = LogManager.getLogger(RetentionCleanupJob.class);

    private final CleanupProperties properties;

    private final RetentionCleanupService cleanup;

    private final OperationalMetrics metrics;

    RetentionCleanupJob(CleanupProperties properties,
                        RetentionCleanupService cleanup,
                        OperationalMetrics metrics) {
        this.properties = properties;
        this.cleanup = cleanup;
        this.metrics = metrics;
    }

    @Scheduled(cron = "${tarbank.cleanup.cron:0 0 * * * *}", zone = "UTC")
    void run() {
        if (!properties.enabled()) {
            return;
        }
        try {
            RetentionCleanupService.CleanupResult result = cleanup.cleanup();
            LOGGER.info(
                    "Retention cleanup completed lockAcquired={} overrides={} moneyIdempotency={} apiIdempotency={} dailyUsage={}",
                    result.lockAcquired(), result.overrides(), result.moneyIdempotency(), result.apiIdempotency(),
                    result.dailyUsage());
        } catch (Exception exception) {
            metrics.cleanupFailed();
            LOGGER.error("Retention cleanup failed exceptionType={}", exception.getClass()
                                                                               .getName());
        }
    }
}
