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

    public String normalize(String raw) {
        if (raw == null) throw new IllegalArgumentException("Document number is required.");
        String normalized = Normalizer.normalize(raw, Normalizer.Form.NFKC)
                                      .replaceAll("[^\\p{Alnum}]", "")
                                      .toUpperCase(java.util.Locale.ROOT);
        if (normalized.isBlank()) throw new IllegalArgumentException("Document number is required.");
        return normalized;
    }

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

    public byte[] encrypt(String normalized) {
        try {
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(MessageDigest.getInstance("SHA-256")
                                                                            .digest(properties.encryptionKey()
                                                                                              .getBytes(StandardCharsets.UTF_8)), "AES"), new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(normalized.getBytes(StandardCharsets.UTF_8));
            return (properties.keyVersion() + ":" + Base64.getUrlEncoder()
                                                          .withoutPadding()
                                                          .encodeToString(iv) + ":" + Base64.getUrlEncoder()
                                                                                            .withoutPadding()
                                                                                            .encodeToString(encrypted)).getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Document encryption is unavailable.", e);
        }
    }
}