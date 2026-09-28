package com.tarbank.security.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "users")
public class UserEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    @Column(nullable = false, unique = true, length = 32)
    private String username;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(name = "credential_version", nullable = false)
    private int credentialVersion;

    @Column(name = "first_name", nullable = false, length = 100)
    private String firstName;

    @Column(name = "middle_name", length = 100)
    private String middleName;

    @Column(name = "last_name", nullable = false, length = 100)
    private String lastName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deactivated_at")
    private Instant deactivatedAt;

    protected UserEntity() {
    }

    public UserEntity(Role role,
                      String username,
                      String passwordHash,
                      String firstName,
                      String middleName,
                      String lastName,
                      Instant now) {
        this.role = role;
        this.username = username;
        this.passwordHash = passwordHash;
        this.firstName = firstName;
        this.middleName = middleName;
        this.lastName = lastName;
        this.status = UserStatus.ACTIVE;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public boolean updateNames(String firstName,
                               boolean firstNameSupplied,
                               String middleName,
                               boolean middleNameSupplied,
                               String lastName,
                               boolean lastNameSupplied,
                               Instant now) {
        boolean changed = false;
        if (firstNameSupplied && !java.util.Objects.equals(this.firstName, firstName)) {
            this.firstName = firstName;
            changed = true;
        }
        if (middleNameSupplied && !java.util.Objects.equals(this.middleName, middleName)) {
            this.middleName = middleName;
            changed = true;
        }
        if (lastNameSupplied && !java.util.Objects.equals(this.lastName, lastName)) {
            this.lastName = lastName;
            changed = true;
        }
        if (changed) {
            updatedAt = now;
        }
        return changed;
    }

    public void changeStatus(UserStatus next,
                             Instant now) {
        status = next;
        updatedAt = now;
        if (next == UserStatus.DEACTIVATED) {
            deactivatedAt = now;
        }
    }

    public void resetPassword(String hash,
                              Instant now) {
        passwordHash = hash;
        credentialVersion++;
        updatedAt = now;
    }

    public Long getId() {
        return id;
    }

    public Role getRole() {
        return role;
    }

    public String getUsername() {
        return username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public int getCredentialVersion() {
        return credentialVersion;
    }

    public UserStatus getStatus() {
        return status;
    }

    public String getFirstName() {
        return firstName;
    }

    public String getMiddleName() {
        return middleName;
    }

    public String getLastName() {
        return lastName;
    }
}