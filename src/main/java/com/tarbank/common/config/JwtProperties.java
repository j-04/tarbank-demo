package com.tarbank.common.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties("tarbank.jwt")
public record JwtProperties(
        @NotBlank String issuer,
        String signingKey,
        @NotNull Duration accessTokenTtl) {
    public JwtProperties {
        if (accessTokenTtl != null && (accessTokenTtl.isZero() || accessTokenTtl.isNegative())) {
            throw new IllegalArgumentException("JWT access token TTL must be positive.");
        }
    }
}
