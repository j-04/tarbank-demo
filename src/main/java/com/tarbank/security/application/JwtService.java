package com.tarbank.security.application;

import com.tarbank.common.config.JwtProperties;
import com.tarbank.security.domain.Role;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class JwtService {
    private final JwtProperties properties;

    private final JsonMapper jsonMapper;

    public JwtService(JwtProperties properties,
                      JsonMapper jsonMapper) {
        this.properties = properties;
        this.jsonMapper = jsonMapper;
    }

    public IssuedToken issue(Long userId,
                             Role role,
                             int credentialVersion) {
        Instant expires = Instant.now()
                                 .plus(properties.accessTokenTtl());
        UUID jti = UUID.randomUUID();
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", properties.issuer());
        claims.put("sub", String.valueOf(userId));
        claims.put("role", role.name());
        claims.put("jti", jti.toString());
        claims.put("credentialVersion", credentialVersion);
        claims.put("iat", Instant.now()
                                 .getEpochSecond());
        claims.put("exp", expires.getEpochSecond());
        return new IssuedToken(compact(claims), jti, expires);
    }

    public TarbankPrincipal verify(String token) {
        try {
            String[] parts = token.split("\\.", -1);
            if (parts.length != 3 || !constantTime(parts[2], sign(parts[0] + "." + parts[1]))) {
                throw new IllegalArgumentException();
            }
            Map<?, ?> header = jsonMapper.readValue(decode(parts[0]), Map.class);
            if (!"HS256".equals(header.get("alg"))) {
                throw new IllegalArgumentException();
            }
            Map<?, ?> c = jsonMapper.readValue(decode(parts[1]), Map.class);
            if (!properties.issuer()
                           .equals(c.get("iss"))) {
                throw new IllegalArgumentException();
            }
            long exp = number(c.get("exp")).longValue();
            if (Instant.now()
                       .getEpochSecond() >= exp) {
                throw new IllegalArgumentException();
            }
            return new TarbankPrincipal(Long.valueOf(String.valueOf(c.get("sub"))), Role.valueOf(String.valueOf(c.get("role"))), UUID.fromString(String.valueOf(c.get("jti"))), number(c.get("credentialVersion")).intValue(), Instant.ofEpochSecond(exp));
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid access token.");
        }
    }

    private String compact(Map<String, Object> claims) {
        try {
            String h = encode("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");
            String p = encode(jsonMapper.writeValueAsString(claims));
            return h + "." + p + "." + sign(h + "." + p);
        } catch (Exception e) {
            throw new IllegalStateException("Token issuance is unavailable.", e);
        }
    }

    private String sign(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(properties.signingKey()
                                                 .getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder()
                         .withoutPadding()
                         .encodeToString(mac.doFinal(body.getBytes(StandardCharsets.US_ASCII)));
        } catch (Exception e) {
            throw new IllegalStateException("Token signing is unavailable.", e);
        }
    }

    private String encode(String value) {
        return Base64.getUrlEncoder()
                     .withoutPadding()
                     .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private String decode(String value) {
        return new String(Base64.getUrlDecoder()
                                .decode(value), StandardCharsets.UTF_8);
    }

    private boolean constantTime(String a,
                                 String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.US_ASCII), b.getBytes(StandardCharsets.US_ASCII));
    }

    private Number number(Object o) {
        if (o instanceof Number n) {
            return n;
        }
        return Long.valueOf(String.valueOf(o));
    }

    public record IssuedToken(String value, UUID tokenId, Instant expiresAt) {
    }
}