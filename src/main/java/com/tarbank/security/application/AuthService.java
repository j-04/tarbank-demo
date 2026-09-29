package com.tarbank.security.application;

import com.tarbank.security.domain.UserStatus;
import com.tarbank.security.persistence.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class AuthService {
    private static final String DUMMY_PASSWORD_HASH =
            "$2b$10$VlSPJVuNx8TZRyRpRt3IvOZjqPEdb1n5UEwdWwngEpzWnbVs.xIHa";

    private final UserRepository users;

    private final PasswordEncoder passwords;

    private final JwtService jwt;

    private final RedisSecurityStore redis;

    private final IdentityProfileValidator profiles;

    public AuthService(UserRepository users,
                       PasswordEncoder passwords,
                       JwtService jwt,
                       RedisSecurityStore redis,
                       IdentityProfileValidator profiles) {
        this.users = users;
        this.passwords = passwords;
        this.jwt = jwt;
        this.redis = redis;
        this.profiles = profiles;
    }

    public JwtService.IssuedToken login(String username,
                                        String password) {
        var candidate = users.findByUsername(username.trim()
                                                     .toLowerCase(java.util.Locale.ROOT));
        var activeUser = candidate.filter(user -> user.getStatus() == UserStatus.ACTIVE);
        String passwordHash = activeUser.map(user -> user.getPasswordHash())
                                        .orElse(DUMMY_PASSWORD_HASH);
        boolean passwordMatches = passwords.matches(password, passwordHash);
        var user = activeUser.filter(ignored -> passwordMatches)
                             .filter(profiles::matches)
                             .orElseThrow(InvalidCredentialsException::new);
        return jwt.issue(user.getId(), user.getRole(), user.getCredentialVersion());
    }

    public void logout(TarbankPrincipal principal) {
        Duration remaining = Duration.between(java.time.Instant.now(), principal.expiresAt());
        if (!remaining.isNegative() && !remaining.isZero()) {
            redis.invalidate(principal.tokenId(), remaining);
        }
    }

    public static class InvalidCredentialsException extends RuntimeException {
    }
}
