package com.tarbank.common.persistence;

import com.tarbank.common.domain.AuditEventEntity;
import org.springframework.data.repository.Repository;

public interface AuditEventRepository extends Repository<AuditEventEntity, Long> {
    <S extends AuditEventEntity> S save(S entity);
}
