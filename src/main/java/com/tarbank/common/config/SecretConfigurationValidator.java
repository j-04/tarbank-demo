package com.tarbank.common.config;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

@Component
class SecretConfigurationValidator {

    private static final int MINIMUM_SECRET_LENGTH = 32;

    private final JwtProperties jwtProperties;

    private final DocumentProtectionProperties documentProtectionProperties;

    private final IdempotencyProperties idempotencyProperties;

    SecretConfigurationValidator(
            JwtProperties jwtProperties,
            DocumentProtectionProperties documentProtectionProperties,
            IdempotencyProperties idempotencyProperties) {
        this.jwtProperties = jwtProperties;
        this.documentProtectionProperties = documentProtectionProperties;
        this.idempotencyProperties = idempotencyProperties;
    }

    @PostConstruct
    void validateSecrets() {
        validateSecret("tarbank.jwt.signing-key", jwtProperties.signingKey());
        validateSecret("tarbank.document-protection.encryption-key", documentProtectionProperties.encryptionKey());
        validateSecret("tarbank.document-protection.lookup-hmac-key", documentProtectionProperties.lookupHmacKey());
        validateSecret("tarbank.idempotency.request-fingerprint-hmac-key",
                       idempotencyProperties.requestFingerprintHmacKey());
    }

    private void validateSecret(String propertyName,
                                String value) {
        if (value == null || value.isBlank() || isUnresolvedPlaceholder(value)) {
            throw new IllegalStateException("Invalid configuration: " + propertyName + " must be configured.");
        }
        if (value.length() < MINIMUM_SECRET_LENGTH) {
            throw new IllegalStateException(
                    "Invalid configuration: " + propertyName + " must contain at least "
                            + MINIMUM_SECRET_LENGTH + " characters.");
        }
    }

    private boolean isUnresolvedPlaceholder(String value) {
        return value.startsWith("${") && value.endsWith("}");
    }
}