package com.tarbank.customer.persistence;

import com.tarbank.customer.domain.CustomerEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface CustomerRepository extends JpaRepository<CustomerEntity, Long> {
    Optional<CustomerEntity> findByDocumentTypeAndDocumentIssuingCountryAndDocumentNumberHash(
            String type,
            String country,
            String hash);

    @Query(value = "select user_id from customers where user_id = :id for update", nativeQuery = true)
    Optional<Long> lockRowById(@Param("id") Long id);

    @Modifying
    @Query(value = "update customers set version = version + 1 where user_id = :id and version = :expectedVersion",
            nativeQuery = true)
    int incrementVersionIfCurrent(@Param("id") Long id,
                                  @Param("expectedVersion") int expectedVersion);
}
