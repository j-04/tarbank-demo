package com.tarbank.common.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("tarbank.document-protection")
public record DocumentProtectionProperties(
        @NotBlank @Pattern(regexp = "v[1-9][0-9]*") String keyVersion,
        String encryptionKey,
        String lookupHmacKey) {
}