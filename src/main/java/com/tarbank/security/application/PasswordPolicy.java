package com.tarbank.security.application;

import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
public class PasswordPolicy {
    public boolean isValid(String password,
                           String username) {
        return password != null
                && password.matches("^[\\x20-\\x7E]{12}$")
                && username != null
                && !password.equalsIgnoreCase(username.toLowerCase(Locale.ROOT));
    }
}