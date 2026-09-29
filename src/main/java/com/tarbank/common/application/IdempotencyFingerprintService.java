package com.tarbank.common.application;

import com.tarbank.common.config.IdempotencyProperties;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

@Service
public class IdempotencyFingerprintService {
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private static final byte[] DOMAIN = "tarbank-idempotency-v1".getBytes(StandardCharsets.UTF_8);

    private final IdempotencyProperties properties;

    public IdempotencyFingerprintService(IdempotencyProperties properties) {
        this.properties = properties;
    }

    public Fingerprints credential(String operation,
                                   String scope,
                                   String credential) {
        return new Fingerprints(hmac(operation, scope, credential.getBytes(StandardCharsets.UTF_8)),
                                legacySha256(credential));
    }

    /**
     * Creates a versioned, domain-separated fingerprint for a canonical request payload. Including
     * the actor and idempotency namespace prevents the same serialized input from producing a
     * reusable verifier in another user's or operation's namespace.
     *
     * @param actorId authenticated actor that owns the idempotency record
     * @param operation stable operation name stored with the record
     * @param scope stable resource scope stored with the record
     * @param canonicalPayload serialized business-significant input in canonical field order
     * @return lowercase hexadecimal HMAC-SHA-256 fingerprint
     */
    public String payload(long actorId,
                          String operation,
                          String scope,
                          byte[] canonicalPayload) {
        return hmac(operation, "actor:" + actorId + ':' + scope, canonicalPayload);
    }

    private String hmac(String operation,
                        String scope,
                        byte[] value) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(
                    properties.requestFingerprintHmacKey()
                              .getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            mac.update(DOMAIN);
            mac.update((byte) 0);
            mac.update(operation.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) 0);
            mac.update(scope.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) 0);
            return HexFormat.of()
                            .formatHex(mac.doFinal(value));
        } catch (Exception exception) {
            throw new IllegalStateException("Idempotency fingerprinting is unavailable.", exception);
        }
    }

    private String legacySha256(String credential) {
        try {
            return HexFormat.of()
                            .formatHex(
                                    MessageDigest.getInstance("SHA-256")
                                                 .digest(credential.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Legacy idempotency fingerprinting is unavailable.", exception);
        }
    }

    public record Fingerprints(String current, String legacy) {
    }
}
