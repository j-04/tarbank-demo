package com.tarbank.common.config;

import com.tarbank.common.observability.OperationalMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DependencyHealthConfigurationTest {
    @Test
    void readinessContributorsAreSafeAndTrackDependencyAvailability() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection databaseConnection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(databaseConnection);
        when(databaseConnection.isValid(2)).thenReturn(true);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        RedisConnectionFactory redisFactory = mock(RedisConnectionFactory.class);
        RedisConnection redisConnection = mock(RedisConnection.class);
        when(redis.getConnectionFactory()).thenReturn(redisFactory);
        when(redisFactory.getConnection()).thenReturn(redisConnection);
        when(redisConnection.ping()).thenReturn("PONG");
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        OperationalMetrics metrics = new OperationalMetrics(registry);
        DependencyHealthConfiguration configuration = new DependencyHealthConfiguration();
        HealthIndicator postgres = configuration.tarbankPostgresHealthIndicator(dataSource, metrics);
        HealthIndicator redisHealth = configuration.tarbankRedisHealthIndicator(redis, metrics);

        assertThat(postgres.health().getStatus()).isEqualTo(Status.UP);
        assertThat(redisHealth.health().getStatus()).isEqualTo(Status.UP);
        assertThat(postgres.health().getDetails()).isEmpty();
        assertThat(redisHealth.health().getDetails()).isEmpty();
        assertThat(registry.get("tarbank.dependency.up").tag("dependency", "postgres").gauge().value())
                .isEqualTo(1.0);
        assertThat(registry.get("tarbank.dependency.up").tag("dependency", "redis").gauge().value())
                .isEqualTo(1.0);

        when(dataSource.getConnection()).thenThrow(new SQLException("database endpoint unavailable"));
        when(redisFactory.getConnection()).thenThrow(
                new DataAccessResourceFailureException("redis endpoint unavailable"));

        assertThat(postgres.health().getStatus()).isEqualTo(Status.DOWN);
        assertThat(redisHealth.health().getStatus()).isEqualTo(Status.DOWN);
        assertThat(postgres.health().getDetails()).isEmpty();
        assertThat(redisHealth.health().getDetails()).isEmpty();
        assertThat(registry.get("tarbank.dependency.up").tag("dependency", "postgres").gauge().value())
                .isZero();
        assertThat(registry.get("tarbank.dependency.up").tag("dependency", "redis").gauge().value())
                .isZero();
    }
}
