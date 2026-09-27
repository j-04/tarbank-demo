package com.tarbank.security.application;

import com.tarbank.security.domain.Role;

import java.time.Instant;
import java.util.UUID;

public record TarbankPrincipal(Long userId, Role role, UUID tokenId, int credentialVersion, Instant expiresAt) {
}