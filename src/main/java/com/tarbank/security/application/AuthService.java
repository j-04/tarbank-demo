package com.tarbank.security.application;

import com.tarbank.security.domain.UserStatus;
import com.tarbank.security.persistence.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class AuthService {
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
        var user = users.findByUsername(username.trim()
                                                .toLowerCase(java.util.Locale.ROOT))
                        .filter(u -> u.getStatus() == UserStatus.ACTIVE)
                        .filter(u -> passwords.matches(password, u.getPasswordHash()))
                        .filter(profiles::matches)
                        .orElseThrow(() -> new InvalidCredentialsException());
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
