package com.tarbank.common.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("tarbank.jwt")
public record JwtProperties(
        @NotBlank String issuer,
        String signingKey,
        @NotNull Duration accessTokenTtl) {
}