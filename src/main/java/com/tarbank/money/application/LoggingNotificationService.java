package com.tarbank.money.application;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

@Component
class LoggingNotificationService implements NotificationService {
    private static final Logger LOGGER = LogManager.getLogger(LoggingNotificationService.class);

    @Override
    public void send(Outcome outcome) {
        LOGGER.info("moneyOperationNotification transactionId={} type={} status={} failureCode={}",
                    outcome.transactionId(), outcome.type(), outcome.status(), outcome.failureCode());
    }
}
