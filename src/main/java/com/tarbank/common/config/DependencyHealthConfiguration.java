package com.tarbank.common.config;

import com.tarbank.common.observability.OperationalMetrics;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.sql.DataSource;

@Configuration
class DependencyHealthConfiguration {
    private static final Logger LOGGER = LogManager.getLogger(DependencyHealthConfiguration.class);

    @Bean
    HealthIndicator tarbankPostgresHealthIndicator(DataSource dataSource,
                                                   OperationalMetrics metrics) {
        return () -> {
            try (var connection = dataSource.getConnection()) {
                boolean available = connection.isValid(2);
                metrics.dependency("postgres", available);
                return available ? Health.up()
                                         .build() : Health.down()
                                                          .build();
            } catch (Exception exception) {
                metrics.dependency("postgres", false);
                LOGGER.warn("PostgreSQL health check failed exceptionType={}",
                            exception.getClass()
                                     .getName());
                return Health.down()
                             .build();
            }
        };
    }

    @Bean
    HealthIndicator tarbankRedisHealthIndicator(StringRedisTemplate redis,
                                                OperationalMetrics metrics) {
        return () -> {
            try (var connection = redis.getConnectionFactory()
                                       .getConnection()) {
                boolean available = "PONG".equals(connection.ping());
                metrics.dependency("redis", available);
                return available ? Health.up()
                                         .build() : Health.down()
                                                          .build();
            } catch (Exception exception) {
                metrics.dependency("redis", false);
                LOGGER.warn("Redis health check failed exceptionType={}",
                            exception.getClass()
                                     .getName());
                return Health.down()
                             .build();
            }
        };
    }
}
