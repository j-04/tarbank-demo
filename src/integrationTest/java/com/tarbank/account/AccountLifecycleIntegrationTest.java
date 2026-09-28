package com.tarbank.account;

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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class AccountLifecycleIntegrationTest extends AbstractIntegrationTest {
    @Autowired
    private MockMvc mvc;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private CustomerRepository customers;

    @Autowired
    private ManagerRepository managers;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoSpyBean
    private AuditEventRepository audits;

    @Test
    void creationUsesSafeInputsSequenceNumbersAndIdempotency() throws Exception {
        Long customerId = createCustomer("accountcreation", "A5000001");
        String manager = login("manager", "TestPass123!");
        String key = UUID.randomUUID().toString();

        Response first = createAccount(customerId, "EUR", key, manager, null);
        assertThat(first.status()).isEqualTo(201);
        String number = extract(first.body(), "accountNumber");
        assertThat(number).matches("^TB[0-9]{14}$");
        assertThat(first.body()).contains("\"customerId\":" + customerId, "\"currency\":\"EUR\"",
                                          "\"availableBalance\":\"0.0000\"", "\"status\":\"ACTIVE\"")
                .doesNotContain("managementVersion", "createdByManager", "statusChangedByManager");
        assertThat(jdbc.queryForObject("select balance from accounts where account_number=?",
                                       BigDecimal.class, number)).isEqualByComparingTo("0.0000");
        assertThat(jdbc.queryForObject("select management_version from accounts where account_number=?",
                                       Integer.class, number)).isZero();
        assertThat(jdbc.queryForObject("select created_by_manager_id from accounts where account_number=?",
                                       Long.class, number)).isEqualTo(managers.findAll().getFirst().getUserId());
        assertThatThrownBy(() -> jdbc.update(
                "update accounts set account_number='TB89999999999999' where account_number=?", number))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(accounts.findByAccountNumber(number)).isPresent();
        assertThatThrownBy(() -> accounts.saveAndFlush(new AccountEntity(
                "INVALID", customers.findById(customerId).orElseThrow(), Currency.EUR,
                managers.findAll().getFirst(), Instant.now())))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> accounts.saveAndFlush(new AccountEntity(
                number, customers.findById(customerId).orElseThrow(), Currency.EUR,
                managers.findAll().getFirst(), Instant.now())))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject(
                "select count(*) from audit_events where action='ACCOUNT_CREATED' and target_id=?",
                Integer.class, number)).isEqualTo(1);

        Response replay = createAccount(customerId, "EUR", key, manager, null);
        assertThat(replay.status()).isEqualTo(201);
        assertThat(extract(replay.body(), "accountNumber")).isEqualTo(number);
        assertThat(accounts.findAll().stream().filter(a -> a.getAccountNumber().equals(number))).hasSize(1);
        assertThat(createAccount(customerId, "USD", key, manager, null).status()).isEqualTo(409);

        Response usd = createAccount(customerId, "USD", UUID.randomUUID().toString(), manager, null);
        assertThat(usd.status()).isEqualTo(201);
        assertThat(usd.body()).contains("\"currency\":\"USD\"", "\"availableBalance\":\"0.0000\"");
        assertThat(extract(usd.body(), "accountNumber")).isNotEqualTo(number);

        assertThat(createAccount(customerId, "GBP", UUID.randomUUID().toString(), manager, null).status()).isEqualTo(400);
        assertThat(createAccount(customerId, "EUR", "00000000-0000-1000-8000-000000000000", manager, null).status())
                .isEqualTo(400);
        for (String forbidden : List.of(
                "\"balance\":\"10.0000\"", "\"accountNumber\":\"TB12345678901234\"",
                "\"status\":\"BLOCKED\"", "\"managementVersion\":7")) {
            assertThat(createAccount(customerId, "EUR", UUID.randomUUID().toString(), manager, forbidden).status())
                    .isEqualTo(400);
        }

        String customerToken = login("accountcreation", "StrongPwd123");
        assertThat(createAccount(customerId, "EUR", UUID.randomUUID().toString(), customerToken, null).status())
                .isEqualTo(403);

        Long blockedId = createCustomer("blockedaccountcreate", "A5000002");
        patchCustomerStatus(blockedId, "customer-v0", "BLOCKED", manager);
        assertThat(createAccount(blockedId, "EUR", UUID.randomUUID().toString(), manager, null).status())
                .isEqualTo(409);

        Long deactivatedId = createCustomer("deactivatedaccountcreate", "A5000003");
        patchCustomerStatus(deactivatedId, "customer-v0", "DEACTIVATED", manager);
        assertThat(createAccount(deactivatedId, "EUR", UUID.randomUUID().toString(), manager, null).status())
                .isEqualTo(409);
    }

    @Test
    void concurrentCreationsAreUniqueAndMatchingRetriesCreateOnce() throws Exception {
        Long customerId = createCustomer("concurrentaccounts", "A5000004");
        String manager = login("manager", "TestPass123!");
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> createAfterSignal(
                    start, customerId, "EUR", UUID.randomUUID().toString(), manager));
            var second = executor.submit(() -> createAfterSignal(
                    start, customerId, "USD", UUID.randomUUID().toString(), manager));
            start.countDown();
            Response one = first.get();
            Response two = second.get();
            assertThat(List.of(one.status(), two.status())).containsOnly(201);
            assertThat(extract(one.body(), "accountNumber")).isNotEqualTo(extract(two.body(), "accountNumber"));
        }

        Long replayCustomerId = createCustomer("concurrentaccountreplay", "A5000005");
        String sharedKey = UUID.randomUUID().toString();
        CountDownLatch replayStart = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> createAfterSignal(
                    replayStart, replayCustomerId, "EUR", sharedKey, manager));
            var second = executor.submit(() -> createAfterSignal(
                    replayStart, replayCustomerId, "EUR", sharedKey, manager));
            replayStart.countDown();
            Response one = first.get();
            Response two = second.get();
            assertThat(List.of(one.status(), two.status())).containsOnly(201);
            assertThat(extract(one.body(), "accountNumber")).isEqualTo(extract(two.body(), "accountNumber"));
        }
        assertThat(jdbc.queryForObject("select count(*) from accounts where customer_id=?",
                                       Integer.class, replayCustomerId)).isEqualTo(1);
    }

    @Test
    void managerAndOwnerReadsUseSafePaginationOwnershipAndDefaultLimits() throws Exception {
        Long customerId = createCustomer("accountreads", "A5000006");
        Long otherCustomerId = createCustomer("otheraccountreader", "A5000007");
        String manager = login("manager", "TestPass123!");
        String owner = login("accountreads", "StrongPwd123");
        String otherOwner = login("otheraccountreader", "StrongPwd123");
        String firstNumber = extract(createAccount(
                customerId, "EUR", UUID.randomUUID().toString(), manager, null).body(), "accountNumber");
        String secondNumber = extract(createAccount(
                customerId, "USD", UUID.randomUUID().toString(), manager, null).body(), "accountNumber");
        String thirdNumber = extract(createAccount(
                customerId, "EUR", UUID.randomUUID().toString(), manager, null).body(), "accountNumber");
        patchAccountStatus(secondNumber, "account-v0", "BLOCKED", manager);

        var firstPage = mvc.perform(get("/api/v1/customers/{id}/accounts", customerId)
                                            .param("limit", "2")
                                            .header("Authorization", "Bearer " + manager))
                .andExpect(status().isOk()).andReturn().getResponse();
        String firstBody = firstPage.getContentAsString();
        String cursor = extract(firstBody, "nextCursor");
        assertThat(firstBody).contains(firstNumber, secondNumber)
                .doesNotContain(thirdNumber, "customerId", "managementVersion", "createdByManager");
        assertThat(firstBody.indexOf(firstNumber)).isLessThan(firstBody.indexOf(secondNumber));

        String secondBody = mvc.perform(get("/api/v1/customers/{id}/accounts", customerId)
                                                .param("limit", "2").param("cursor", cursor)
                                                .header("Authorization", "Bearer " + manager))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(secondBody).contains(thirdNumber, "\"nextCursor\":null");

        String ownerList = mvc.perform(get("/api/v1/accounts")
                                               .header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(ownerList).contains(firstNumber, secondNumber, thirdNumber, "\"status\":\"BLOCKED\"")
                .doesNotContain("customerId", "managementVersion");

        mvc.perform(get("/api/v1/customers/{id}/accounts", 999999999L)
                            .header("Authorization", "Bearer " + manager))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/customers/{id}/accounts", customerId)
                            .param("cursor", "not-a-cursor")
                            .header("Authorization", "Bearer " + manager))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/customers/{id}/accounts", customerId)
                            .param("limit", "0")
                            .header("Authorization", "Bearer " + manager))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/accounts").param("limit", "101")
                            .header("Authorization", "Bearer " + owner))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/accounts")
                            .header("Authorization", "Bearer " + manager))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/customers/{id}/accounts", customerId)
                            .header("Authorization", "Bearer " + owner))
                .andExpect(status().isForbidden());

        var ownerDetail = mvc.perform(get("/api/v1/accounts/{number}", firstNumber)
                                              .header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk()).andReturn().getResponse();
        assertThat(ownerDetail.getHeader("ETag")).isEqualTo("\"account-v0\"");
        assertThat(ownerDetail.getContentAsString())
                .contains(firstNumber, "\"withdrawal\":{\"amount\":\"1000.0000\",\"expiresAt\":null}",
                          "\"transfer\":{\"amount\":\"1000.0000\",\"expiresAt\":null}")
                .doesNotContain("customerId", "managementVersion", "managerId", "createdAt");

        mvc.perform(get("/api/v1/accounts/{number}", firstNumber)
                            .header("Authorization", "Bearer " + manager))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/accounts/{number}", firstNumber)
                            .header("Authorization", "Bearer " + otherOwner))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/accounts/{number}", "TB99999999999999")
                            .header("Authorization", "Bearer " + manager))
                .andExpect(status().isNotFound());

        assertThat(jdbc.queryForObject(
                "select count(*) from information_schema.tables where table_schema='public' and table_name like '%limit_override%'",
                Integer.class)).isZero();
        assertThat(otherCustomerId).isPositive();
    }

    @Test
    void statusChangesRequireEtagsAndFollowTheLifecycleRules() throws Exception {
        Long customerId = createCustomer("accountstatus", "A5000008");
        String manager = login("manager", "TestPass123!");
        String number = extract(createAccount(
                customerId, "EUR", UUID.randomUUID().toString(), manager, null).body(), "accountNumber");

        mvc.perform(patch("/api/v1/accounts/{number}/status", number)
                            .header("Authorization", "Bearer " + manager)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"BLOCKED\"}"))
                .andExpect(status().isPreconditionRequired());
        mvc.perform(patch("/api/v1/accounts/{number}/status", number)
                            .header("Authorization", "Bearer " + manager)
                            .header("If-Match", "invalid")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"BLOCKED\"}"))
                .andExpect(status().isBadRequest());

        String owner = login("accountstatus", "StrongPwd123");
        assertThat(patchAccountStatus(number, "account-v0", "BLOCKED", owner).status()).isEqualTo(403);
        assertThat(patchAccountStatus(number, "account-v0", "ACTIVE", manager).status()).isEqualTo(409);

        var blocked = patchAccountStatus(number, "account-v0", "BLOCKED", manager);
        assertThat(blocked.status()).isEqualTo(200);
        assertThat(blocked.etag()).isEqualTo("\"account-v1\"");
        assertThat(blocked.body()).contains("\"status\":\"BLOCKED\"");
        assertThat(patchAccountStatus(number, "account-v0", "ACTIVE", manager).status()).isEqualTo(412);
        assertThat(patchAccountStatus(number, "account-v1", "BLOCKED", manager).status()).isEqualTo(409);

        Response active = patchAccountStatus(number, "account-v1", "ACTIVE", manager);
        assertThat(active.status()).isEqualTo(200);
        assertThat(active.etag()).isEqualTo("\"account-v2\"");
        Response deactivated = patchAccountStatus(number, "account-v2", "DEACTIVATED", manager);
        assertThat(deactivated.status()).isEqualTo(200);
        assertThat(deactivated.etag()).isEqualTo("\"account-v3\"");
        assertThat(patchAccountStatus(number, "account-v3", "ACTIVE", manager).status()).isEqualTo(409);
        assertThat(patchAccountStatus(number, "account-v3", "BLOCKED", manager).status()).isEqualTo(409);
        assertThat(patchAccountStatus(number, "account-v3", "DEACTIVATED", manager).status()).isEqualTo(409);
        assertThat(jdbc.queryForObject(
                "select count(*) from audit_events where action='ACCOUNT_STATUS_CHANGED' and target_id=?",
                Integer.class, number)).isEqualTo(3);
        assertThat(jdbc.queryForObject(
                "select status_changed_at is not null and status_changed_by_manager_id is not null from accounts where account_number=?",
                Boolean.class, number)).isTrue();

        String directNumber = extract(createAccount(
                customerId, "USD", UUID.randomUUID().toString(), manager, null).body(), "accountNumber");
        assertThat(patchAccountStatus(directNumber, "account-v0", "DEACTIVATED", manager).status()).isEqualTo(200);

        Long guardedCustomerId = createCustomer("guardedaccountstatus", "A5000009");
        String guardedNumber = extract(createAccount(
                guardedCustomerId, "EUR", UUID.randomUUID().toString(), manager, null).body(), "accountNumber");
        assertThat(patchAccountStatus(guardedNumber, "account-v0", "BLOCKED", manager).status()).isEqualTo(200);
        patchCustomerStatus(guardedCustomerId, "customer-v0", "BLOCKED", manager);
        assertThat(patchAccountStatus(guardedNumber, "account-v1", "ACTIVE", manager).status()).isEqualTo(409);
        patchCustomerStatus(guardedCustomerId, "customer-v1", "ACTIVE", manager);
        assertThat(accounts.findByAccountNumber(guardedNumber).orElseThrow().getStatus())
                .isEqualTo(AccountStatus.BLOCKED);
        assertThat(patchAccountStatus(guardedNumber, "account-v1", "ACTIVE", manager).status()).isEqualTo(200);
    }

    @Test
    void customerTransitionsSerializeWithAccountCreationAndUnblocking() throws Exception {
        String manager = login("manager", "TestPass123!");

        for (String target : List.of("BLOCKED", "DEACTIVATED")) {
            Long customerId = createCustomer(
                    "creationrace" + target.toLowerCase(), "A51" + (target.equals("BLOCKED") ? "00001" : "00002"));
            CountDownLatch start = new CountDownLatch(1);
            try (var executor = Executors.newFixedThreadPool(2)) {
                var creation = executor.submit(() -> createAfterSignal(
                        start, customerId, "EUR", UUID.randomUUID().toString(), manager));
                var customerTransition = executor.submit(() -> customerStatusAfterSignal(
                        start, customerId, "customer-v0", target, manager));
                start.countDown();
                assertThat(customerTransition.get()).isEqualTo(200);
                assertThat(creation.get().status()).isIn(201, 409);
            }
            assertThat(jdbc.queryForObject(
                    "select count(*) from accounts where customer_id=? and status='ACTIVE'",
                    Integer.class, customerId)).isZero();
        }

        for (String target : List.of("BLOCKED", "DEACTIVATED")) {
            Long customerId = createCustomer(
                    "unblockrace" + target.toLowerCase(), "A52" + (target.equals("BLOCKED") ? "00001" : "00002"));
            String number = extract(createAccount(
                    customerId, "EUR", UUID.randomUUID().toString(), manager, null).body(), "accountNumber");
            assertThat(patchAccountStatus(number, "account-v0", "BLOCKED", manager).status()).isEqualTo(200);

            CountDownLatch start = new CountDownLatch(1);
            try (var executor = Executors.newFixedThreadPool(2)) {
                var accountUnblock = executor.submit(() -> accountStatusAfterSignal(
                        start, number, "account-v1", "ACTIVE", manager));
                var customerTransition = executor.submit(() -> customerStatusAfterSignal(
                        start, customerId, "customer-v0", target, manager));
                start.countDown();
                assertThat(customerTransition.get()).isEqualTo(200);
                assertThat(accountUnblock.get()).isIn(200, 409, 412);
            }
            assertThat(accounts.findByAccountNumber(number).orElseThrow().getStatus().name()).isEqualTo(target);
        }
    }

    @Test
    void accountStatusAndAuditRollBackTogether() throws Exception {
        Long customerId = createCustomer("accountrollback", "A5000010");
        String manager = login("manager", "TestPass123!");
        String number = extract(createAccount(
                customerId, "EUR", UUID.randomUUID().toString(), manager, null).body(), "accountNumber");
        int auditCount = jdbc.queryForObject("select count(*) from audit_events", Integer.class);

        doThrow(new IllegalStateException("forced audit failure"))
                .when(audits).save(any(AuditEventEntity.class));
        try {
            assertThat(patchAccountStatus(number, "account-v0", "BLOCKED", manager).status()).isEqualTo(500);
        } finally {
            reset(audits);
        }

        AccountEntity reloaded = accounts.findByAccountNumber(number).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(reloaded.getManagementVersion()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from audit_events", Integer.class)).isEqualTo(auditCount);
    }

    private Response createAfterSignal(CountDownLatch start,
                                       Long customerId,
                                       String currency,
                                       String key,
                                       String token) throws Exception {
        start.await();
        return createAccount(customerId, currency, key, token, null);
    }

    private int customerStatusAfterSignal(CountDownLatch start,
                                          Long customerId,
                                          String etag,
                                          String next,
                                          String token) throws Exception {
        start.await();
        return patchCustomerStatus(customerId, etag, next, token).status();
    }

    private int accountStatusAfterSignal(CountDownLatch start,
                                         String accountNumber,
                                         String etag,
                                         String next,
                                         String token) throws Exception {
        start.await();
        return patchAccountStatus(accountNumber, etag, next, token).status();
    }

    private Response createAccount(Long customerId,
                                   String currency,
                                   String key,
                                   String token,
                                   String extraField) throws Exception {
        String body = "{\"currency\":\"" + currency + "\"";
        if (extraField != null) body += "," + extraField;
        body += "}";
        var response = mvc.perform(post("/api/v1/customers/{id}/accounts", customerId)
                                           .header("Authorization", "Bearer " + token)
                                           .header("Idempotency-Key", key)
                                           .contentType(MediaType.APPLICATION_JSON)
                                           .content(body))
                .andReturn().getResponse();
        return new Response(response.getStatus(), response.getContentAsString(), response.getHeader("ETag"));
    }

    private Response patchAccountStatus(String accountNumber,
                                        String etag,
                                        String next,
                                        String token) throws Exception {
        var response = mvc.perform(patch("/api/v1/accounts/{number}/status", accountNumber)
                                           .header("Authorization", "Bearer " + token)
                                           .header("If-Match", etag)
                                           .contentType(MediaType.APPLICATION_JSON)
                                           .content("{\"status\":\"" + next + "\"}"))
                .andReturn().getResponse();
        return new Response(response.getStatus(), response.getContentAsString(), response.getHeader("ETag"));
    }

    private Response patchCustomerStatus(Long customerId,
                                         String etag,
                                         String next,
                                         String token) throws Exception {
        var response = mvc.perform(patch("/api/v1/customers/{id}/status", customerId)
                                           .header("Authorization", "Bearer " + token)
                                           .header("If-Match", etag)
                                           .contentType(MediaType.APPLICATION_JSON)
                                           .content("{\"status\":\"" + next + "\"}"))
                .andReturn().getResponse();
        return new Response(response.getStatus(), response.getContentAsString(), response.getHeader("ETag"));
    }

    private Long createCustomer(String username,
                                String document) throws Exception {
        String response = mvc.perform(post("/api/v1/customers")
                                              .header("Authorization", "Bearer " + login("manager", "TestPass123!"))
                                              .header("Idempotency-Key", UUID.randomUUID().toString())
                                              .contentType(MediaType.APPLICATION_JSON)
                                              .content(customer(username, document)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return Long.valueOf(response.replaceFirst(".*\\\"customerId\\\":(\\d+).*", "$1"));
    }

    private String login(String username,
                         String password) throws Exception {
        String response = mvc.perform(post("/api/v1/auth/login")
                                              .contentType(MediaType.APPLICATION_JSON)
                                              .content("{\"username\":\"" + username
                                                               + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return response.replaceFirst(".*\\\"accessToken\\\":\\\"([^\\\"]+)\\\".*", "$1");
    }

    private String customer(String username,
                            String document) {
        return "{\"username\":\"" + username + "\",\"password\":\"StrongPwd123\","
                + "\"firstName\":\"Alice\",\"lastName\":\"Example\",\"dateOfBirth\":\"1990-01-01\","
                + "\"email\":\"alice@example.test\",\"phoneNumber\":\"+381601234567\","
                + "\"residentialAddress\":{\"country\":\"RS\",\"city\":\"Belgrade\","
                + "\"postalCode\":\"11000\",\"line1\":\"Example 1\"},"
                + "\"identityDocument\":{\"type\":\"PASSPORT\",\"issuingCountry\":\"RS\","
                + "\"number\":\"" + document + "\",\"expiresOn\":\"2030-01-01\"},"
                + "\"timezone\":\"Europe/Belgrade\"}";
    }

    private String extract(String body,
                           String field) {
        var matcher = Pattern.compile("\\\"" + Pattern.quote(field) + "\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
                .matcher(body);
        if (!matcher.find()) throw new AssertionError("Missing field " + field + " in " + body);
        return matcher.group(1);
    }

    private record Response(int status, String body, String etag) {
    }
}
