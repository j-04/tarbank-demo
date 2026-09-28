package com.tarbank.money.application;

import com.tarbank.money.domain.TransactionStatus;
import com.tarbank.money.domain.TransactionType;

import java.util.UUID;

public interface NotificationService {
    void send(Outcome outcome);

    record Outcome(UUID transactionId, TransactionType type, TransactionStatus status, String failureCode) {
    }
}
