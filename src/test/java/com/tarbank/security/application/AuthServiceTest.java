package com.tarbank.security.application;

import com.tarbank.security.domain.Role;
import com.tarbank.security.domain.UserEntity;
import com.tarbank.security.domain.UserStatus;
import com.tarbank.security.persistence.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthServiceTest {
    private static final String DUMMY_HASH =
            "$2b$10$VlSPJVuNx8TZRyRpRt3IvOZjqPEdb1n5UEwdWwngEpzWnbVs.xIHa";

    private final UserRepository users = mock(UserRepository.class);

    private final PasswordEncoder passwords = mock(PasswordEncoder.class);

    private final JwtService jwt = mock(JwtService.class);

    private final RedisSecurityStore redis = mock(RedisSecurityStore.class);

    private final IdentityProfileValidator profiles = mock(IdentityProfileValidator.class);

    private final AuthService service = new AuthService(users, passwords, jwt, redis, profiles);

    @Test
    void unknownUsernameStillPerformsOnePasswordVerification() {
        when(users.findByUsername("unknown")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.login("unknown", "WrongPass123"))
                .isInstanceOf(AuthService.InvalidCredentialsException.class);

        verify(passwords).matches("WrongPass123", DUMMY_HASH);
        verify(profiles, never()).matches(any());
    }

    @Test
    void inactiveUsernameUsesTheDummyVerifierExactlyOnce() {
        UserEntity user = user("inactive", "stored-hash");
        user.changeStatus(UserStatus.BLOCKED, Instant.parse("2026-01-02T00:00:00Z"));
        when(users.findByUsername("inactive")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.login("inactive", "WrongPass123"))
                .isInstanceOf(AuthService.InvalidCredentialsException.class);

        verify(passwords).matches("WrongPass123", DUMMY_HASH);
        verify(passwords, never()).matches("WrongPass123", "stored-hash");
        verify(profiles, never()).matches(user);
    }

    @Test
    void wrongPasswordForActiveUserPerformsOneRealVerification() {
        UserEntity user = user("active", "stored-hash");
        when(users.findByUsername("active")).thenReturn(Optional.of(user));
        when(passwords.matches("WrongPass123", "stored-hash")).thenReturn(false);

        assertThatThrownBy(() -> service.login("active", "WrongPass123"))
                .isInstanceOf(AuthService.InvalidCredentialsException.class);

        verify(passwords).matches("WrongPass123", "stored-hash");
        verify(profiles, never()).matches(user);
    }

    private UserEntity user(String username,
                            String passwordHash) {
        return new UserEntity(Role.CUSTOMER, username, passwordHash, "Test", null, "Customer",
                              Instant.parse("2026-01-01T00:00:00Z"));
    }
}
