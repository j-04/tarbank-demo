package com.tarbank.common.config;

import com.tarbank.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class SharedContainerAIntegrationTest extends AbstractIntegrationTest {

    @Test
    void redisIsReachable() {
        assertThat(redisTemplate.getConnectionFactory()
                                .getConnection()
                                .ping()).isEqualTo("PONG");
    }
}