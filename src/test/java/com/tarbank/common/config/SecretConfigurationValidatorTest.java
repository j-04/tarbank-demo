package com.tarbank.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class SecretConfigurationValidatorTest {

    @Test
    void reportsTheInvalidPropertyWithoutEchoingTheSecret() {
        String shortSecret = "too-short-secret";
        SecretConfigurationValidator validator = new SecretConfigurationValidator(
                new JwtProperties("tarbank", shortSecret, Duration.ofHours(1)),
                new DocumentProtectionProperties("v1", "a".repeat(32), "b".repeat(32)));

        assertThatThrownBy(validator::validateSecrets)
                .satisfies(exception -> assertThat(exception.getMessage())
                        .contains("tarbank.jwt.signing-key")
                        .doesNotContain(shortSecret));
    }

    @Test
    void rejectsAnUnresolvedSecretPlaceholderWithoutEchoingIt() {
        String unresolvedPlaceholder = "${TARBANK_DOCUMENT_ENCRYPTION_KEY}";
        SecretConfigurationValidator validator = new SecretConfigurationValidator(
                new JwtProperties("tarbank", "a".repeat(32), Duration.ofHours(1)),
                new DocumentProtectionProperties("v1", unresolvedPlaceholder, "b".repeat(32)));

        assertThatThrownBy(validator::validateSecrets)
                .satisfies(exception -> assertThat(exception.getMessage())
                        .contains("tarbank.document-protection.encryption-key")
                        .doesNotContain(unresolvedPlaceholder));
    }
}