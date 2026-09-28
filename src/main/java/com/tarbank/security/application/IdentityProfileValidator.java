package com.tarbank.security.application;

import com.tarbank.customer.persistence.CustomerRepository;
import com.tarbank.security.domain.UserEntity;
import com.tarbank.security.persistence.ManagerRepository;
import org.springframework.stereotype.Service;

@Service
public class IdentityProfileValidator {
    private final ManagerRepository managers;

    private final CustomerRepository customers;

    public IdentityProfileValidator(ManagerRepository managers,
                                    CustomerRepository customers) {
        this.managers = managers;
        this.customers = customers;
    }

    public boolean matches(UserEntity user) {
        boolean manager = managers.existsById(user.getId()), customer = customers.existsById(user.getId());
        return switch (user.getRole()) {
            case MANAGER -> manager && !customer;
            case CUSTOMER -> customer && !manager;
        };
    }
}