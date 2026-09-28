package com.tarbank.account.application;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
public class AccountNumberGenerator {
    private static final long MAX_NUMERIC_PORTION = 99_999_999_999_999L;

    private final EntityManager entityManager;

    public AccountNumberGenerator(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public String next() {
        Number reserved = (Number) entityManager.createNativeQuery(
                                                        "select nextval('account_number_sequence')")
                                                .getSingleResult();
        long value = reserved.longValue();
        if (value < 1 || value > MAX_NUMERIC_PORTION) {
            throw new IllegalStateException("Account-number sequence is exhausted.");
        }
        return "TB" + String.format(Locale.ROOT, "%014d", value);
    }
}
