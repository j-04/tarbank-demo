package com.tarbank.common.config;

import com.tarbank.account.application.AccountProperties;
import com.tarbank.account.domain.Currency;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Period;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigurationPropertiesValidationTest {

    @Test
    void rejectsNonPositiveSecurityDurations() {
        assertThatThrownBy(() -> new JwtProperties("tarbank", "secret", Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("JWT access token TTL must be positive.");
        assertThatThrownBy(() -> new IdempotencyProperties(Duration.ofHours(1), Duration.ofHours(1), "secret"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Idempotency lock timeout must be shorter than retention.");
    }

    @Test
    void rejectsCleanupRetentionThatCouldDeleteCurrentDayUsage() {
        assertThatThrownBy(() -> new CleanupProperties(true, "0 0 * * * *", 100, Period.ZERO, 42L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 1 and 3650 days");
    }

    @Test
    void requiresDefaultLimitsForEverySupportedCurrency() {
        AccountProperties.DefaultLimits limits = new AccountProperties.DefaultLimits(
                new BigDecimal("1000.0000"), new BigDecimal("1000.0000"));

        assertThatThrownBy(() -> new AccountProperties(Map.of(Currency.EUR, limits)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("every supported currency");
    }

    @Test
    void validatesManagerUsernamePasswordAndNamesBeforeSeeding() {
        ManagerSeedProperties properties = new ManagerSeedProperties(List.of(
                new ManagerSeedProperties.Manager("invalid username", "short", " ", "Manager")));

        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(properties))
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .contains("managers[0].username", "managers[0].password", "managers[0].firstName");
        }
    }
}
