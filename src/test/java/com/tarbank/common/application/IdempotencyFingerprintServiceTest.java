package com.tarbank.common.application;

import com.tarbank.common.config.IdempotencyProperties;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

class IdempotencyFingerprintServiceTest {
    private final IdempotencyFingerprintService fingerprints = new IdempotencyFingerprintService(
            new IdempotencyProperties(Duration.ofHours(24), Duration.ofSeconds(5), "h".repeat(32)));

    @Test
    void credentialFingerprintsAreKeyedDeterministicAndDomainSeparated() {
        var first = fingerprints.credential("CUSTOMER_PASSWORD_RESET", "customer:42", "ResetPwd123!");
        var replay = fingerprints.credential("CUSTOMER_PASSWORD_RESET", "customer:42", "ResetPwd123!");
        var anotherCustomer = fingerprints.credential(
                "CUSTOMER_PASSWORD_RESET", "customer:43", "ResetPwd123!");

        assertThat(first.current())
                .isEqualTo(replay.current())
                .isNotEqualTo(first.legacy())
                .isNotEqualTo(anotherCustomer.current());
    }

    @Test
    void payloadFingerprintIsKeyedAndActorScoped() throws Exception {
        byte[] payload = "canonical-password-bearing-payload".getBytes(StandardCharsets.UTF_8);
        String fingerprint = fingerprints.payload(42L, "CUSTOMER_CREATE", "customers", payload);
        String unkeyed = HexFormat.of()
                                  .formatHex(MessageDigest.getInstance("SHA-256").digest(payload));

        assertThat(fingerprint).isNotEqualTo(unkeyed)
                               .isEqualTo(fingerprints.payload(42L, "CUSTOMER_CREATE", "customers", payload))
                               .isNotEqualTo(fingerprints.payload(43L, "CUSTOMER_CREATE", "customers", payload));
    }
}
