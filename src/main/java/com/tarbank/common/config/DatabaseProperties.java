package com.tarbank.common.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("tarbank.database")
public record DatabaseProperties(
        @NotBlank String url,
        @NotBlank String username,
        @NotBlank String password) {
}
