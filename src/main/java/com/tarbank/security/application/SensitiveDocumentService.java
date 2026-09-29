package com.tarbank.security.application;

import com.tarbank.common.config.DocumentProtectionProperties;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.Normalizer;
import java.util.Base64;

@Service
public class SensitiveDocumentService {
    private static final SecureRandom RANDOM = new SecureRandom();

    private final DocumentProtectionProperties properties;

    public SensitiveDocumentService(DocumentProtectionProperties properties) {
        this.properties = properties;
    }

    /**
     * Produces the canonical representation used by both lookup protection and encryption.
     *
     * @param raw document number supplied during onboarding
     * @return Unicode-normalized uppercase alphanumeric value with separators removed
     */
    public String normalize(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("Document number is required.");
        }
        String normalized = Normalizer.normalize(raw, Normalizer.Form.NFKC)
                                      .replaceAll("[^\\p{Alnum}]", "")
                                      .toUpperCase(java.util.Locale.ROOT);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("Document number is required.");
        }
        return normalized;
    }

    /**
     * Creates a deterministic keyed fingerprint for uniqueness checks. HMAC prevents an attacker
     * with a database copy from cheaply enumerating likely document numbers.
     *
     * @param normalized canonical document number returned by {@link #normalize(String)}
     * @return lowercase hexadecimal HMAC-SHA-256 lookup value
     */
    public String lookupHash(String normalized) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(properties.lookupHmacKey()
                                                 .getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return java.util.HexFormat.of()
                                      .formatHex(mac.doFinal(normalized.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Document lookup protection is unavailable.", e);
        }
    }

    /**
     * Encrypts a normalized document number with AES-GCM and a fresh IV. The serialized envelope
     * carries the key version and IV so a future key-rotation process can identify the right key.
     *
     * @param normalized canonical document number returned by {@link #normalize(String)}
     * @return UTF-8 bytes containing {@code keyVersion:base64url(iv):base64url(ciphertextAndTag)}
     */
    public byte[] encrypt(String normalized) {
        try {
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(MessageDigest.getInstance("SHA-256")
                                                                            .digest(properties.encryptionKey()
                                                                                              .getBytes(
                                                                                                      StandardCharsets.UTF_8)),
                                                               "AES"), new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(normalized.getBytes(StandardCharsets.UTF_8));
            return (properties.keyVersion() + ":" + Base64.getUrlEncoder()
                                                          .withoutPadding()
                                                          .encodeToString(iv) + ":" + Base64.getUrlEncoder()
                                                                                            .withoutPadding()
                                                                                            .encodeToString(
                                                                                                    encrypted)).getBytes(
                    StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Document encryption is unavailable.", e);
        }
    }
}
