package com.tarbank.customer.persistence;

import com.tarbank.customer.domain.CustomerEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CustomerRepository extends JpaRepository<CustomerEntity, Long> {
    Optional<CustomerEntity> findByDocumentTypeAndDocumentIssuingCountryAndDocumentNumberHash(String type, String country, String hash);
}