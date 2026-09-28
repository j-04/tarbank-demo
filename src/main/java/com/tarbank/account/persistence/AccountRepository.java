package com.tarbank.account.persistence;

import com.tarbank.account.domain.AccountEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface AccountRepository extends JpaRepository<AccountEntity, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select account from AccountEntity account where account.customer.userId = :customerId and account.status <> com.tarbank.account.domain.AccountStatus.DEACTIVATED order by account.id")
    List<AccountEntity> lockNonDeactivatedByCustomerId(@Param("customerId") Long customerId);
}