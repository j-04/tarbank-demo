package com.tarbank.account.persistence;

import com.tarbank.account.domain.AccountEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AccountRepository extends JpaRepository<AccountEntity, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select account from AccountEntity account where account.customer.userId = :customerId and account.status <> com.tarbank.account.domain.AccountStatus.DEACTIVATED order by account.id")
    List<AccountEntity> lockNonDeactivatedByCustomerId(@Param("customerId") Long customerId);

    @Query("select account from AccountEntity account where account.customer.userId = :customerId and (:afterId is null or account.id > :afterId) order by account.id")
    List<AccountEntity> findPageByCustomerId(@Param("customerId") Long customerId,
                                             @Param("afterId") Long afterId,
                                             Pageable pageable);

    @EntityGraph(attributePaths = {"customer", "customer.user"})
    Optional<AccountEntity> findByAccountNumber(String accountNumber);

    @Query("select account.customer.userId from AccountEntity account where account.accountNumber = :accountNumber")
    Optional<Long> findCustomerIdByAccountNumber(@Param("accountNumber") String accountNumber);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select account from AccountEntity account join fetch account.customer customer join fetch customer.user where account.accountNumber = :accountNumber")
    Optional<AccountEntity> lockByAccountNumber(@Param("accountNumber") String accountNumber);
}
