package com.tarbank.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.tarbank.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class SharedContainerBIntegrationTest extends AbstractIntegrationTest {

    @Test
    void redisIsReachableAfterAnotherIntegrationTestClass() {
        assertThat(redisTemplate.getConnectionFactory().getConnection().ping()).isEqualTo("PONG");
    }
}