package com.tarbank.common.resilience;

public final class SimulatedFailureException extends RuntimeException {
    private final FailurePoint point;

    public SimulatedFailureException(FailurePoint point) {
        super("Configured demo failure was injected.");
        this.point = point;
    }

    public FailurePoint getPoint() {
        return point;
    }
}
