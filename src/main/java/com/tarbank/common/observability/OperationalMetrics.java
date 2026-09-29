package com.tarbank.common.observability;

import com.tarbank.money.domain.TransactionType;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class OperationalMetrics {
    private final MeterRegistry registry;

    private final Map<String, AtomicInteger> dependencyStates = new ConcurrentHashMap<>();

    public OperationalMetrics(MeterRegistry registry) {
        this.registry = registry;
        registerDependency("postgres");
        registerDependency("redis");
    }

    public void rateLimitRejected(String group) {
        registry.counter("tarbank.rate_limit.rejections", "group", group)
                .increment();
    }

    public void cleanup(String table,
                        int deleted) {
        registry.counter("tarbank.cleanup.runs", "table", table, "outcome", "success")
                .increment();
        registry.counter("tarbank.cleanup.deleted", "table", table)
                .increment(deleted);
    }

    public void cleanupFailed() {
        registry.counter("tarbank.cleanup.runs", "table", "all", "outcome", "failure")
                .increment();
    }

    public void moneyOutcome(TransactionType type,
                             String outcome) {
        registry.counter("tarbank.money.operations", "type", type.name(), "outcome", outcome)
                .increment();
    }

    public void notification(String outcome) {
        registry.counter("tarbank.notifications", "outcome", outcome)
                .increment();
    }

    public void dependency(String dependency,
                           boolean available) {
        dependencyStates.get(dependency)
                        .set(available ? 1 : 0);
    }

    private void registerDependency(String dependency) {
        AtomicInteger state = new AtomicInteger(0);
        dependencyStates.put(dependency, state);
        Gauge.builder("tarbank.dependency.up", state, AtomicInteger::get)
             .tag("dependency", dependency)
             .register(registry);
    }
}
