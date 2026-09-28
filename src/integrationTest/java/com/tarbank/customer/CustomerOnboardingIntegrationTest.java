package com.tarbank.customer;

import com.tarbank.customer.persistence.CustomerRepository;
import com.tarbank.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class CustomerOnboardingIntegrationTest extends AbstractIntegrationTest {
    @Autowired
    private MockMvc mvc;

    @Autowired
    private CustomerRepository customers;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void seededManagerCanLoginAndLogout() throws Exception {
        String token = login();
        mvc.perform(post("/api/v1/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/customers/1").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createsCustomerReturnsSafeProfileAndEncryptsDocument() throws Exception {
        String key = UUID.randomUUID().toString();
        String response = create(key, customer("alice", "A1234567"));
        assertThat(response).contains("\"username\":\"alice\"").doesNotContain("A1234567");
        Long id = customerId(response);
        mvc.perform(get("/api/v1/customers/{id}", id).header("Authorization", "Bearer " + login()))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("\"type\":\"PASSPORT\"")
                        .doesNotContain("A1234567"));
        byte[] encrypted = jdbc.queryForObject("select document_number_encrypted from customers where user_id=?", byte[].class, id);
        assertThat(new String(encrypted, StandardCharsets.UTF_8)).doesNotContain("A1234567");
    }

    @Test
    void replayReturnsStoredResultWithTheNewCorrelationId() throws Exception {
        String key = UUID.randomUUID().toString();
        String body = customer("bobby", "B1234567");
        String first = create(key, body, "0aa88e6c-8248-4202-85d4-710c7f96c81c");
        String replay = create(key, body, "aadd8e6c-8248-4202-85d4-710c7f96c81c");
        assertThat(customerId(replay)).isEqualTo(customerId(first));
        assertThat(replay).contains("aadd8e6c-8248-4202-85d4-710c7f96c81c");
    }

    @Test
    void rejectsChangedIdempotencyInputAndDuplicateDocument() throws Exception {
        String key = UUID.randomUUID().toString();
        create(key, customer("carol", "C1234567"));
        mvc.perform(post("/api/v1/customers").header("Authorization", "Bearer " + login()).header("Idempotency-Key", key)
                            .contentType(MediaType.APPLICATION_JSON).content(customer("daria", "D1234567")))
                .andExpect(status().isConflict())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString()).contains("IDEMPOTENCY_CONFLICT"));
        mvc.perform(post("/api/v1/customers").header("Authorization", "Bearer " + login()).header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON).content(customer("daria", "c-123 4567")))
                .andExpect(status().isConflict())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString()).contains("IDENTITY_DOCUMENT_ALREADY_EXISTS"));
    }

    @Test
    void rejectsUnderageCustomersAndInvalidIdempotencyKeys() throws Exception {
        mvc.perform(post("/api/v1/customers").header("Authorization", "Bearer " + login()).header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON).content(customer("young", "Y1234567").replace("1990-01-01", "2010-01-01")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString()).contains("CUSTOMER_MUST_BE_ADULT"));
        mvc.perform(post("/api/v1/customers").header("Authorization", "Bearer " + login()).header("Idempotency-Key", "00000000-0000-1000-8000-000000000000")
                            .contentType(MediaType.APPLICATION_JSON).content(customer("valid", "V1234567")))
                .andExpect(status().isBadRequest());
    }

    private String create(String key,
                          String body) throws Exception {
        return create(key, body, UUID.randomUUID().toString());
    }

    private String create(String key,
                          String body,
                          String correlationId) throws Exception {
        return mvc.perform(post("/api/v1/customers").header("Authorization", "Bearer " + login()).header("Idempotency-Key", key)
                                   .header("X-Correlation-Id", correlationId).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    }

    private String login() throws Exception {
        String response = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                                              .content("{\"username\":\"MANAGER\",\"password\":\"TestPass123!\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return response.replaceFirst(".*\\\"accessToken\\\":\\\"([^\\\"]+)\\\".*", "$1");
    }

    private Long customerId(String response) {
        return Long.valueOf(response.replaceFirst(".*\\\"customerId\\\":(\\d+).*", "$1"));
    }

    private String customer(String username,
                            String document) {
        return "{\"username\":\"" + username + "\",\"password\":\"StrongPwd123\",\"firstName\":\"Alice\",\"lastName\":\"Example\",\"dateOfBirth\":\"1990-01-01\",\"email\":\"alice@example.test\",\"phoneNumber\":\"+381601234567\",\"residentialAddress\":{\"country\":\"RS\",\"city\":\"Belgrade\",\"postalCode\":\"11000\",\"line1\":\"Example 1\"},\"identityDocument\":{\"type\":\"PASSPORT\",\"issuingCountry\":\"RS\",\"number\":\"" + document + "\",\"expiresOn\":\"2030-01-01\"},\"timezone\":\"Europe/Belgrade\"}";
    }
}