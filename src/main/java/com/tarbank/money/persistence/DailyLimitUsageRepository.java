package com.tarbank.money.persistence;

import com.tarbank.money.domain.DailyLimitUsageEntity;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDate;

public interface DailyLimitUsageRepository extends Repository<DailyLimitUsageEntity, Long> {
    @Modifying
    @Query(value = """
            insert into daily_limit_usage
                (account_id, operation_type, usage_date, used_amount, updated_at)
            values (:accountId, :operationType, :usageDate, 0.0000, :now)
            on conflict (account_id, operation_type, usage_date) do nothing
            """, nativeQuery = true)
    int insertIfAbsent(@Param("accountId") Long accountId,
                       @Param("operationType") String operationType,
                       @Param("usageDate") LocalDate usageDate,
                       @Param("now") Instant now);

    @Modifying
    @Query(value = "delete from daily_limit_usage where id=:id and used_amount=0.0000",
           nativeQuery = true)
    int deleteUnused(@Param("id") Long id);
}
