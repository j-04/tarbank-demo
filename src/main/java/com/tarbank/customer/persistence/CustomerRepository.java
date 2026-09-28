package com.tarbank.customer.persistence;

import com.tarbank.customer.domain.CustomerEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface CustomerRepository extends JpaRepository<CustomerEntity, Long> {
    Optional<CustomerEntity> findByDocumentTypeAndDocumentIssuingCountryAndDocumentNumberHash(
            String type,
            String country,
            String hash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select customer from CustomerEntity customer join fetch customer.user where customer.userId = :id")
    Optional<CustomerEntity> lockById(@Param("id") Long id);

    @Modifying
    @Query(value = "update customers set version = version + 1 where user_id = :id and version = :expectedVersion",
            nativeQuery = true)
    int incrementVersionIfCurrent(@Param("id") Long id,
                                  @Param("expectedVersion") int expectedVersion);
}