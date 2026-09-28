package com.tarbank.common.resilience;

import com.tarbank.common.config.FailureSimulationProperties;
import com.tarbank.money.domain.TransactionType;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@Component
class DemoFailureSimulator implements FailureSimulator {
    private final FailureSimulationProperties properties;

    DemoFailureSimulator(FailureSimulationProperties properties) {
        this.properties = properties;
    }

    @Override
    public Execution select(TransactionType operationType) {
        if (!properties.enabled()
                || ThreadLocalRandom.current().nextDouble() >= properties.failureRate()) {
            return Execution.none();
        }
        List<FailurePoint> points = List.copyOf(properties.allowedPoints());
        return Execution.at(points.get(ThreadLocalRandom.current().nextInt(points.size())));
    }
}
