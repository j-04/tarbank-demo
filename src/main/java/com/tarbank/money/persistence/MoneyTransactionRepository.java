package com.tarbank.money.persistence;

import com.tarbank.money.domain.MoneyTransactionEntity;
import org.springframework.data.repository.Repository;

import java.util.UUID;

public interface MoneyTransactionRepository extends Repository<MoneyTransactionEntity, UUID> {
    <S extends MoneyTransactionEntity> S save(S transaction);
}
