package com.tarbank.money.application;

import org.apache.logging.log4j.CloseableThreadContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
class LoggingNotificationService implements NotificationService {
    private static final Logger LOGGER = LogManager.getLogger(LoggingNotificationService.class);

    @Override
    public void send(Outcome outcome) {
        try (CloseableThreadContext.Instance ignored = CloseableThreadContext.putAll(Map.of(
                "operationType", outcome.type().name(),
                "outcome", "notification_sent"))) {
            LOGGER.info("Money operation notification status={} failureCode={}",
                        outcome.status(), outcome.failureCode());
        }
    }
}
