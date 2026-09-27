package com.tarbank.common.http;

import java.util.UUID;

public final class CorrelationIdContext {

    private static final ThreadLocal<UUID> CORRELATION_ID = new ThreadLocal<>();

    private CorrelationIdContext() {
    }

    public static UUID current() {
        return CORRELATION_ID.get();
    }

    static void set(UUID correlationId) {
        CORRELATION_ID.set(correlationId);
    }

    static void clear() {
        CORRELATION_ID.remove();
    }
}
