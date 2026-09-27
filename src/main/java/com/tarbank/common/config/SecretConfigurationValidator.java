package com.tarbank.common.config;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

@Component
class SecretConfigurationValidator {

    private static final int MINIMUM_SECRET_LENGTH = 32;

    private final JwtProperties jwtProperties;
    private final DocumentProtectionProperties documentProtectionProperties;

    SecretConfigurationValidator(
            JwtProperties jwtProperties, DocumentProtectionProperties documentProtectionProperties) {
        this.jwtProperties = jwtProperties;
        this.documentProtectionProperties = documentProtectionProperties;
    }

    @PostConstruct
    void validateSecrets() {
        validateSecret("tarbank.jwt.signing-key", jwtProperties.signingKey());
        validateSecret("tarbank.document-protection.encryption-key", documentProtectionProperties.encryptionKey());
        validateSecret("tarbank.document-protection.lookup-hmac-key", documentProtectionProperties.lookupHmacKey());
    }

    private void validateSecret(String propertyName, String value) {
        if (value == null || value.isBlank() || value.length() < MINIMUM_SECRET_LENGTH) {
            throw new IllegalStateException(
                    "Invalid configuration: " + propertyName + " must contain at least "
                            + MINIMUM_SECRET_LENGTH + " characters.");
        }
    }
}