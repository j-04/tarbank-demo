package com.tarbank.common.application;

import com.tarbank.common.config.IdempotencyProperties;
import com.tarbank.common.persistence.ApiRequestIdempotencyRepository;
import com.tarbank.security.domain.UserEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RequestIdempotencyServiceTest {

    @Test
    void preservesInfrastructureExceptionType() {
        ApiRequestIdempotencyRepository records = mock(ApiRequestIdempotencyRepository.class);
        EntityManager entityManager = mock(EntityManager.class);
        Query query = mock(Query.class);
        when(entityManager.createNativeQuery("set local lock_timeout = '5000ms'")).thenReturn(query);
        when(query.executeUpdate()).thenThrow(new DataAccessResourceFailureException("database unavailable"));
        RequestIdempotencyService service = new RequestIdempotencyService(
                records,
                new IdempotencyProperties(Duration.ofHours(24), Duration.ofSeconds(5), "h".repeat(32)),
                JsonMapper.builder().build(), entityManager);

        assertThatThrownBy(() -> service.execute(mock(UserEntity.class), "TEST", "scope", UUID.randomUUID(),
                                                  "request-hash", Object.class, 200, Object::new))
                .isInstanceOf(DataAccessResourceFailureException.class)
                .hasMessage("database unavailable");
    }
}
