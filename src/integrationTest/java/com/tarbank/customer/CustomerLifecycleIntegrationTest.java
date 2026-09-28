package com.tarbank.customer;

import com.tarbank.account.domain.AccountEntity;
import com.tarbank.account.domain.AccountStatus;
import com.tarbank.account.domain.Currency;
import com.tarbank.account.persistence.AccountRepository;
import com.tarbank.common.domain.AuditEventEntity;
import com.tarbank.common.persistence.AuditEventRepository;
import com.tarbank.customer.persistence.CustomerRepository;
import com.tarbank.security.persistence.ManagerRepository;
import com.tarbank.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class CustomerLifecycleIntegrationTest extends AbstractIntegrationTest {
    @Autowired
    private MockMvc mvc;

    @Autowired
    private CustomerRepository customers;

    @Autowired
    private ManagerRepository managers;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoSpyBean
    private PasswordEncoder passwordEncoder;

    @MockitoSpyBean
    private AuditEventRepository audits;

    @Test
    void listsReadsAndUpdatesCustomersWithSafeCursorAndEtagContracts() throws Exception {
        Long id = createCustomer("lifecycleone", "L1000001");
        createCustomer("lifecycletwo", "L1000002");
        String manager = login("manager", "TestPass123!");

        String list = mvc.perform(get("/api/v1/customers").param("limit", "1")
                                                          .header("Authorization", "Bearer " + manager))
                         .andExpect(status().isOk())
                         .andReturn()
                         .getResponse()
                         .getContentAsString();
        assertThat(list).contains("nextCursor")
                        .doesNotContain("L1000001")
                        .doesNotContain("documentNumber");
        mvc.perform(get("/api/v1/customers").param("status", "UNKNOWN")
                                            .header("Authorization", "Bearer " + manager))
           .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/customers").param("cursor", "not-a-cursor")
                                            .header("Authorization", "Bearer " + manager))
           .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/customers").param("limit", "not-a-number")
                                            .header("Authorization", "Bearer " + manager))
           .andExpect(status().isBadRequest());

        var detail = mvc.perform(get("/api/v1/customers/{id}", id)
                                         .header("Authorization", "Bearer " + manager))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse();
        assertThat(detail.getHeader("ETag")).isEqualTo("\"customer-v0\"");
        assertThat(detail.getContentAsString()).doesNotContain("L1000001")
                                               .doesNotContain("passwordHash");

        mvc.perform(patch("/api/v1/customers/{id}", id).header("Authorization", "Bearer " + manager)
                                                       .contentType(MediaType.APPLICATION_JSON)
                                                       .content("{\"phoneNumber\":\"+381601111111\"}"))
           .andExpect(status().isPreconditionRequired());
        var updated = mvc.perform(patch("/api/v1/customers/{id}", id).header("Authorization", "Bearer " + manager)
                                                                     .header("If-Match", "customer-v0")
                                                                     .contentType(MediaType.APPLICATION_JSON)
                                                                     .content("{\"firstName\":\"Alicia\",\"phoneNumber\":\"+381601111111\"}"))
                         .andExpect(status().isOk())
                         .andReturn()
                         .getResponse();
        assertThat(updated.getHeader("ETag")).isEqualTo("\"customer-v1\"");
        assertThat(updated.getContentAsString()).contains("Alicia", "+381601111111");
        mvc.perform(patch("/api/v1/customers/{id}", id).header("Authorization", "Bearer " + manager)
                                                       .header("If-Match", "customer-v0")
                                                       .contentType(MediaType.APPLICATION_JSON)
                                                       .content("{\"lastName\":\"Stale\"}"))
           .andExpect(status().isPreconditionFailed());
        mvc.perform(patch("/api/v1/customers/{id}", id).header("Authorization", "Bearer " + manager)
                                                       .header("If-Match", "customer-v1")
                                                       .contentType(MediaType.APPLICATION_JSON)
                                                       .content("{\"timezone\":\"UTC\"}"))
           .andExpect(status().isBadRequest());

        String customerToken = login("lifecycleone", "StrongPwd123");
        mvc.perform(get("/api/v1/customers").header("Authorization", "Bearer " + customerToken))
           .andExpect(status().isForbidden());
    }

    @Test
    void statusTransitionsCascadeWithCustomerFirstLockingAndUnblockDoesNotRestoreAccounts() throws Exception {
        Long id = createCustomer("statusflow", "S1000001");
        var customer = customers.findById(id)
                                .orElseThrow();
        var manager = managers.findById(managerId())
                              .orElseThrow();
        AccountEntity account = accounts.saveAndFlush(new AccountEntity("TB90000000000001", customer, Currency.EUR, manager, Instant.now()));
        String customerToken = login("statusflow", "StrongPwd123");
        String managerToken = login("manager", "TestPass123!");

        patchStatus(id, "customer-v0", "BLOCKED", managerToken).andExpect(status().isOk());
        assertThat(accounts.findById(account.getId())
                           .orElseThrow()
                           .getStatus()).isEqualTo(AccountStatus.BLOCKED);
        mvc.perform(post("/api/v1/auth/logout").header("Authorization", "Bearer " + customerToken))
           .andExpect(status().isForbidden());

        patchStatus(id, "customer-v1", "ACTIVE", managerToken).andExpect(status().isOk());
        assertThat(accounts.findById(account.getId())
                           .orElseThrow()
                           .getStatus()).isEqualTo(AccountStatus.BLOCKED);
        patchStatus(id, "customer-v2", "DEACTIVATED", managerToken).andExpect(status().isOk());
        assertThat(accounts.findById(account.getId())
                           .orElseThrow()
                           .getStatus()).isEqualTo(AccountStatus.DEACTIVATED);
        patchStatus(id, "customer-v3", "ACTIVE", managerToken).andExpect(status().isConflict());
        patchStatus(id, "customer-v3", "BLOCKED", managerToken).andExpect(status().isConflict());
        patchStatus(id, "customer-v3", "DEACTIVATED", managerToken).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("select count(*) from audit_events where target_id=?", Integer.class, id.toString()))
                .isGreaterThanOrEqualTo(3);

        Long activeId = createCustomer("invalidactive", "S1000002");
        patchStatus(activeId, "customer-v0", "ACTIVE", managerToken).andExpect(status().isConflict());

        Long blockedId = createCustomer("blockeddeactivate", "S1000003");
        patchStatus(blockedId, "customer-v0", "BLOCKED", managerToken).andExpect(status().isOk());
        patchStatus(blockedId, "customer-v1", "BLOCKED", managerToken).andExpect(status().isConflict());
        patchStatus(blockedId, "customer-v1", "DEACTIVATED", managerToken).andExpect(status().isOk());
    }

    @Test
    void passwordResetIsIdempotentAndInvalidatesPreviouslyIssuedTokens() throws Exception {
        Long id = createCustomer("resetflow", "R1000001");
        String oldToken = login("resetflow", "StrongPwd123");
        String manager = login("manager", "TestPass123!");
        String key = UUID.randomUUID()
                         .toString();

        resetPassword(id, UUID.randomUUID()
                              .toString(), "ResetPwd123!", oldToken)
                .andExpect(status().isForbidden());
        var invalidReset = resetPassword(id, UUID.randomUUID()
                                                 .toString(), "too-short", manager)
                .andExpect(status().isBadRequest())
                .andReturn()
                .getResponse();
        assertThat(invalidReset.getContentAsString()).contains("newPassword", "PasswordPolicy");
        var resetResponse = resetPassword(id, key, "ResetPwd123!", manager)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse();
        assertThat(resetResponse.getContentAsString())
                .contains("PASSWORD_RESET")
                .doesNotContain("ResetPwd123!", "passwordHash");
        int version = jdbc.queryForObject("select credential_version from users where id=?", Integer.class, id);
        String passwordHash = jdbc.queryForObject("select password_hash from users where id=?", String.class, id);
        assertThat(version).isEqualTo(1);
        assertThat(passwordEncoder.matches("ResetPwd123!", passwordHash)).isTrue();
        String legacyFingerprint = sha256("ResetPwd123!");
        String storedFingerprint = requestFingerprint(key);
        assertThat(storedFingerprint).isNotEqualTo(legacyFingerprint);
        mvc.perform(post("/api/v1/auth/logout").header("Authorization", "Bearer " + oldToken))
           .andExpect(status().isUnauthorized());

        jdbc.update("update api_request_idempotency set request_hash=? where idempotency_key=?",
                    legacyFingerprint, UUID.fromString(key));
        resetPassword(id, key, "ResetPwd123!", manager).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select credential_version from users where id=?", Integer.class, id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select password_hash from users where id=?", String.class, id))
                .isEqualTo(passwordHash);
        assertThat(requestFingerprint(key)).isEqualTo(storedFingerprint)
                                           .isNotEqualTo(legacyFingerprint);

        String afterFirstReset = login("resetflow", "ResetPwd123!");
        resetPassword(id, UUID.randomUUID()
                              .toString(), "OtherPwd123!", manager).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select credential_version from users where id=?", Integer.class, id)).isEqualTo(2);
        mvc.perform(post("/api/v1/auth/logout").header("Authorization", "Bearer " + afterFirstReset))
           .andExpect(status().isUnauthorized());
    }

    @Test
    void patchRejectsInternalPresenceFlagsAndExplicitNullRequiredFieldsAtomically() throws Exception {
        Long id = createCustomer("patchpresence", "P1000001");
        String manager = login("manager", "TestPass123!");
        int auditCount = jdbc.queryForObject(
                "select count(*) from audit_events where action='CUSTOMER_UPDATED' and target_id=?",
                Integer.class, id.toString());

        for (String flag : List.of("firstNameSupplied", "middleNameSupplied", "lastNameSupplied", "emailSupplied",
                                   "phoneNumberSupplied", "residentialAddressSupplied")) {
            mvc.perform(patch("/api/v1/customers/{id}", id)
                                .header("Authorization", "Bearer " + manager)
                                .header("If-Match", "customer-v0")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"firstName\":\"Changed\",\"" + flag + "\":false}"))
               .andExpect(status().isBadRequest());
        }

        var nullPhone = mvc.perform(patch("/api/v1/customers/{id}", id)
                                            .header("Authorization", "Bearer " + manager)
                                            .header("If-Match", "customer-v0")
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content("{\"firstName\":\"Changed\",\"phoneNumber\":null}"))
                           .andExpect(status().isBadRequest())
                           .andReturn()
                           .getResponse();
        assertThat(nullPhone.getContentAsString()).contains("phoneNumber", "NotNull");

        var nullAddress = mvc.perform(patch("/api/v1/customers/{id}", id)
                                              .header("Authorization", "Bearer " + manager)
                                              .header("If-Match", "customer-v0")
                                              .contentType(MediaType.APPLICATION_JSON)
                                              .content("{\"firstName\":\"Changed\",\"residentialAddress\":null}"))
                             .andExpect(status().isBadRequest())
                             .andReturn()
                             .getResponse();
        assertThat(nullAddress.getContentAsString()).contains("residentialAddress", "NotNull");

        mvc.perform(patch("/api/v1/customers/{id}", id)
                            .header("Authorization", "Bearer " + manager)
                            .header("If-Match", "customer-v0")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"firstName\":\"Changed\",\"phoneNumber\":null,\"phoneNumberSupplied\":false}"))
           .andExpect(status().isBadRequest());

        var unchanged = mvc.perform(get("/api/v1/customers/{id}", id)
                                            .header("Authorization", "Bearer " + manager))
                           .andExpect(status().isOk())
                           .andReturn()
                           .getResponse();
        assertThat(unchanged.getHeader("ETag")).isEqualTo("\"customer-v0\"");
        assertThat(unchanged.getContentAsString()).contains("Alice", "+381601234567", "Example 1");
        assertThat(jdbc.queryForObject(
                "select count(*) from audit_events where action='CUSTOMER_UPDATED' and target_id=?",
                Integer.class, id.toString())).isEqualTo(auditCount);
    }

    @Test
    void concurrentProfileUpdatesWithTheSameEtagAllowOnlyOneWriter() throws Exception {
        Long id = createCustomer("concurrentprofile", "C1000001");
        String manager = login("manager", "TestPass123!");
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> updateStatusAfterSignal(
                    start, id, manager, "{\"firstName\":\"FirstWriter\"}"));
            var second = executor.submit(() -> updateStatusAfterSignal(
                    start, id, manager, "{\"lastName\":\"SecondWriter\"}"));
            start.countDown();

            assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(200, 412);
        }
        assertThat(customers.findById(id)
                            .orElseThrow()
                            .getVersion()).isEqualTo(1);
    }

    @Test
    void concurrentPasswordResetsWithDifferentKeysAreSerializedAndBothApplied() throws Exception {
        Long id = createCustomer("concurrentreset", "C1000002");
        String manager = login("manager", "TestPass123!");
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> resetStatusAfterSignal(
                    start, id, UUID.randomUUID()
                                   .toString(), "FirstPwd123!", manager));
            var second = executor.submit(() -> resetStatusAfterSignal(
                    start, id, UUID.randomUUID()
                                   .toString(), "OtherPwd123!", manager));
            start.countDown();

            assertThat(List.of(first.get(), second.get())).containsOnly(200);
        }
        assertThat(jdbc.queryForObject("select credential_version from users where id=?", Integer.class, id)).isEqualTo(2);
        String storedHash = jdbc.queryForObject("select password_hash from users where id=?", String.class, id);
        assertThat(passwordEncoder.matches("FirstPwd123!", storedHash)
                           || passwordEncoder.matches("OtherPwd123!", storedHash)).isTrue();
        assertThat(jdbc.queryForObject(
                "select count(*) from audit_events where action='CUSTOMER_PASSWORD_RESET' and target_id=?",
                Integer.class, id.toString())).isEqualTo(2);
    }

    @Test
    void concurrentProfileUpdatePreservesCompletedPasswordReset() throws Exception {
        Long id = createCustomer("resetprofilelock", "C1000004");
        String manager = login("manager", "TestPass123!");
        String oldToken = login("resetprofilelock", "StrongPwd123");
        CountDownLatch encoderEntered = new CountDownLatch(1);
        CountDownLatch releaseEncoder = new CountDownLatch(1);
        pausePasswordEncoding("NameReset12!", encoderEntered, releaseEncoder);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var resetRequest = executor.submit(() -> resetPassword(
                    id, UUID.randomUUID()
                            .toString(), "NameReset12!", manager)
                    .andReturn()
                    .getResponse()
                    .getStatus());
            assertThat(encoderEntered.await(10, TimeUnit.SECONDS)).isTrue();
            var profileRequest = executor.submit(() -> mvc.perform(patch("/api/v1/customers/{id}", id)
                                                                           .header("Authorization", "Bearer " + manager)
                                                                           .header("If-Match", "customer-v0")
                                                                           .contentType(MediaType.APPLICATION_JSON)
                                                                           .content("{\"firstName\":\"Renamed\"}"))
                                                          .andReturn()
                                                          .getResponse()
                                                          .getStatus());
            try {
                assertThat(awaitCustomerRowLockWait()).isTrue();
            } finally {
                releaseEncoder.countDown();
            }
            assertThat(resetRequest.get(10, TimeUnit.SECONDS)).isEqualTo(200);
            assertThat(profileRequest.get(10, TimeUnit.SECONDS)).isEqualTo(200);
        } finally {
            releaseEncoder.countDown();
            reset(passwordEncoder);
        }

        var user = customers.findById(id)
                            .orElseThrow()
                            .getUser();
        assertThat(user.getFirstName()).isEqualTo("Renamed");
        assertThat(user.getCredentialVersion()).isEqualTo(1);
        assertThat(passwordEncoder.matches("NameReset12!", user.getPasswordHash())).isTrue();
        assertThat(passwordEncoder.matches("StrongPwd123", user.getPasswordHash())).isFalse();
        mvc.perform(get("/api/v1/accounts").header("Authorization", "Bearer " + oldToken))
           .andExpect(status().isUnauthorized());
    }

    @Test
    void concurrentCustomerStatusChangePreservesCompletedPasswordReset() throws Exception {
        Long id = createCustomer("resetstatuslock", "C1000005");
        String manager = login("manager", "TestPass123!");
        String oldToken = login("resetstatuslock", "StrongPwd123");
        CountDownLatch encoderEntered = new CountDownLatch(1);
        CountDownLatch releaseEncoder = new CountDownLatch(1);
        pausePasswordEncoding("StateReset1!", encoderEntered, releaseEncoder);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var resetRequest = executor.submit(() -> resetPassword(
                    id, UUID.randomUUID()
                            .toString(), "StateReset1!", manager)
                    .andReturn()
                    .getResponse()
                    .getStatus());
            assertThat(encoderEntered.await(10, TimeUnit.SECONDS)).isTrue();
            var statusRequest = executor.submit(() -> patchStatus(
                    id, "customer-v0", "BLOCKED", manager).andReturn()
                                                          .getResponse()
                                                          .getStatus());
            try {
                assertThat(awaitCustomerRowLockWait()).isTrue();
            } finally {
                releaseEncoder.countDown();
            }
            assertThat(resetRequest.get(10, TimeUnit.SECONDS)).isEqualTo(200);
            assertThat(statusRequest.get(10, TimeUnit.SECONDS)).isEqualTo(200);
        } finally {
            releaseEncoder.countDown();
            reset(passwordEncoder);
        }

        var customer = customers.findById(id)
                                .orElseThrow();
        assertThat(customer.getUser()
                           .getStatus()
                           .name()).isEqualTo("BLOCKED");
        assertThat(customer.getUser()
                           .getCredentialVersion()).isEqualTo(1);
        assertThat(passwordEncoder.matches("StateReset1!", customer.getUser()
                                                                   .getPasswordHash())).isTrue();
        assertThat(passwordEncoder.matches("StrongPwd123", customer.getUser()
                                                                   .getPasswordHash())).isFalse();

        patchStatus(id, "customer-v1", "ACTIVE", manager).andExpect(status().isOk());
        mvc.perform(get("/api/v1/accounts").header("Authorization", "Bearer " + oldToken))
           .andExpect(status().isUnauthorized());
    }

    @Test
    void customerAccountAndAuditChangesRollBackTogether() throws Exception {
        Long id = createCustomer("rollbackflow", "C1000003");
        var customer = customers.findById(id)
                                .orElseThrow();
        var manager = managers.findById(managerId())
                              .orElseThrow();
        AccountEntity account = accounts.saveAndFlush(
                new AccountEntity("TB90000000000002", customer, Currency.USD, manager, Instant.now()));
        String managerToken = login("manager", "TestPass123!");
        int auditCount = jdbc.queryForObject("select count(*) from audit_events", Integer.class);

        doThrow(new IllegalStateException("forced audit failure"))
                .when(audits)
                .save(any(AuditEventEntity.class));
        try {
            patchStatus(id, "customer-v0", "BLOCKED", managerToken).andExpect(status().isInternalServerError());
        } finally {
            reset(audits);
        }

        assertThat(customers.findById(id)
                            .orElseThrow()
                            .getUser()
                            .getStatus()
                            .name()).isEqualTo("ACTIVE");
        assertThat(customers.findById(id)
                            .orElseThrow()
                            .getVersion()).isZero();
        AccountEntity reloaded = accounts.findById(account.getId())
                                         .orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(reloaded.getManagementVersion()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from audit_events", Integer.class)).isEqualTo(auditCount);
    }

    private void pausePasswordEncoding(String password,
                                       CountDownLatch entered,
                                       CountDownLatch release) {
        doAnswer(invocation -> {
            if (password.contentEquals(invocation.getArgument(0, CharSequence.class))) {
                entered.countDown();
                if (!release.await(15, TimeUnit.SECONDS)) {
                    throw new AssertionError("Timed out waiting to release password encoding.");
                }
            }
            return invocation.callRealMethod();
        }).when(passwordEncoder)
          .encode(any(CharSequence.class));
    }

    private boolean awaitCustomerRowLockWait() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbc.queryForObject(
                    "select count(*) from pg_stat_activity "
                            + "where datname=current_database() and wait_event_type='Lock' "
                            + "and lower(query) like '%select user_id from customers%'",
                    Integer.class);
            if (waiting != null && waiting > 0) {
                return true;
            }
            Thread.sleep(25);
        }
        return false;
    }

    private String requestFingerprint(String key) {
        return jdbc.queryForObject(
                "select request_hash from api_request_idempotency where idempotency_key=?",
                String.class, UUID.fromString(key));
    }

    private String sha256(String value) throws Exception {
        return HexFormat.of()
                        .formatHex(
                                MessageDigest.getInstance("SHA-256")
                                             .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private int updateStatusAfterSignal(CountDownLatch start,
                                        Long id,
                                        String token,
                                        String body) throws Exception {
        start.await();
        return mvc.perform(patch("/api/v1/customers/{id}", id)
                                   .header("Authorization", "Bearer " + token)
                                   .header("If-Match", "customer-v0")
                                   .contentType(MediaType.APPLICATION_JSON)
                                   .content(body))
                  .andReturn()
                  .getResponse()
                  .getStatus();
    }

    private int resetStatusAfterSignal(CountDownLatch start,
                                       Long id,
                                       String key,
                                       String password,
                                       String token)
            throws Exception {
        start.await();
        return resetPassword(id, key, password, token).andReturn()
                                                      .getResponse()
                                                      .getStatus();
    }

    private org.springframework.test.web.servlet.ResultActions patchStatus(
            Long id,
            String etag,
            String status,
            String token) throws Exception {
        return mvc.perform(patch("/api/v1/customers/{id}/status", id).header("Authorization", "Bearer " + token)
                                                                     .header("If-Match", etag)
                                                                     .contentType(MediaType.APPLICATION_JSON)
                                                                     .content("{\"status\":\"" + status + "\"}"));
    }

    private org.springframework.test.web.servlet.ResultActions resetPassword(
            Long id,
            String key,
            String password,
            String token) throws Exception {
        return mvc.perform(post("/api/v1/customers/{id}/password-reset", id)
                                   .header("Authorization", "Bearer " + token)
                                   .header("Idempotency-Key", key)
                                   .contentType(MediaType.APPLICATION_JSON)
                                   .content("{\"newPassword\":\"" + password + "\"}"));
    }

    private Long createCustomer(String username,
                                String document) throws Exception {
        String response = mvc.perform(post("/api/v1/customers").header("Authorization", "Bearer " + login("manager", "TestPass123!"))
                                                               .header("Idempotency-Key", UUID.randomUUID()
                                                                                              .toString())
                                                               .contentType(MediaType.APPLICATION_JSON)
                                                               .content(customer(username, document)))
                             .andExpect(status().isCreated())
                             .andReturn()
                             .getResponse()
                             .getContentAsString();
        return Long.valueOf(response.replaceFirst(".*\\\"customerId\\\":(\\d+).*", "$1"));
    }

    private Long managerId() {
        return jdbc.queryForObject("select id from users where username='manager'", Long.class);
    }

    private String login(String username,
                         String password) throws Exception {
        String response = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                                                                .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                             .andExpect(status().isOk())
                             .andReturn()
                             .getResponse()
                             .getContentAsString();
        return response.replaceFirst(".*\\\"accessToken\\\":\\\"([^\\\"]+)\\\".*", "$1");
    }

    private String customer(String username,
                            String document) {
        return "{\"username\":\"" + username + "\",\"password\":\"StrongPwd123\","
                + "\"firstName\":\"Alice\",\"lastName\":\"Example\",\"dateOfBirth\":\"1990-01-01\","
                + "\"email\":\"alice@example.test\",\"phoneNumber\":\"+381601234567\","
                + "\"residentialAddress\":{\"country\":\"RS\",\"city\":\"Belgrade\",\"postalCode\":\"11000\",\"line1\":\"Example 1\"},"
                + "\"identityDocument\":{\"type\":\"PASSPORT\",\"issuingCountry\":\"RS\",\"number\":\"" + document + "\",\"expiresOn\":\"2030-01-01\"},"
                + "\"timezone\":\"Europe/Belgrade\"}";
    }
}