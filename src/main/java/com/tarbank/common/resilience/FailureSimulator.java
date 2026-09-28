package com.tarbank.common.resilience;

import com.tarbank.money.domain.TransactionType;

public interface FailureSimulator {
    Execution select(TransactionType operationType);

    record Execution(FailurePoint selectedPoint) {
        private static final Execution NONE = new Execution(null);

        public static Execution none() {
            return NONE;
        }

        public static Execution at(FailurePoint point) {
            if (point == null) throw new IllegalArgumentException("Failure point is required.");
            return new Execution(point);
        }

        public void inject(FailurePoint point) {
            if (selectedPoint == point) throw new SimulatedFailureException(point);
        }
    }
}
