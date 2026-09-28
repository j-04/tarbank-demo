package com.tarbank.account.persistence;

import com.tarbank.account.domain.AccountLimitOverrideEntity;
import org.springframework.data.repository.Repository;

public interface AccountLimitOverrideRepository extends Repository<AccountLimitOverrideEntity, Long> {
    <S extends AccountLimitOverrideEntity> S save(S override);
}