package com.tarbank.support;

import com.tarbank.TarbankApplication;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.HashMap;
import java.util.Map;

@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class AbstractIntegrationTest {

    private static final String DATABASE_NAME = "tarbank_test";

    private static final String DATABASE_USERNAME = "tarbank_test";

    private static final String DATABASE_PASSWORD = "tarbank-test-database-password";

    @Container
    protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:17.5-alpine"))
            .withDatabaseName(DATABASE_NAME)
            .withUsername(DATABASE_USERNAME)
            .withPassword(DATABASE_PASSWORD);

    private static final String REDIS_PASSWORD = "tarbank-test-redis-password";

    @Container
    protected static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:7.4.2-alpine"))
            .withCommand("redis-server", "--requirepass", REDIS_PASSWORD)
            .withExposedPorts(6379);

    @Autowired
    protected StringRedisTemplate redisTemplate;

    @DynamicPropertySource
    static void registerContainerProperties(DynamicPropertyRegistry registry) {
        registry.add("tarbank.database.url", POSTGRES::getJdbcUrl);
        registry.add("tarbank.database.username", POSTGRES::getUsername);
        registry.add("tarbank.database.password", POSTGRES::getPassword);
        registry.add("tarbank.redis.host", REDIS::getHost);
        registry.add("tarbank.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("tarbank.redis.password", () -> REDIS_PASSWORD);
    }

    @BeforeEach
    void clearRedis() {
        redisTemplate.getConnectionFactory()
                     .getConnection()
                     .serverCommands()
                     .flushDb();
    }

    protected ConfigurableApplicationContext startApplication(Map<String, Object> overrides) {
        Map<String, Object> properties = new HashMap<>();
        properties.put("tarbank.database.url", POSTGRES.getJdbcUrl());
        properties.put("tarbank.database.username", POSTGRES.getUsername());
        properties.put("tarbank.database.password", POSTGRES.getPassword());
        properties.put("tarbank.redis.host", REDIS.getHost());
        properties.put("tarbank.redis.port", REDIS.getMappedPort(6379));
        properties.put("tarbank.redis.password", REDIS_PASSWORD);
        properties.put("spring.datasource.url", POSTGRES.getJdbcUrl());
        properties.put("spring.datasource.username", POSTGRES.getUsername());
        properties.put("spring.datasource.password", POSTGRES.getPassword());
        properties.put("spring.data.redis.host", REDIS.getHost());
        properties.put("spring.data.redis.port", REDIS.getMappedPort(6379));
        properties.put("spring.data.redis.password", REDIS_PASSWORD);
        properties.putAll(overrides);

        String[] commandLineProperties = properties.entrySet()
                                                   .stream()
                                                   .map(entry -> "--" + entry.getKey() + "=" + entry.getValue())
                                                   .toArray(String[]::new);

        return new SpringApplicationBuilder(TarbankApplication.class)
                .web(WebApplicationType.NONE)
                .profiles("test")
                .run(commandLineProperties);
    }
}