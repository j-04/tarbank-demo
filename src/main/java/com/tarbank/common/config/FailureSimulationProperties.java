package com.tarbank.common.config;

import com.tarbank.common.resilience.FailurePoint;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.Set;

@Validated
@ConfigurationProperties("tarbank.failure-simulator")
public record FailureSimulationProperties(
        boolean enabled,
        @DecimalMin("0.0") @DecimalMax("1.0") double failureRate,
        @NotEmpty Set<FailurePoint> allowedPoints) {
    public FailureSimulationProperties {
        allowedPoints = Set.copyOf(allowedPoints);
    }
}
