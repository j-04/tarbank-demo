package com.tarbank.security.application;

import com.tarbank.common.config.ManagerSeedProperties;
import com.tarbank.security.domain.ManagerEntity;
import com.tarbank.security.domain.Role;
import com.tarbank.security.domain.UserEntity;
import com.tarbank.security.persistence.ManagerRepository;
import com.tarbank.security.persistence.UserRepository;
import java.time.Instant;
import java.util.Locale;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ManagerSeeder {
    private final UserRepository users;
    private final ManagerRepository managers;
    private final PasswordEncoder encoder;
    private final PasswordPolicy passwordPolicy;

    public ManagerSeeder(UserRepository users, ManagerRepository managers, PasswordEncoder encoder,
            PasswordPolicy passwordPolicy) {
        this.users = users;
        this.managers = managers;
        this.encoder = encoder;
        this.passwordPolicy = passwordPolicy;
    }

    @Transactional
    public void seed(ManagerSeedProperties properties) {
        for (var seed : properties.managers()) {
            String username = seed.username().trim().toLowerCase(Locale.ROOT);
            UserEntity user = users.findByUsername(username).orElse(null);
            if (user == null) {
                if (!passwordPolicy.isValid(seed.password(), username)) {
                    throw new IllegalStateException("Configured manager credentials do not meet the password policy.");
                }
                user = users.save(new UserEntity(Role.MANAGER, username, encoder.encode(seed.password()),
                        seed.firstName().trim(), null, seed.lastName().trim(), Instant.now()));
            }
            if (user.getRole() != Role.MANAGER) {
                throw new IllegalStateException("Configured manager username belongs to a non-manager.");
            }
            if (!managers.existsById(user.getId())) {
                managers.save(new ManagerEntity(user));
            }
        }
    }
}