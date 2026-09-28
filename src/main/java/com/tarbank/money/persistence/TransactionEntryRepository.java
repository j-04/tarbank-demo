package com.tarbank.money.persistence;

import com.tarbank.money.domain.TransactionEntryEntity;
import org.springframework.data.repository.Repository;

public interface TransactionEntryRepository extends Repository<TransactionEntryEntity, Long> {
    <S extends TransactionEntryEntity> S save(S entry);
}
