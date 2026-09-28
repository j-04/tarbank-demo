package com.tarbank.common.resilience;

public enum FailurePoint {
    BEFORE_TRANSACTION,
    DURING_TRANSACTION_BEFORE_COMMIT,
    AFTER_COMMIT_BEFORE_RESPONSE
}
