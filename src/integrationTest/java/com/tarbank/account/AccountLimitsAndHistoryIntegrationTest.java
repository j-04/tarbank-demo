package com.tarbank.account;

import com.tarbank.account.persistence.AccountFeatureQueryRepository;
import com.tarbank.money.domain.LimitOperationType;
import com.tarbank.money.persistence.MoneyQueryRepository;
import com.tarbank.support.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Timeout(value = 45, unit = TimeUnit.SECONDS)
class AccountLimitsAndHistoryIntegrationTest extends AbstractIntegrationTest {
    private static final Instant FIXED_TIME = Instant.parse("2026-01-15T12:00:00Z");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JsonMapper json;

    @MockitoBean
    private Clock clock;

    @MockitoSpyBean
    private MoneyQueryRepository moneyQueries;

    @MockitoSpyBean
    private AccountFeatureQueryRepository featureQueries;

    @BeforeEach
    void useFixedClock() {
        when(clock.instant()).thenReturn(FIXED_TIME);
    }

    @AfterEach
    void resetSpies() {
        reset(moneyQueries, featureQueries);
    }

    @Test
    void dailyLimitUpdatesEnforceRangeOwnershipEtagsIdempotencyAndAuditing() throws Exception {
        String manager = login("manager", "TestPass123!");
        Long ownerId = createCustomer("limitownerone", "L8000001", manager);
        Long otherId = createCustomer("limitotherone", "L8000002", manager);
        String owner = login("limitownerone", "StrongPwd123");
        String other = login("limitotherone", "StrongPwd123");
        String account = createAccount(ownerId, "EUR", manager);

        Response details = detail(account, owner);
        assertThat(details.status()).isEqualTo(200);
        assertThat(details.etag()).isEqualTo("\"account-v0\"");
        assertThat(details.body()).contains(
                "\"withdrawal\":{\"amount\":\"1000.0000\",\"expiresAt\":null}",
                "\"transfer\":{\"amount\":\"1000.0000\",\"expiresAt\":null}");

        assertThat(updateLimits(account, null, "account-v0", owner,
                                "{\"withdrawalLimit\":\"1500.0000\"}").status()).isEqualTo(400);
        assertThat(updateLimits(account, UUID.randomUUID()
                                             .toString(), null, owner,
                                "{\"withdrawalLimit\":\"1500.0000\"}").status()).isEqualTo(428);
        assertThat(updateLimits(account, UUID.randomUUID()
                                             .toString(), "account-v0", owner,
                                "{}").status()).isEqualTo(400);
        Response belowMinimum = updateLimits(account, UUID.randomUUID()
                                                          .toString(), "account-v0", owner,
                                             "{\"withdrawalLimit\":\"999.9999\"}");
        assertThat(belowMinimum.status()).isEqualTo(422);
        assertThat(belowMinimum.body()).contains("\"code\":\"DAILY_LIMIT_OUT_OF_RANGE\"");
        Response aboveMaximum = updateLimits(account, UUID.randomUUID()
                                                          .toString(), "account-v0", owner,
                                             "{\"transferLimit\":\"3000.0001\"}");
        assertThat(aboveMaximum.status()).isEqualTo(422);
        assertThat(aboveMaximum.body()).contains("\"code\":\"DAILY_LIMIT_OUT_OF_RANGE\"");
        Response extremeExponent = updateLimits(account, UUID.randomUUID()
                                                             .toString(), "account-v0", owner,
                                                "{\"withdrawalLimit\":\"1e2147483647\"}");
        assertThat(extremeExponent.status()).isEqualTo(422);
        assertThat(extremeExponent.body()).contains("\"code\":\"DAILY_LIMIT_OUT_OF_RANGE\"");
        assertThat(overrideCount(account)).isZero();
        assertThat(accountVersion(account)).isZero();
        assertThat(updateLimits(account, UUID.randomUUID()
                                             .toString(), "account-v0", owner,
                                "{\"withdrawalLimit\":\"1000.00001\"}").status()).isEqualTo(400);

        String firstKey = UUID.randomUUID()
                              .toString();
        Response first = updateLimits(account, firstKey, "account-v0", owner,
                                      "{\"withdrawalLimit\":\"1500.0000\"}");
        assertThat(first.status()).isEqualTo(200);
        assertThat(first.etag()).isEqualTo("\"account-v1\"");
        assertThat(first.body()).contains(
                "\"withdrawalLimit\":\"1500.0000\"",
                "\"transferLimit\":\"1000.0000\"",
                "\"expiresAt\":\"2026-01-15T23:00:00Z\"");
        assertOverride(account, "WITHDRAWAL", "1500.0000", "2026-01-15", "2026-01-15T23:00:00Z");
        assertThat(overrideCount(account)).isEqualTo(1);
        assertThat(accountVersion(account)).isEqualTo(1);
        assertThat(limitAuditCount(account)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                                               select metadata::text from audit_events
                                               where action='DAILY_LIMIT_UPDATED' and target_id=?
                                               """, String.class, account)).contains(
                "oldWithdrawalLimit", "1000.0000", "newWithdrawalLimit", "1500.0000");

        Response replay = updateLimits(account, firstKey, "account-v0", owner,
                                       "{\"withdrawalLimit\":\"1500.0000\"}");
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.etag()).isEqualTo("\"account-v1\"");
        assertThat(json.readTree(replay.body())
                       .path("data"))
                .isEqualTo(json.readTree(first.body())
                               .path("data"));
        assertThat(overrideCount(account)).isEqualTo(1);
        assertThat(limitAuditCount(account)).isEqualTo(1);
        Response refreshedEtagReplay = updateLimits(account, firstKey, "account-v1", owner,
                                                    "{\"withdrawalLimit\":\"1500.0000\"}");
        assertThat(refreshedEtagReplay.status()).isEqualTo(200);
        assertThat(refreshedEtagReplay.etag()).isEqualTo("\"account-v1\"");
        assertThat(json.readTree(refreshedEtagReplay.body())
                       .path("data"))
                .isEqualTo(json.readTree(first.body())
                               .path("data"));
        assertThat(overrideCount(account)).isEqualTo(1);
        assertThat(limitAuditCount(account)).isEqualTo(1);
        assertThat(updateLimits(account, firstKey, "account-v0", owner,
                                "{\"withdrawalLimit\":\"1600.0000\"}").status()).isEqualTo(409);

        Response noOp = updateLimits(account, UUID.randomUUID()
                                                  .toString(), "account-v1", owner,
                                     "{\"withdrawalLimit\":\"1500.0000\"}");
        assertThat(noOp.status()).isEqualTo(200);
        assertThat(noOp.etag()).isEqualTo("\"account-v1\"");
        assertThat(accountVersion(account)).isEqualTo(1);
        assertThat(overrideCount(account)).isEqualTo(1);
        assertThat(limitAuditCount(account)).isEqualTo(1);

        Response decrease = updateLimits(account, UUID.randomUUID()
                                                      .toString(), "account-v1", owner,
                                         "{\"withdrawalLimit\":\"1400.0000\"}");
        assertThat(decrease.status()).isEqualTo(422);
        assertThat(decrease.body()).contains("\"code\":\"DAILY_LIMIT_OUT_OF_RANGE\"");
        assertThat(updateLimits(account, UUID.randomUUID()
                                             .toString(), "account-v0", owner,
                                "{\"transferLimit\":\"2000.0000\"}").status()).isEqualTo(412);
        assertThat(updateLimits(account, UUID.randomUUID()
                                             .toString(), "account-v1", other,
                                "{\"transferLimit\":\"2000.0000\"}").status()).isEqualTo(403);

        Response maximum = updateLimits(account, UUID.randomUUID()
                                                     .toString(), "account-v1", manager,
                                        "{\"transferLimit\":\"3000.0000\"}");
        assertThat(maximum.status()).isEqualTo(200);
        assertThat(maximum.etag()).isEqualTo("\"account-v2\"");
        assertThat(overrideCount(account)).isEqualTo(2);
        assertOverride(account, "TRANSFER", "3000.0000", "2026-01-15", "2026-01-15T23:00:00Z");

        Response laterIncrease = updateLimits(account, UUID.randomUUID()
                                                           .toString(), "account-v2", owner,
                                              "{\"withdrawalLimit\":\"2000.0000\"}");
        assertThat(laterIncrease.status()).isEqualTo(200);
        assertThat(laterIncrease.etag()).isEqualTo("\"account-v3\"");
        assertThat(overrideCount(account)).isEqualTo(2);
        assertOverride(account, "WITHDRAWAL", "2000.0000", "2026-01-15", "2026-01-15T23:00:00Z");
        assertThat(accountVersion(account)).isEqualTo(3);
        assertThat(limitAuditCount(account)).isEqualTo(3);
        assertThat(detail(account, owner).body()).contains(
                "\"withdrawal\":{\"amount\":\"2000.0000\"",
                "\"transfer\":{\"amount\":\"3000.0000\"");
        assertThat(otherId).isPositive();
    }

    @Test
    void expiryUsesCustomerTimezoneAcrossDstAndAfterMidnightReplayKeepsTheOriginalResult() throws Exception {
        Instant dstDay = Instant.parse("2026-10-24T22:30:00Z");
        when(clock.instant()).thenReturn(dstDay);
        String manager = login("manager", "TestPass123!");
        Long ownerId = createCustomer("limitdstowner", "L8000003", manager);
        String owner = login("limitdstowner", "StrongPwd123");
        String account = createAccount(ownerId, "EUR", manager);
        String key = UUID.randomUUID()
                         .toString();

        Response first = updateLimits(account, key, "account-v0", owner,
                                      "{\"transferLimit\":\"1800.0000\"}");
        assertThat(first.status()).isEqualTo(200);
        assertThat(first.body()).contains("\"expiresAt\":\"2026-10-25T23:00:00Z\"");
        assertOverride(account, "TRANSFER", "1800.0000", "2026-10-25", "2026-10-25T23:00:00Z");
        assertThat(jdbc.queryForObject("""
                                               select response_body::text from api_request_idempotency
                                               where operation='DAILY_LIMIT_UPDATE' and idempotency_key=?
                                               """, String.class, UUID.fromString(key))).contains(
                "effectiveDate", "2026-10-25", "2026-10-25T23:00:00Z");

        when(clock.instant()).thenReturn(Instant.parse("2026-10-25T23:00:01Z"));
        Response replay = updateLimits(account, key, "account-v0", owner,
                                       "{\"transferLimit\":\"1800.0000\"}");
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.etag()).isEqualTo("\"account-v1\"");
        assertThat(json.readTree(replay.body())
                       .path("data"))
                .isEqualTo(json.readTree(first.body())
                               .path("data"));
        assertThat(overrideCount(account)).isEqualTo(1);

        Response expiredDetail = detail(account, owner);
        assertThat(expiredDetail.body()).contains(
                "\"transfer\":{\"amount\":\"1000.0000\",\"expiresAt\":null}");
        Response nextDay = updateLimits(account, UUID.randomUUID()
                                                     .toString(), "account-v1", owner,
                                        "{\"transferLimit\":\"1500.0000\"}");
        assertThat(nextDay.status()).isEqualTo(200);
        assertThat(nextDay.body()).contains("\"expiresAt\":\"2026-10-26T23:00:00Z\"");
        assertThat(overrideCount(account)).isEqualTo(2);
    }

    @Test
    void accountDetailsUseOneSnapshotAcrossAConcurrentAtomicLimitUpdate() throws Exception {
        String manager = login("manager", "TestPass123!");
        Long ownerId = createCustomer("limitsnapshot", "L8000008", manager);
        String owner = login("limitsnapshot", "StrongPwd123");
        String account = createAccount(ownerId, "EUR", manager);
        CountDownLatch firstLimitRead = new CountDownLatch(1);
        CountDownLatch resumeDetail = new CountDownLatch(1);
        AtomicBoolean pauseOnce = new AtomicBoolean();
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            if (invocation.getArgument(1) == LimitOperationType.WITHDRAWAL
                    && pauseOnce.compareAndSet(false, true)) {
                firstLimitRead.countDown();
                if (!resumeDetail.await(10, TimeUnit.SECONDS)) {
                    throw new AssertionError("Timed out waiting to resume account detail.");
                }
            }
            return result;
        }).when(featureQueries)
          .findEffectiveOverride(any(), any(), any(), any());

        Response concurrentDetail;
        try (var executor = Executors.newSingleThreadExecutor()) {
            var detailRequest = executor.submit(() -> detail(account, owner));
            assertThat(firstLimitRead.await(10, TimeUnit.SECONDS)).isTrue();
            Response update = updateLimits(
                    account, UUID.randomUUID()
                                 .toString(), "account-v0", owner,
                    "{\"withdrawalLimit\":\"2000.0000\",\"transferLimit\":\"2000.0000\"}");
            assertThat(update.status()).isEqualTo(200);
            assertThat(update.etag()).isEqualTo("\"account-v1\"");
            resumeDetail.countDown();
            concurrentDetail = detailRequest.get(10, TimeUnit.SECONDS);
        } finally {
            resumeDetail.countDown();
        }

        assertThat(concurrentDetail.status()).isEqualTo(200);
        assertThat(concurrentDetail.etag()).isEqualTo("\"account-v0\"");
        assertThat(concurrentDetail.body()).contains(
                                                   "\"withdrawal\":{\"amount\":\"1000.0000\",\"expiresAt\":null}",
                                                   "\"transfer\":{\"amount\":\"1000.0000\",\"expiresAt\":null}")
                                           .doesNotContain("\"amount\":\"2000.0000\"");

        Response afterCommit = detail(account, owner);
        assertThat(afterCommit.etag()).isEqualTo("\"account-v1\"");
        assertThat(afterCommit.body()).contains(
                "\"withdrawal\":{\"amount\":\"2000.0000\"",
                "\"transfer\":{\"amount\":\"2000.0000\"");
    }

    @Test
    void accountHistoryIsAuthorizedSafeFilteredAndCursorStableForTimestampTies() throws Exception {
        String manager = login("manager", "TestPass123!");
        Long ownerId = createCustomer("historyownerone", "L8000004", manager);
        Long recipientId = createCustomer("historyrecipient", "L8000005", manager);
        Long otherId = createCustomer("historyotherone", "L8000006", manager);
        String owner = login("historyownerone", "StrongPwd123");
        String other = login("historyotherone", "StrongPwd123");
        String source = createAccount(ownerId, "EUR", manager);
        String destination = createAccount(recipientId, "EUR", manager);

        Response deposit = deposit(source, "2000.0000", owner);
        Response withdrawal = withdraw(source, "25.0000", owner);
        Response transfer = transfer(source, destination, "50.0000", owner);
        Response failed = withdraw(source, "3000.0000", owner);
        assertThat(List.of(deposit.status(), withdrawal.status(), transfer.status()))
                .containsOnly(201);
        assertThat(failed.status()).isEqualTo(422);
        String failedTransactionId = jdbc.queryForObject("""
                                                                 select t.id::text from transactions t
                                                                 join accounts a on a.id=t.source_account_id
                                                                 where a.account_number=? and t.status='FAILED'
                                                                 order by t.created_at desc limit 1
                                                                 """, String.class, source);

        Response firstPage = history(source, owner, null, 2, null, null);
        assertThat(firstPage.status()).isEqualTo(200);
        assertThat(firstPage.body()).contains(
                                            "\"type\":\"TRANSFER\"", "\"type\":\"WITHDRAWAL\"",
                                            "\"status\":\"COMPLETED\"", "\"amountDelta\":\"-50.0000\"",
                                            "\"balanceAfter\":\"1925.0000\"", "\"currency\":\"EUR\"",
                                            "\"createdAt\":\"2026-01-15T12:00:00Z\"")
                                    .doesNotContain(failedTransactionId, "failureCode", "customerId", "accountNumber");
        String cursor = extract(firstPage.body(), "nextCursor");
        List<String> originalFirstIds = transactionIds(firstPage.body());
        assertThat(originalFirstIds).hasSize(2)
                                    .doesNotHaveDuplicates();

        Response later = deposit(source, "10.0000", owner);
        String laterId = extract(later.body(), "transactionId");
        Snapshot beforeReads = snapshot(source);
        Response secondPage = history(source, owner, cursor, 2, null, null);
        assertThat(secondPage.status()).isEqualTo(200);
        assertThat(secondPage.body()).doesNotContain(laterId, failedTransactionId);
        List<String> originalSecondIds = transactionIds(secondPage.body());
        assertThat(originalSecondIds).hasSize(1);
        Set<String> traversed = new HashSet<>(originalFirstIds);
        traversed.addAll(originalSecondIds);
        assertThat(traversed).hasSize(3);

        Response newTraversal = history(source, owner, null, 10, null, null);
        assertThat(newTraversal.body()).contains(laterId)
                                       .doesNotContain(failedTransactionId);
        assertThat(transactionIds(newTraversal.body())).hasSize(4)
                                                       .doesNotHaveDuplicates();
        assertThat(history(source, manager, null, 20, null, null).status()).isEqualTo(200);
        assertThat(history(source, other, null, 20, null, null).status()).isEqualTo(403);
        assertThat(history(source, owner, null, 0, null, null).status()).isEqualTo(400);
        assertThat(history(source, owner, null, 101, null, null).status()).isEqualTo(400);
        assertThat(history(source, owner, null, 20, FIXED_TIME, FIXED_TIME).status()).isEqualTo(400);
        assertThat(history(source, owner, cursor, 2, Instant.parse("2026-01-01T00:00:00Z"), null)
                           .status()).isEqualTo(400);
        assertThat(history(destination, owner, cursor, 2, null, null).status()).isEqualTo(403);
        assertThat(history(destination, manager, cursor, 2, null, null).status()).isEqualTo(400);
        assertThat(history(source, owner, null, 20, null, FIXED_TIME).body())
                .contains("\"items\":[]");
        assertThat(snapshot(source)).isEqualTo(beforeReads);
        assertThat(otherId).isPositive();
    }

    @Test
    void concurrentIncreaseAndWithdrawalSerializeOnTheAccountLockWithoutPartialState() throws Exception {
        String manager = login("manager", "TestPass123!");
        Long ownerId = createCustomer("limitraceowner", "L8000007", manager);
        String owner = login("limitraceowner", "StrongPwd123");
        String account = createAccount(ownerId, "EUR", manager);
        assertThat(deposit(account, "2000.0000", owner).status()).isEqualTo(201);
        Long accountId = jdbc.queryForObject(
                "select id from accounts where account_number=?", Long.class, account);
        CountDownLatch accountLocked = new CountDownLatch(1);
        CountDownLatch releaseMoneyOperation = new CountDownLatch(1);
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            accountLocked.countDown();
            if (!releaseMoneyOperation.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting to resume the withdrawal.");
            }
            return result;
        }).when(moneyQueries)
          .lockAccount(accountId);

        Response withdrawal;
        Response increase;
        try (var executor = Executors.newFixedThreadPool(2)) {
            var money = executor.submit(() -> withdraw(account, "1500.0000", owner));
            assertThat(accountLocked.await(10, TimeUnit.SECONDS)).isTrue();
            var limit = executor.submit(() -> updateLimits(
                    account, UUID.randomUUID()
                                 .toString(), "account-v0", owner,
                    "{\"withdrawalLimit\":\"2000.0000\",\"transferLimit\":\"2000.0000\"}"));
            awaitDatabaseLockWaiter();
            releaseMoneyOperation.countDown();
            withdrawal = money.get(10, TimeUnit.SECONDS);
            increase = limit.get(10, TimeUnit.SECONDS);
        } finally {
            releaseMoneyOperation.countDown();
        }

        assertThat(withdrawal.status()).isEqualTo(422);
        assertThat(withdrawal.body()).contains("\"code\":\"DAILY_LIMIT_EXCEEDED\"");
        assertThat(increase.status()).isEqualTo(200);
        assertThat(increase.etag()).isEqualTo("\"account-v1\"");
        assertThat(overrideCount(account)).isEqualTo(2);
        assertOverride(account, "WITHDRAWAL", "2000.0000", "2026-01-15", "2026-01-15T23:00:00Z");
        assertOverride(account, "TRANSFER", "2000.0000", "2026-01-15", "2026-01-15T23:00:00Z");
        assertThat(usageCount(account)).isZero();
        assertThat(entryCount(account)).isEqualTo(1);
        assertThat(balance(account)).isEqualByComparingTo("2000.0000");

        reset(moneyQueries);
        assertThat(withdraw(account, "1500.0000", owner).status()).isEqualTo(201);
        assertThat(usage(account, "WITHDRAWAL")).isEqualByComparingTo("1500.0000");
        assertThat(entryCount(account)).isEqualTo(2);
        assertThat(balance(account)).isEqualByComparingTo("500.0000");
    }

    private void awaitDatabaseLockWaiter() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Integer waiters = jdbc.queryForObject("""
                                                          select count(*) from pg_stat_activity
                                                          where datname=current_database() and wait_event_type='Lock'
                                                          """, Integer.class);
            if (waiters != null && waiters > 0) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("Timed out waiting for the competing database lock.");
    }

    private Response updateLimits(String account,
                                  String key,
                                  String etag,
                                  String token,
                                  String body) throws Exception {
        MockHttpServletRequestBuilder request = patch("/api/v1/accounts/{account}/daily-limits", account)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        if (etag != null) {
            request.header("If-Match", etag);
        }
        return perform(request);
    }

    private Response detail(String account,
                            String token) throws Exception {
        return perform(get("/api/v1/accounts/{account}", account)
                               .header("Authorization", "Bearer " + token));
    }

    private Response history(String account,
                             String token,
                             String cursor,
                             int limit,
                             Instant from,
                             Instant to) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/v1/accounts/{account}/transactions", account)
                .header("Authorization", "Bearer " + token)
                .param("limit", Integer.toString(limit));
        if (cursor != null) {
            request.param("cursor", cursor);
        }
        if (from != null) {
            request.param("from", from.toString());
        }
        if (to != null) {
            request.param("to", to.toString());
        }
        return perform(request);
    }

    private Response deposit(String account,
                             String amount,
                             String token) throws Exception {
        return money(post("/api/v1/accounts/{account}/deposits", account)
                             .content("{\"amount\":\"" + amount + "\"}"), token);
    }

    private Response withdraw(String account,
                              String amount,
                              String token) throws Exception {
        return money(post("/api/v1/accounts/{account}/withdrawals", account)
                             .content("{\"amount\":\"" + amount + "\"}"), token);
    }

    private Response transfer(String source,
                              String destination,
                              String amount,
                              String token) throws Exception {
        return money(post("/api/v1/accounts/{account}/transfers", source)
                             .content("{\"destinationAccountNumber\":\"" + destination
                                              + "\",\"amount\":\"" + amount + "\"}"), token);
    }

    private Response money(MockHttpServletRequestBuilder request,
                           String token) throws Exception {
        return perform(request.header("Authorization", "Bearer " + token)
                              .header("Idempotency-Key", UUID.randomUUID()
                                                             .toString())
                              .contentType(MediaType.APPLICATION_JSON));
    }

    private Response perform(MockHttpServletRequestBuilder request) throws Exception {
        var response = mvc.perform(request)
                          .andReturn()
                          .getResponse();
        return new Response(response.getStatus(), response.getContentAsString(), response.getHeader("ETag"));
    }

    private Long createCustomer(String username,
                                String document,
                                String manager) throws Exception {
        Response response = perform(post("/api/v1/customers")
                                            .header("Authorization", "Bearer " + manager)
                                            .header("Idempotency-Key", UUID.randomUUID()
                                                                           .toString())
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(customer(username, document)));
        return json.readTree(response.body())
                   .path("data")
                   .path("customerId")
                   .asLong();
    }

    private String createAccount(Long customerId,
                                 String currency,
                                 String manager) throws Exception {
        Response response = perform(post("/api/v1/customers/{id}/accounts", customerId)
                                            .header("Authorization", "Bearer " + manager)
                                            .header("Idempotency-Key", UUID.randomUUID()
                                                                           .toString())
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content("{\"currency\":\"" + currency + "\"}"));
        return extract(response.body(), "accountNumber");
    }

    private String login(String username,
                         String password) throws Exception {
        Response response = perform(post("/api/v1/auth/login")
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content("{\"username\":\"" + username
                                                             + "\",\"password\":\"" + password + "\"}"));
        return extract(response.body(), "accessToken");
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

    private void assertOverride(String account,
                                String operation,
                                String amount,
                                String effectiveDate,
                                String expiresAt) {
        var row = jdbc.queryForMap("""
                                           select o.limit_amount, o.effective_date::text effective_date,
                                                  to_char(o.expires_at at time zone 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"') expires_at
                                           from account_limit_overrides o
                                           join accounts a on a.id=o.account_id
                                           where a.account_number=? and o.operation_type=?
                                           order by o.effective_date desc limit 1
                                           """, account, operation);
        assertThat((BigDecimal) row.get("limit_amount")).isEqualByComparingTo(amount);
        assertThat(row.get("effective_date")).isEqualTo(effectiveDate);
        assertThat(row.get("expires_at")).isEqualTo(expiresAt);
    }

    private int overrideCount(String account) {
        return jdbc.queryForObject("""
                                           select count(*) from account_limit_overrides o
                                           join accounts a on a.id=o.account_id where a.account_number=?
                                           """, Integer.class, account);
    }

    private int accountVersion(String account) {
        return jdbc.queryForObject(
                "select management_version from accounts where account_number=?", Integer.class, account);
    }

    private int limitAuditCount(String account) {
        return jdbc.queryForObject("""
                                           select count(*) from audit_events
                                           where action='DAILY_LIMIT_UPDATED' and target_type='ACCOUNT' and target_id=?
                                           """, Integer.class, account);
    }

    private int entryCount(String account) {
        return jdbc.queryForObject("""
                                           select count(*) from transaction_entries e
                                           join accounts a on a.id=e.account_id where a.account_number=?
                                           """, Integer.class, account);
    }

    private int usageCount(String account) {
        return jdbc.queryForObject("""
                                           select count(*) from daily_limit_usage u
                                           join accounts a on a.id=u.account_id where a.account_number=?
                                           """, Integer.class, account);
    }

    private BigDecimal usage(String account,
                             String operation) {
        return jdbc.queryForObject("""
                                           select u.used_amount from daily_limit_usage u
                                           join accounts a on a.id=u.account_id
                                           where a.account_number=? and u.operation_type=?
                                           """, BigDecimal.class, account, operation);
    }

    private BigDecimal balance(String account) {
        return jdbc.queryForObject(
                "select balance from accounts where account_number=?", BigDecimal.class, account);
    }

    private Snapshot snapshot(String account) {
        var row = jdbc.queryForMap("""
                                           select a.balance, a.updated_at,
                                                  (select count(*) from transaction_entries e where e.account_id=a.id) entry_count,
                                                  (select count(*) from daily_limit_usage u where u.account_id=a.id) usage_count,
                                                  (select max(u.updated_at) from daily_limit_usage u where u.account_id=a.id) usage_updated_at
                                           from accounts a where a.account_number=?
                                           """, account);
        return new Snapshot((BigDecimal) row.get("balance"), row.get("updated_at"),
                            ((Number) row.get("entry_count")).longValue(),
                            ((Number) row.get("usage_count")).longValue(), row.get("usage_updated_at"));
    }

    private List<String> transactionIds(String body) {
        var matcher = Pattern.compile("\\\"transactionId\\\":\\\"([^\\\"]+)\\\"")
                             .matcher(body);
        return matcher.results()
                      .map(result -> result.group(1))
                      .toList();
    }

    private String extract(String body,
                           String field) {
        var matcher = Pattern.compile("\\\"" + Pattern.quote(field)
                                              + "\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
                             .matcher(body);
        if (!matcher.find()) {
            throw new AssertionError("Missing field " + field + " in " + body);
        }
        return matcher.group(1);
    }

    private record Response(int status, String body, String etag) {
    }

    private record Snapshot(BigDecimal balance, Object accountUpdatedAt, long entryCount,
                            long usageCount, Object usageUpdatedAt) {
    }
}
