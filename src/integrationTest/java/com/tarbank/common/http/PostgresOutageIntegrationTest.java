package com.tarbank.common.http;

import com.tarbank.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@TestPropertySource(properties = "spring.datasource.hikari.connection-timeout=1000")
@Timeout(value = 45, unit = TimeUnit.SECONDS)
class PostgresOutageIntegrationTest extends AbstractIntegrationTest {
    @Autowired
    private MockMvc mvc;

    @Autowired
    private ApplicationContext context;

    @Test
    void validJwtReturnsDependencyUnavailableWhenPostgresStops() throws Exception {
        String loginBody = mvc.perform(post("/api/v1/auth/login")
                                               .contentType(MediaType.APPLICATION_JSON)
                                               .content("""
                                                                {
                                                                  "username": "manager",
                                                                  "password": "TestPass123!"
                                                                }
                                                                """))
                              .andReturn()
                              .getResponse()
                              .getContentAsString();
        String token = extract(loginBody, "accessToken");
        HealthIndicator health = context.getBean(
                "tarbankPostgresHealthIndicator", HealthIndicator.class);
        assertThat(health.health().getStatus().getCode()).isEqualTo("UP");

        POSTGRES.stop();

        assertThat(health.health().getStatus().getCode()).isEqualTo("DOWN");
        var response = mvc.perform(get("/api/v1/customers/999999999")
                                           .header("Authorization", "Bearer " + token))
                          .andReturn()
                          .getResponse();
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString())
                .contains("\"code\":\"DEPENDENCY_UNAVAILABLE\"")
                .doesNotContain("jdbc:", "postgresql", "connection");
    }

    private String extract(String body,
                           String field) {
        var matcher = Pattern.compile("\\\"" + Pattern.quote(field)
                                              + "\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
                             .matcher(body);
        if (!matcher.find()) throw new AssertionError("Missing field " + field + " in " + body);
        return matcher.group(1);
    }
}
