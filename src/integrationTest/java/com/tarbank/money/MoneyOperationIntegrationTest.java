package com.tarbank.money;

import com.tarbank.common.application.RetentionCleanupService;
import com.tarbank.common.config.FailureSimulationProperties;
import com.tarbank.common.resilience.FailurePoint;
import com.tarbank.common.resilience.FailureSimulator;
import com.tarbank.common.resilience.FailureSimulator.Execution;
import com.tarbank.common.resilience.SimulatedFailureException;
import com.tarbank.money.application.MoneyOperationService;
import com.tarbank.money.application.NotificationService;
import com.tarbank.money.domain.TransactionType;
import com.tarbank.money.persistence.MoneyQueryRepository;
import com.tarbank.support.AbstractIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Timeout(value = 45, unit = TimeUnit.SECONDS)
class MoneyOperationIntegrationTest extends AbstractIntegrationTest {
    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JsonMapper json;

    @MockitoBean
    private Clock clock;

    @MockitoSpyBean
    private NotificationService notifications;

    @MockitoSpyBean
    private MoneyQueryRepository queries;

    @MockitoSpyBean
    private MoneyOperationService operations;

    @MockitoSpyBean
    private FailureSimulator failures;

    @Autowired
    private FailureSimulationProperties failureProperties;

    @Autowired
    private RetentionCleanupService cleanup;

    @Autowired
    private MeterRegistry metrics;

    @BeforeEach
    void useFixedClock() {
        when(clock.instant()).thenReturn(Instant.parse("2026-01-15T12:00:00Z"));
    }

    @AfterEach
    void resetNotificationAdapter() {
        reset(notifications, queries, operations, failures);
    }

    @Test
    void automatedTestsKeepFailureSimulationDisabled() {
        assertThat(failureProperties.enabled()).isFalse();
        assertThat(failureProperties.failureRate()).isZero();
    }

    @Test
    void forcedBeforeTransactionFailureLeavesNoDurableMoneyState() throws Exception {
        String manager = login("manager", "TestPass123!");
        Long ownerId = createCustomer("failurebefore", "M9000001", manager);
        String owner = login("failurebefore", "StrongPwd123");
        String account = createAccount(ownerId, "EUR", manager);
        String key = UUID.randomUUID().toString();
        int transactionsBefore = transactionCount(ownerId);
        int entriesBefore = customerEntryCount(ownerId);
        int auditsBefore = moneyAuditCount();

        doReturn(Execution.at(FailurePoint.BEFORE_TRANSACTION)).when(failures)
                                                                    .select(TransactionType.DEPOSIT);
        Response result = deposit(account, "15.0000", key, owner);

        assertThat(result.status()).isEqualTo(500);
        assertThat(result.body()).contains("\"code\":\"INTERNAL_ERROR\"")
                                 .doesNotContain("BEFORE_TRANSACTION");
        assertThat(balance(account)).isEqualByComparingTo("0.0000");
        assertThat(transactionCount(ownerId)).isEqualTo(transactionsBefore);
        assertThat(customerEntryCount(ownerId)).isEqualTo(entriesBefore);
        assertThat(moneyAuditCount()).isEqualTo(auditsBefore);
        assertThat(customerMoneyIdempotencyCount(ownerId)).isZero();
        assertThat(jdbc.queryForObject(
                "select count(*) from money_operation_idempotency where idempotency_key=?",
                Integer.class, UUID.fromString(key))).isZero();
    }

    @Test
    void forcedDuringTransactionFailureRollsBackEverythingAndCanRetry() throws Exception {
        String manager = login("manager", "TestPass123!");
        Long ownerId = createCustomer("failureduring", "M9000002", manager);
        String owner = login("failureduring", "StrongPwd123");
        String account = createAccount(ownerId, "EUR", manager);
        assertThat(deposit(account, "100.0000", UUID.randomUUID().toString(), owner).status())
                .isEqualTo(201);
        String key = UUID.randomUUID().toString();
        int transactionsBefore = transactionCount(ownerId);
        int entriesBefore = customerEntryCount(ownerId);
        int auditsBefore = moneyAuditCount();
        int idempotencyBefore = customerMoneyIdempotencyCount(ownerId);

        doReturn(Execution.at(FailurePoint.DURING_TRANSACTION_BEFORE_COMMIT)).when(failures)
                                                                                 .select(TransactionType.WITHDRAWAL);
        Response result = withdraw(account, "25.0000", key, owner);

        assertThat(result.status()).isEqualTo(500);
        assertThat(balance(account)).isEqualByComparingTo("100.0000");
        assertThat(transactionCount(ownerId)).isEqualTo(transactionsBefore);
        assertThat(customerEntryCount(ownerId)).isEqualTo(entriesBefore);
        assertThat(moneyAuditCount()).isEqualTo(auditsBefore);
        assertThat(customerMoneyIdempotencyCount(ownerId)).isEqualTo(idempotencyBefore);
        assertThat(dailyUsageRowCount(account)).isZero();

        doReturn(Execution.none()).when(failures).select(TransactionType.WITHDRAWAL);
        assertThat(withdraw(account, "25.0000", key, owner).status()).isEqualTo(201);
        assertThat(balance(account)).isEqualByComparingTo("75.0000");
        assertThat(usage(account, "WITHDRAWAL")).isEqualByComparingTo("25.0000");
        assertThat(jdbc.queryForObject("""
                                               select count(*) from money_operation_idempotency
                                               where idempotency_key=? and status='COMPLETED'
                                               """, Integer.class, UUID.fromString(key))).isEqualTo(1);
    }

    @Test
    void forcedAfterCommitFailurePersistsOneReplayableCompletion() throws Exception {
        String manager = login("manager", "TestPass123!");
        Long ownerId = createCustomer("failureafter", "M9000003", manager);
        String owner = login("failureafter", "StrongPwd123");
        String account = createAccount(ownerId, "EUR", manager);
        String key = UUID.randomUUID().toString();

        doReturn(Execution.at(FailurePoint.AFTER_COMMIT_BEFORE_RESPONSE)).when(failures)
                                                                           .select(TransactionType.DEPOSIT);
        Response first = deposit(account, "40.0000", key, owner);

        assertThat(first.status()).isEqualTo(500);
        assertThat(balance(account)).isEqualByComparingTo("40.0000");
        assertThat(transactionCount(ownerId)).isEqualTo(1);
        assertThat(customerEntryCount(ownerId)).isEqualTo(1);
        assertThat(customerMoneyIdempotencyCount(ownerId)).isEqualTo(1);

        Response replay = deposit(account, "40.0000", key, owner);

        assertThat(replay.status()).isEqualTo(201);
        assertThat(balance(account)).isEqualByComparingTo("40.0000");
        assertThat(transactionCount(ownerId)).isEqualTo(1);
        assertThat(customerEntryCount(ownerId)).isEqualTo(1);
        assertThat(customerMoneyIdempotencyCount(ownerId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                                               select count(*) from money_operation_idempotency
                                               where idempotency_key=? and status='COMPLETED'
                                               """, Integer.class, UUID.fromString(key))).isEqualTo(1);
    }

    @Test
    void cleanupDeletesOnlyExpiredMutableRetentionRecords() throws Exception {
        String manager = login("manager", "TestPass123!");
        Long ownerId = createCustomer("cleanupowner", "M9000004", manager);
        String owner = login("cleanupowner", "StrongPwd123");
        String account = createAccount(ownerId, "EUR", manager);
        UUID completedMoneyKey = UUID.randomUUID();
        Response deposit = deposit(account, "50.0000", completedMoneyKey.toString(), owner);
        assertThat(deposit.status()).isEqualTo(201);
        UUID transactionId = UUID.fromString(extract(deposit.body(), "transactionId"));
        Long accountId = jdbc.queryForObject(
                "select id from accounts where account_number=?", Long.class, account);
        Long managerId = jdbc.queryForObject(
                "select id from users where username='manager'", Long.class);
        Timestamp expired = Timestamp.from(Instant.parse("2026-01-14T12:00:00Z"));
        Timestamp unexpired = Timestamp.from(Instant.parse("2026-01-16T12:00:00Z"));
        UUID activeMoneyKey = UUID.randomUUID();
        UUID expiredApiKey = UUID.randomUUID();
        UUID activeApiKey = UUID.randomUUID();
        UUID unexpiredApiKey = UUID.randomUUID();

        jdbc.update("update money_operation_idempotency set expires_at=? where idempotency_key=?",
                    expired, completedMoneyKey);
        jdbc.update("""
                            insert into money_operation_idempotency(
                                customer_id, account_id, operation_type, idempotency_key,
                                request_hash, status, expires_at, created_at, updated_at)
                            values (?, ?, 'DEPOSIT', ?, ?, 'IN_PROGRESS', ?, ?, ?)
                            """, ownerId, accountId, activeMoneyKey, "a".repeat(64),
                    expired, expired, expired);
        jdbc.update("""
                            insert into api_request_idempotency(
                                actor_user_id, operation, resource_scope, idempotency_key,
                                request_hash, status, response_status, response_body,
                                expires_at, created_at, updated_at)
                            values (?, 'CLEANUP_TEST', 'expired', ?, ?, 'COMPLETED', 200,
                                    cast(? as jsonb), ?, ?, ?)
                            """, managerId, expiredApiKey, "b".repeat(64), "{}",
                    expired, expired, expired);
        jdbc.update("""
                            insert into api_request_idempotency(
                                actor_user_id, operation, resource_scope, idempotency_key,
                                request_hash, status, expires_at, created_at, updated_at)
                            values (?, 'CLEANUP_TEST', 'active', ?, ?, 'IN_PROGRESS', ?, ?, ?)
                            """, managerId, activeApiKey, "c".repeat(64), expired, expired, expired);
        jdbc.update("""
                            insert into api_request_idempotency(
                                actor_user_id, operation, resource_scope, idempotency_key,
                                request_hash, status, response_status, response_body,
                                expires_at, created_at, updated_at)
                            values (?, 'CLEANUP_TEST', 'unexpired', ?, ?, 'COMPLETED', 200,
                                    cast(? as jsonb), ?, ?, ?)
                            """, managerId, unexpiredApiKey, "d".repeat(64), "{}",
                    unexpired, expired, expired);
        jdbc.update("""
                            insert into account_limit_overrides(
                                account_id, operation_type, limit_amount, effective_date,
                                expires_at, updated_by_user_id, created_at, updated_at)
                            values (?, 'WITHDRAWAL', 1500.0000, ?, ?, ?, ?, ?)
                            """, accountId, LocalDate.parse("2026-01-14"), expired,
                    managerId, expired, expired);
        jdbc.update("""
                            insert into account_limit_overrides(
                                account_id, operation_type, limit_amount, effective_date,
                                expires_at, updated_by_user_id, created_at, updated_at)
                            values (?, 'TRANSFER', 1500.0000, ?, ?, ?, ?, ?)
                            """, accountId, LocalDate.parse("2026-01-15"), unexpired,
                    managerId, expired, expired);
        jdbc.update("""
                            insert into daily_limit_usage(
                                account_id, operation_type, usage_date, used_amount, updated_at)
                            values (?, 'WITHDRAWAL', ?, 10.0000, ?)
                            """, accountId, LocalDate.parse("2025-01-01"), expired);
        jdbc.update("""
                            insert into daily_limit_usage(
                                account_id, operation_type, usage_date, used_amount, updated_at)
                            values (?, 'TRANSFER', ?, 10.0000, ?)
                            """, accountId, LocalDate.parse("2026-01-15"), expired);
        int transactionCount = jdbc.queryForObject("select count(*) from transactions", Integer.class);
        int entryCount = jdbc.queryForObject("select count(*) from transaction_entries", Integer.class);
        int auditCount = jdbc.queryForObject("select count(*) from audit_events", Integer.class);

        RetentionCleanupService.CleanupResult result = cleanup.cleanup();

        assertThat(result.lockAcquired()).isTrue();
        assertThat(result.overrides()).isEqualTo(1);
        assertThat(result.moneyIdempotency()).isEqualTo(1);
        assertThat(result.apiIdempotency()).isGreaterThanOrEqualTo(1);
        assertThat(result.dailyUsage()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select count(*) from money_operation_idempotency where idempotency_key=?",
                Integer.class, completedMoneyKey)).isZero();
        assertThat(jdbc.queryForObject(
                "select count(*) from money_operation_idempotency where idempotency_key=?",
                Integer.class, activeMoneyKey)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select count(*) from api_request_idempotency where idempotency_key=?",
                Integer.class, expiredApiKey)).isZero();
        assertThat(jdbc.queryForObject(
                "select count(*) from api_request_idempotency where idempotency_key in (?, ?)",
                Integer.class, activeApiKey, unexpiredApiKey)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "select count(*) from account_limit_overrides where account_id=?",
                Integer.class, accountId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select count(*) from daily_limit_usage where account_id=?",
                Integer.class, accountId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from transactions", Integer.class))
                .isEqualTo(transactionCount);
        assertThat(jdbc.queryForObject("select count(*) from transaction_entries", Integer.class))
                .isEqualTo(entryCount);
        assertThat(jdbc.queryForObject("select count(*) from audit_events", Integer.class))
                .isEqualTo(auditCount);
        assertThat(jdbc.queryForObject(
                "select count(*) from transactions where id=?", Integer.class, transactionId))
                .isEqualTo(1);
    }

    @Test
    void completedOperationsPersistExactAtomicFinancialRecords() throws Exception {
        String manager = login("manager", "TestPass123!");
        Long ownerId = createCustomer("moneyownerone", "M6000001", manager);
        Long recipientId = createCustomer("moneyrecipientone", "M6000002", manager);
        String owner = login("moneyownerone", "StrongPwd123");
        String source = createAccount(ownerId, "EUR", manager);
        String destination = createAccount(recipientId, "EUR", manager);

        Response deposit = deposit(source, "200.0000", UUID.randomUUID()
                                                           .toString(), owner);
        Response withdrawal = withdraw(source, "25.0000", UUID.randomUUID()
                                                              .toString(), owner);
        Response transfer = transfer(source, destination, "50.0000", UUID.randomUUID()
                                                                         .toString(), owner);

        assertThat(List.of(deposit.status(), withdrawal.status(), transfer.status())).containsOnly(201);
        UUID depositId = UUID.fromString(extract(deposit.body(), "transactionId"));
        UUID withdrawalId = UUID.fromString(extract(withdrawal.body(), "transactionId"));
        UUID transferId = UUID.fromString(extract(transfer.body(), "transactionId"));
        assertThat(deposit.body()).contains(
                "\"status\":\"COMPLETED\"", "\"amount\":\"200.0000\"",
                "\"currency\":\"EUR\"", "\"balanceAfter\":\"200.0000\"");
        assertThat(withdrawal.body()).contains(
                "\"status\":\"COMPLETED\"", "\"balanceAfter\":\"175.0000\"");
        assertThat(transfer.body()).contains(
                "\"status\":\"COMPLETED\"", "\"sourceAccountNumber\":\"" + source + "\"",
                "\"destinationAccountNumber\":\"" + destination + "\"");

        assertThat(balance(source)).isEqualByComparingTo("125.0000");
        assertThat(balance(destination)).isEqualByComparingTo("50.0000");
        assertTransaction(depositId, "DEPOSIT", "COMPLETED", "200.0000", null);
        assertTransaction(withdrawalId, "WITHDRAWAL", "COMPLETED", "25.0000", null);
        assertTransaction(transferId, "TRANSFER", "COMPLETED", "50.0000", null);

        assertEntries(depositId, List.of(new Entry("200.0000", "200.0000")));
        assertEntries(withdrawalId, List.of(new Entry("-25.0000", "175.0000")));
        assertEntries(transferId, List.of(
                new Entry("-50.0000", "125.0000"),
                new Entry("50.0000", "50.0000")));

        assertThat(usage(source, "WITHDRAWAL")).isEqualByComparingTo("25.0000");
        assertThat(usage(source, "TRANSFER")).isEqualByComparingTo("50.0000");
        assertThat(jdbc.queryForObject(
                "select count(*) from daily_limit_usage where account_id=(select id from accounts where account_number=?)",
                Integer.class, destination)).isZero();
        for (UUID id : List.of(depositId, withdrawalId, transferId)) {
            assertThat(jdbc.queryForObject(
                    "select count(*) from audit_events where target_type='TRANSACTION' and target_id=?",
                    Integer.class, id.toString())).isEqualTo(1);
            assertThat(jdbc.queryForObject(
                    "select count(*) from money_operation_idempotency where transaction_id=? and status='COMPLETED'",
                    Integer.class, id)).isEqualTo(1);
        }
        assertThat(jdbc.queryForObject(
                "select count(*) from api_request_idempotency where operation like 'DEPOSIT%' or operation like 'WITHDRAWAL%' or operation like 'TRANSFER%'",
                Integer.class)).isZero();
    }

    @Test
    void completedAndFailedRequestsReplayWhileChangedInputConflicts() throws Exception {
        String manager = login("manager", "TestPass123!");
        Long ownerId = createCustomer("moneyidemowner", "M6000003", manager);
        String owner = login("moneyidemowner", "StrongPwd123");
        String account = createAccount(ownerId, "USD", manager);

        String depositKey = UUID.randomUUID()
                                .toString();
        Response firstDeposit = deposit(account, "40", depositKey, owner);
        Response replayedDeposit = deposit(account, "40.0000", depositKey, owner);
        assertThat(replayedDeposit.status()).isEqualTo(201);
        assertThat(extract(replayedDeposit.body(), "transactionId"))
                .isEqualTo(extract(firstDeposit.body(), "transactionId"));
        assertThat(balance(account)).isEqualByComparingTo("40.0000");
        assertThat(deposit(account, "41.0000", depositKey, owner).status()).isEqualTo(409);

        String withdrawalKey = UUID.randomUUID()
                                   .toString();
        Response firstFailure = withdraw(account, "50.0000", withdrawalKey, owner);
        Response replayedFailure = withdraw(account, "50", withdrawalKey, owner);
        assertThat(firstFailure.status()).isEqualTo(422);
        assertThat(replayedFailure.status()).isEqualTo(422);
        assertThat(firstFailure.body()).contains("\"code\":\"INSUFFICIENT_FUNDS\"");
        assertThat(replayedFailure.body()).contains("\"code\":\"INSUFFICIENT_FUNDS\"");
        assertThat(withdraw(account, "51.0000", withdrawalKey, owner).status()).isEqualTo(409);

        assertThat(jdbc.queryForObject(
                "select count(*) from transactions where initiated_by_customer_id=?",
                Integer.class, ownerId)).isEqualTo(2);
        UUID failedId = jdbc.queryForObject(
                "select id from transactions where initiated_by_customer_id=? and status='FAILED'",
                UUID.class, ownerId);
        assertThat(failedId).isNotNull();
        assertThat(jdbc.queryForObject(
                "select count(*) from transaction_entries where transaction_id=?",
                Integer.class, failedId)).isZero();
        assertThat(jdbc.queryForObject(
                "select status from money_operation_idempotency where transaction_id=?",
                String.class, failedId)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject(
                "select count(*) from audit_events where target_id=?",
                Integer.class, failedId.toString())).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select count(*) from daily_limit_usage where account_id=(select id from accounts where account_number=?)",
                Integer.class, account)).isZero();
    }

    @Test
    void businessRulesPersistFailuresWithoutMovingMoneyOrConsumingLimits() throws Exception {
        String manager = login("manager", "TestPass123!");
        Long ownerId = createCustomer("moneyrulesowner", "M6000004", manager);
        Long recipientId = createCustomer("moneyrulesrecipient", "M6000005", manager);
        Long otherId = createCustomer("moneyrulesother", "M6000006", manager);
        String owner = login("moneyrulesowner", "StrongPwd123");
        String otherOwner = login("moneyrulesother", "StrongPwd123");
        String source = createAccount(ownerId, "EUR", manager);
        String eurDestination = createAccount(recipientId, "EUR", manager);
        String usdDestination = createAccount(recipientId, "USD", manager);

        assertThat(deposit(source, "2000.0000", UUID.randomUUID()
                                                    .toString(), owner).status()).isEqualTo(201);
        assertFailure(withdraw(source, "4.9999", UUID.randomUUID()
                                                     .toString(), owner),
                      "MINIMUM_WITHDRAWAL_AMOUNT");
        assertFailure(withdraw(source, "1000.0001", UUID.randomUUID()
                                                        .toString(), owner),
                      "DAILY_LIMIT_EXCEEDED");
        assertFailure(transfer(source, eurDestination, "1000.0001", UUID.randomUUID()
                                                                        .toString(), owner),
                      "DAILY_LIMIT_EXCEEDED");
        assertFailure(transfer(source, usdDestination, "10.0000", UUID.randomUUID()
                                                                      .toString(), owner),
                      "CURRENCY_MISMATCH");

        String empty = createAccount(ownerId, "EUR", manager);
        assertFailure(withdraw(empty, "5.0000", UUID.randomUUID()
                                                    .toString(), owner),
                      "INSUFFICIENT_FUNDS");

        patchAccountStatus(source, "BLOCKED", manager);
        assertFailure(deposit(source, "10.0000", UUID.randomUUID()
                                                     .toString(), owner),
                      "ACCOUNT_NOT_ACTIVE");
        assertFailure(withdraw(source, "10.0000", UUID.randomUUID()
                                                      .toString(), owner),
                      "ACCOUNT_NOT_ACTIVE");
        assertFailure(transfer(source, eurDestination, "10.0000", UUID.randomUUID()
                                                                      .toString(), owner),
                      "ACCOUNT_NOT_ACTIVE");

        assertThat(balance(source)).isEqualByComparingTo("2000.0000");
        assertThat(balance(eurDestination)).isEqualByComparingTo("0.0000");
        assertThat(balance(usdDestination)).isEqualByComparingTo("0.0000");
        assertThat(balance(empty)).isEqualByComparingTo("0.0000");
        assertThat(jdbc.queryForObject("""
                                               select count(*) from transaction_entries e
                                               join transactions t on t.id=e.transaction_id
                                               where t.initiated_by_customer_id=? and t.status='FAILED'
                                               """, Integer.class, ownerId)).isZero();
        assertThat(jdbc.queryForObject("""
                                               select count(*) from daily_limit_usage u
                                               join accounts a on a.id=u.account_id
                                               where a.customer_id=?
                                               """, Integer.class, ownerId)).isZero();
        assertThat(jdbc.queryForObject("""
                                               select count(*) from transactions t
                                               where t.initiated_by_customer_id=? and t.status='FAILED'
                                                 and not exists (select 1 from audit_events e where e.target_id=t.id::text)
                                               """, Integer.class, ownerId)).isZero();
        assertThat(jdbc.queryForObject("""
                                               select count(*) from transactions t
                                               where t.initiated_by_customer_id=? and t.status='FAILED'
                                                 and not exists (select 1 from money_operation_idempotency i
                                                                 where i.transaction_id=t.id and i.status='FAILED')
                                               """, Integer.class, ownerId)).isZero();

        int transactionCount = transactionCount(ownerId);
        assertThat(transfer(source, source, "10.0000", UUID.randomUUID()
                                                           .toString(), owner).status()).isEqualTo(400);
        assertThat(deposit(source, "0", UUID.randomUUID()
                                            .toString(), owner).status()).isEqualTo(400);
        assertThat(deposit(source, "1.00001", UUID.randomUUID()
                                                  .toString(), owner).status()).isEqualTo(400);
        assertThat(deposit(source, "10.0000", "not-a-uuid", owner).status()).isEqualTo(400);
        assertThat(deposit(source, "10.0000", UUID.randomUUID()
                                                  .toString(), otherOwner).status()).isEqualTo(403);
        assertThat(deposit(source, "10.0000", UUID.randomUUID()
                                                  .toString(), manager).status()).isEqualTo(403);
        assertThat(deposit(source, "10.0000", UUID.randomUUID()
                                                  .toString(), null).status()).isEqualTo(401);
        assertThat(depositWithoutKey(source, "10.0000", owner).status()).isEqualTo(400);
        assertThat(transactionCount(ownerId)).isEqualTo(transactionCount);
        assertThat(otherId).isPositive();
    }

    @Test
    void concurrentDepositsRefreshBalanceAfterTheAccountLock() throws Exception {
        String manager = login("manager", "TestPass123!");
        Long ownerId = createCustomer("moneydepositrace", "M6000008", manager);
        String owner = login("moneydepositrace", "StrongPwd123");
        String account = createAccount(ownerId, "EUR", manager);
        String delayedKey = UUID.randomUUID()
                                .toString();
        CountDownLatch idempotencyLoaded = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        pauseAfterIdempotency(delayedKey, idempotencyLoaded, resume);

        Response delayed;
        Response competing;
        try (var executor = Executors.newFixedThreadPool(2)) {
            var delayedRequest = executor.submit(() -> deposit(account, "200.0000", delayedKey, owner));
            assertThat(idempotencyLoaded.await(10, TimeUnit.SECONDS)).isTrue();
            competing = executor.submit(() -> deposit(
                                        account, "100.0000", UUID.randomUUID()
                                                                 .toString(), owner))
                                .get(10, TimeUnit.SECONDS);
            resume.countDown();
            delayed = delayedRequest.get(10, TimeUnit.SECONDS);
        } finally {
            resume.countDown();
        }

        assertThat(List.of(delayed.status(), competing.status())).containsOnly(201);
        assertReconciled(account, "300.0000");
    }

    @Test
    void concurrentWithdrawalsRefreshFundsAfterTheAccountLock() throws Exception {
        String manager = login("manager", "TestPass123!");
        Long ownerId = createCustomer("moneywithdrawrace", "M6000009", manager);
        String owner = login("moneywithdrawrace", "StrongPwd123");
        String account = createAccount(ownerId, "EUR", manager);
        assertThat(deposit(account, "100.0000", UUID.randomUUID()
                                                    .toString(), owner).status()).isEqualTo(201);
        String delayedKey = UUID.randomUUID()
                                .toString();
        CountDownLatch idempotencyLoaded = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        pauseAfterIdempotency(delayedKey, idempotencyLoaded, resume);

        Response delayed;
        Response competing;
        try (var executor = Executors.newFixedThreadPool(2)) {
            var delayedRequest = executor.submit(() -> withdraw(account, "70.0000", delayedKey, owner));
            assertThat(idempotencyLoaded.await(10, TimeUnit.SECONDS)).isTrue();
            competing = executor.submit(() -> withdraw(
                                        account, "70.0000", UUID.randomUUID()
                                                                .toString(), owner))
                                .get(10, TimeUnit.SECONDS);
            resume.countDown();
            delayed = delayedRequest.get(10, TimeUnit.SECONDS);
        } finally {
            resume.countDown();
        }

        assertThat(competing.status()).isEqualTo(201);
        assertFailure(delayed, "INSUFFICIENT_FUNDS");
        assertReconciled(account, "30.0000");
        assertThat(usage(account, "WITHDRAWAL")).isEqualByComparingTo("70.0000");
    }

    @Test
    void withdrawalAndTransferUseTheRefreshedSharedSourceBalance() throws Exception {
        String manager = login("manager", "TestPass123!");
        Long ownerId = createCustomer("moneymixedrace", "M6000010", manager);
        Long recipientId = createCustomer("moneymixedrecipient", "M6000011", manager);
        String owner = login("moneymixedrace", "StrongPwd123");
        String source = createAccount(ownerId, "EUR", manager);
        String destination = createAccount(recipientId, "EUR", manager);
        assertThat(deposit(source, "100.0000", UUID.randomUUID()
                                                   .toString(), owner).status()).isEqualTo(201);
        String delayedKey = UUID.randomUUID()
                                .toString();
        CountDownLatch idempotencyLoaded = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        pauseAfterIdempotency(delayedKey, idempotencyLoaded, resume);

        Response delayed;
        Response competing;
        try (var executor = Executors.newFixedThreadPool(2)) {
            var delayedRequest = executor.submit(() -> transfer(
                    source, destination, "70.0000", delayedKey, owner));
            assertThat(idempotencyLoaded.await(10, TimeUnit.SECONDS)).isTrue();
            competing = executor.submit(() -> withdraw(
                                        source, "70.0000", UUID.randomUUID()
                                                               .toString(), owner))
                                .get(10, TimeUnit.SECONDS);
            resume.countDown();
            delayed = delayedRequest.get(10, TimeUnit.SECONDS);
        } finally {
            resume.countDown();
        }

        assertThat(competing.status()).isEqualTo(201);
        assertFailure(delayed, "INSUFFICIENT_FUNDS");
        assertReconciled(source, "30.0000");
        assertReconciled(destination, "0.0000");
        assertThat(jdbc.queryForObject("""
                                               select count(*) from daily_limit_usage u
                                               join accounts a on a.id=u.account_id
                                               where a.account_number=? and u.operation_type='TRANSFER'
                                               """, Integer.class, source)).isZero();
    }

    @Test
    void concurrentTransfersRefreshTheSharedDestinationBalance() throws Exception {
        String manager = login("manager", "TestPass123!");
        Long firstOwnerId = createCustomer("moneytransferone", "M6000012", manager);
        Long secondOwnerId = createCustomer("moneytransfertwo", "M6000013", manager);
        Long recipientId = createCustomer("moneytransferrecipient", "M6000014", manager);
        String firstOwner = login("moneytransferone", "StrongPwd123");
        String secondOwner = login("moneytransfertwo", "StrongPwd123");
        String firstSource = createAccount(firstOwnerId, "EUR", manager);
        String secondSource = createAccount(secondOwnerId, "EUR", manager);
        String destination = createAccount(recipientId, "EUR", manager);
        assertThat(deposit(firstSource, "100.0000", UUID.randomUUID()
                                                        .toString(), firstOwner).status())
                .isEqualTo(201);
        assertThat(deposit(secondSource, "100.0000", UUID.randomUUID()
                                                         .toString(), secondOwner).status())
                .isEqualTo(201);
        String delayedKey = UUID.randomUUID()
                                .toString();
        CountDownLatch idempotencyLoaded = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        pauseAfterIdempotency(delayedKey, idempotencyLoaded, resume);

        Response delayed;
        Response competing;
        try (var executor = Executors.newFixedThreadPool(2)) {
            var delayedRequest = executor.submit(() -> transfer(
                    firstSource, destination, "60.0000", delayedKey, firstOwner));
            assertThat(idempotencyLoaded.await(10, TimeUnit.SECONDS)).isTrue();
            competing = executor.submit(() -> transfer(
                                        secondSource, destination, "40.0000", UUID.randomUUID()
                                                                                  .toString(), secondOwner))
                                .get(10, TimeUnit.SECONDS);
            resume.countDown();
            delayed = delayedRequest.get(10, TimeUnit.SECONDS);
        } finally {
            resume.countDown();
        }

        assertThat(List.of(delayed.status(), competing.status())).containsOnly(201);
        UUID competingId = UUID.fromString(extract(competing.body(), "transactionId"));
        UUID delayedId = UUID.fromString(extract(delayed.body(), "transactionId"));
        assertAccountEntry(competingId, secondSource, "-40.0000", "60.0000");
        assertAccountEntry(competingId, destination, "40.0000", "40.0000");
        assertAccountEntry(delayedId, firstSource, "-60.0000", "40.0000");
        assertAccountEntry(delayedId, destination, "60.0000", "100.0000");
        assertReconciled(firstSource, "40.0000");
        assertReconciled(secondSource, "60.0000");
        assertReconciled(destination, "100.0000");
    }

    @Test
    void balanceCapacityFailuresAreDurableAndReplayable() throws Exception {
        String manager = login("manager", "TestPass123!");
        Long ownerId = createCustomer("moneybalancecapacity", "M6000015", manager);
        String owner = login("moneybalancecapacity", "StrongPwd123");
        String full = createAccount(ownerId, "EUR", manager);
        String source = createAccount(ownerId, "EUR", manager);
        assertThat(deposit(full, "999999999999999.9999", UUID.randomUUID()
                                                             .toString(), owner).status())
                .isEqualTo(201);
        assertThat(deposit(source, "10.0000", UUID.randomUUID()
                                                  .toString(), owner).status()).isEqualTo(201);

        String depositKey = UUID.randomUUID()
                                .toString();
        Response depositFailure = deposit(full, "0.0001", depositKey, owner);
        Response depositReplay = deposit(full, "0.0001", depositKey, owner);
        String transferKey = UUID.randomUUID()
                                 .toString();
        Response transferFailure = transfer(source, full, "1.0000", transferKey, owner);
        Response transferReplay = transfer(source, full, "1.0000", transferKey, owner);

        for (Response response : List.of(depositFailure, depositReplay, transferFailure, transferReplay)) {
            assertFailure(response, "BALANCE_LIMIT_EXCEEDED");
        }
        assertReconciled(full, "999999999999999.9999");
        assertReconciled(source, "10.0000");
        assertThat(jdbc.queryForObject("""
                                               select count(*) from transactions
                                               where initiated_by_customer_id=? and status='FAILED'
                                                 and failure_code='BALANCE_LIMIT_EXCEEDED'
                                               """, Integer.class, ownerId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                                               select count(*) from transaction_entries e
                                               join transactions t on t.id=e.transaction_id
                                               where t.initiated_by_customer_id=? and t.failure_code='BALANCE_LIMIT_EXCEEDED'
                                               """, Integer.class, ownerId)).isZero();
        assertThat(jdbc.queryForObject("""
                                               select count(*) from money_operation_idempotency
                                               where idempotency_key in (?, ?) and status='FAILED'
                                               """, Integer.class, UUID.fromString(depositKey), UUID.fromString(transferKey))).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                                               select count(*) from audit_events e
                                               join transactions t on e.target_id=t.id::text
                                               where t.initiated_by_customer_id=? and t.failure_code='BALANCE_LIMIT_EXCEEDED'
                                               """, Integer.class, ownerId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                                               select count(*) from daily_limit_usage u
                                               join accounts a on a.id=u.account_id
                                               where a.account_number=?
                                               """, Integer.class, source)).isZero();
    }

    @Test
    void notificationFailureCannotUndoCommitAndFinancialHistoryIsImmutable() throws Exception {
        String manager = login("manager", "TestPass123!");
        Long ownerId = createCustomer("moneynotifyowner", "M6000007", manager);
        String owner = login("moneynotifyowner", "StrongPwd123");
        String account = createAccount(ownerId, "EUR", manager);
        doThrow(new IllegalStateException("adapter unavailable")).when(notifications)
                                                                 .send(any());
        double failuresBefore = metrics.counter("tarbank.notifications", "outcome", "failure")
                                       .count();

        Response result = deposit(account, "15.0000", UUID.randomUUID()
                                                          .toString(), owner);

        assertThat(result.status()).isEqualTo(201);
        UUID transactionId = UUID.fromString(extract(result.body(), "transactionId"));
        assertThat(balance(account)).isEqualByComparingTo("15.0000");
        assertTransaction(transactionId, "DEPOSIT", "COMPLETED", "15.0000", null);
        assertThatThrownBy(() -> jdbc.update(
                "update transactions set amount=16.0000 where id=?", transactionId))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update(
                "delete from transaction_entries where transaction_id=?", transactionId))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update(
                "update audit_events set action='CHANGED' where target_id=?", transactionId.toString()))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update(
                "update transaction_entries set balance_after=16.0000 where transaction_id=?", transactionId))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update(
                "delete from audit_events where target_id=?", transactionId.toString()))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update(
                "delete from transactions where id=?", transactionId))
                .isInstanceOf(DataAccessException.class);
        assertThat(jdbc.queryForObject(
                "select count(*) from transaction_entries where transaction_id=?",
                Integer.class, transactionId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select count(*) from audit_events where target_id=?",
                Integer.class, transactionId.toString())).isEqualTo(1);
        assertThat(metrics.counter("tarbank.notifications", "outcome", "failure").count())
                .isEqualTo(failuresBefore + 1.0);
    }


    @Test
    void oppositeDirectionTransfersCompleteWithoutDeadlock() throws Exception {
        String manager = login("manager", "TestPass123!");
        Long firstOwnerId = createCustomer("phase7oppositeone", "M7000001", manager);
        Long secondOwnerId = createCustomer("phase7oppositetwo", "M7000002", manager);
        String firstOwner = login("phase7oppositeone", "StrongPwd123");
        String secondOwner = login("phase7oppositetwo", "StrongPwd123");
        String firstAccount = createAccount(firstOwnerId, "EUR", manager);
        String secondAccount = createAccount(secondOwnerId, "EUR", manager);
        assertThat(deposit(firstAccount, "200.0000", UUID.randomUUID()
                                                         .toString(), firstOwner).status())
                .isEqualTo(201);
        assertThat(deposit(secondAccount, "200.0000", UUID.randomUUID()
                                                          .toString(), secondOwner).status())
                .isEqualTo(201);

        CyclicBarrier beforeLocks = new CyclicBarrier(2);
        doAnswer(invocation -> {
            beforeLocks.await(10, TimeUnit.SECONDS);
            return invocation.callRealMethod();
        }).when(queries)
          .lockAccounts(anyLong(), anyLong());

        List<Response> responses = concurrently(
                () -> transfer(firstAccount, secondAccount, "50.0000",
                               UUID.randomUUID()
                                   .toString(), firstOwner),
                () -> transfer(secondAccount, firstAccount, "50.0000",
                               UUID.randomUUID()
                                   .toString(), secondOwner));

        assertThat(responses).allSatisfy(response -> assertThat(response.status()).isEqualTo(201));
        for (Response response : responses) {
            UUID transactionId = UUID.fromString(extract(response.body(), "transactionId"));
            assertThat(jdbc.queryForObject(
                    "select count(*) from transaction_entries where transaction_id=?",
                    Integer.class, transactionId)).isEqualTo(2);
            assertThat(jdbc.queryForObject(
                    "select sum(amount_delta) from transaction_entries where transaction_id=?",
                    BigDecimal.class, transactionId)).isEqualByComparingTo("0.0000");
        }
        assertReconciled(firstAccount, "200.0000");
        assertReconciled(secondAccount, "200.0000");
        assertThat(jdbc.queryForObject(
                "select count(*) from accounts where balance < 0", Integer.class)).isZero();
        assertAllAccountsReconciled();
    }

    @Test
    void moneyOperationAndStatusChangeHaveOneSerialOrder() throws Exception {
        String manager = login("manager", "TestPass123!");
        Long ownerId = createCustomer("phase7statusrace", "M7000003", manager);
        String owner = login("phase7statusrace", "StrongPwd123");
        String account = createAccount(ownerId, "EUR", manager);
        assertThat(deposit(account, "40.0000", UUID.randomUUID()
                                                   .toString(), owner).status())
                .isEqualTo(201);

        Long accountId = jdbc.queryForObject(
                "select id from accounts where account_number=?", Long.class, account);
        CountDownLatch moneyLocked = new CountDownLatch(1);
        CountDownLatch resumeMoney = new CountDownLatch(1);
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            moneyLocked.countDown();
            if (!resumeMoney.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out while holding the account lock.");
            }
            return result;
        }).when(queries)
          .lockAccount(accountId);

        Response moneyResult;
        Response statusResult;
        try (var executor = Executors.newFixedThreadPool(2)) {
            var moneyFuture = executor.submit(
                    () -> deposit(account, "10.0000", UUID.randomUUID()
                                                          .toString(), owner));
            assertThat(moneyLocked.await(10, TimeUnit.SECONDS)).isTrue();
            var statusFuture = executor.submit(() -> patchAccountStatusResponse(account, manager));
            awaitDatabaseLockWaiter();
            resumeMoney.countDown();
            moneyResult = moneyFuture.get(15, TimeUnit.SECONDS);
            statusResult = statusFuture.get(15, TimeUnit.SECONDS);
        } finally {
            resumeMoney.countDown();
        }

        assertThat(moneyResult.status()).isEqualTo(201);
        assertThat(statusResult.status()).isEqualTo(200);
        assertThat(jdbc.queryForObject(
                "select status from accounts where account_number=?", String.class, account))
                .isEqualTo("BLOCKED");
        assertFailure(deposit(account, "5.0000", UUID.randomUUID()
                                                     .toString(), owner),
                      "ACCOUNT_NOT_ACTIVE");
        assertReconciled(account, "50.0000");
    }

    @Test
    void dailyUsageIsAtomicSeparatedAndBoundToTheCustomerLocalDate() throws Exception {
        String manager = login("manager", "TestPass123!");
        Long ownerId = createCustomer("phase7usagerace", "M7000004", manager);
        String owner = login("phase7usagerace", "StrongPwd123");
        String account = createAccount(ownerId, "EUR", manager);
        assertThat(deposit(account, "2000.0000", UUID.randomUUID()
                                                     .toString(), owner).status())
                .isEqualTo(201);
        assertThat(dailyUsageRowCount(account)).isZero();

        Long accountId = jdbc.queryForObject(
                "select id from accounts where account_number=?", Long.class, account);
        CyclicBarrier beforeLock = new CyclicBarrier(2);
        doAnswer(invocation -> {
            beforeLock.await(10, TimeUnit.SECONDS);
            return invocation.callRealMethod();
        }).when(queries)
          .lockAccount(accountId);

        List<Response> responses = concurrently(
                () -> withdraw(account, "600.0000", UUID.randomUUID()
                                                        .toString(), owner),
                () -> withdraw(account, "600.0000", UUID.randomUUID()
                                                        .toString(), owner));

        assertThat(responses.stream()
                            .map(Response::status)
                            .toList())
                .containsExactlyInAnyOrder(201, 422);
        assertThat(responses.stream()
                            .filter(response -> response.status() == 422)
                            .findFirst()
                            .orElseThrow()
                            .body()).contains("\"code\":\"DAILY_LIMIT_EXCEEDED\"");
        assertThat(dailyUsageRowCount(account)).isEqualTo(1);
        assertThat(usage(account, "WITHDRAWAL")).isEqualByComparingTo("600.0000");
        assertReconciled(account, "1400.0000");
        assertThat(jdbc.queryForObject(
                "select count(*) from daily_limit_usage where used_amount > 1000.0000",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject("""
                                               select count(*) from (
                                                   select account_id, operation_type, usage_date
                                                   from daily_limit_usage
                                                   group by account_id, operation_type, usage_date
                                                   having count(*) > 1
                                               ) duplicate_usage
                                               """, Integer.class)).isZero();
        reset(queries);

        String separateAccount = createAccount(ownerId, "EUR", manager);
        String destination = createAccount(ownerId, "EUR", manager);
        assertThat(deposit(separateAccount, "2000.0000",
                           UUID.randomUUID()
                               .toString(), owner).status()).isEqualTo(201);
        assertThat(dailyUsageRowCount(separateAccount)).isZero();
        assertThat(withdraw(separateAccount, "600.0000",
                            UUID.randomUUID()
                                .toString(), owner).status()).isEqualTo(201);
        assertThat(transfer(separateAccount, destination, "600.0000",
                            UUID.randomUUID()
                                .toString(), owner).status()).isEqualTo(201);
        assertThat(dailyUsageRowCount(separateAccount)).isEqualTo(2);
        assertThat(usage(separateAccount, "WITHDRAWAL")).isEqualByComparingTo("600.0000");
        assertThat(usage(separateAccount, "TRANSFER")).isEqualByComparingTo("600.0000");
        assertThat(dailyUsageRowCount(destination)).isZero();
        assertThat(deposit(separateAccount, "10.0000",
                           UUID.randomUUID()
                               .toString(), owner).status()).isEqualTo(201);
        assertThat(usage(separateAccount, "WITHDRAWAL")).isEqualByComparingTo("600.0000");
        assertThat(usage(separateAccount, "TRANSFER")).isEqualByComparingTo("600.0000");

        String transferSource = createAccount(ownerId, "EUR", manager);
        String firstTransferDestination = createAccount(ownerId, "EUR", manager);
        String secondTransferDestination = createAccount(ownerId, "EUR", manager);
        assertThat(deposit(transferSource, "2000.0000",
                           UUID.randomUUID()
                               .toString(), owner).status()).isEqualTo(201);
        String firstTransferKey = UUID.randomUUID()
                                      .toString();
        String secondTransferKey = UUID.randomUUID()
                                       .toString();
        CyclicBarrier beforeTransferLocks = new CyclicBarrier(2);
        doAnswer(invocation -> {
            beforeTransferLocks.await(10, TimeUnit.SECONDS);
            return invocation.callRealMethod();
        }).when(queries)
          .lockAccounts(anyLong(), anyLong());

        List<Response> transferResponses = concurrently(
                () -> transfer(transferSource, firstTransferDestination, "600.0000",
                               firstTransferKey, owner),
                () -> transfer(transferSource, secondTransferDestination, "600.0000",
                               secondTransferKey, owner));

        assertThat(transferResponses.stream()
                                    .map(Response::status)
                                    .toList())
                .containsExactlyInAnyOrder(201, 422);
        assertThat(transferResponses.stream()
                                    .filter(response -> response.status() == 422)
                                    .findFirst()
                                    .orElseThrow()
                                    .body()).contains("\"code\":\"DAILY_LIMIT_EXCEEDED\"");
        assertThat(dailyUsageRowCount(transferSource)).isEqualTo(1);
        assertThat(usage(transferSource, "TRANSFER")).isEqualByComparingTo("600.0000");
        assertThat(jdbc.queryForObject("""
                                               select count(*) from transaction_entries e
                                               join money_operation_idempotency i on i.transaction_id=e.transaction_id
                                               where i.idempotency_key in (?, ?)
                                               """, Integer.class, UUID.fromString(firstTransferKey),
                                       UUID.fromString(secondTransferKey))).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                                               select count(*) from transactions t
                                               join money_operation_idempotency i on i.transaction_id=t.id
                                               where i.idempotency_key in (?, ?) and t.status='FAILED'
                                                 and t.failure_code='DAILY_LIMIT_EXCEEDED'
                                               """, Integer.class, UUID.fromString(firstTransferKey),
                                       UUID.fromString(secondTransferKey))).isEqualTo(1);
        assertReconciled(transferSource, "1400.0000");
        assertThat(balance(firstTransferDestination).add(balance(secondTransferDestination)))
                .isEqualByComparingTo("600.0000");
        assertAllAccountsReconciled();
        reset(queries);

        when(clock.instant()).thenReturn(Instant.parse("2026-01-01T22:59:59Z"));
        String midnightAccount = createAccount(ownerId, "EUR", manager);
        String midnightTransferSource = createAccount(ownerId, "EUR", manager);
        String midnightTransferDestination = createAccount(ownerId, "EUR", manager);
        assertThat(deposit(midnightAccount, "1500.0000",
                           UUID.randomUUID()
                               .toString(), owner).status()).isEqualTo(201);
        assertThat(deposit(midnightTransferSource, "1500.0000",
                           UUID.randomUUID()
                               .toString(), owner).status()).isEqualTo(201);
        assertThat(withdraw(midnightAccount, "600.0000",
                            UUID.randomUUID()
                                .toString(), owner).status()).isEqualTo(201);
        assertThat(transfer(midnightTransferSource, midnightTransferDestination, "600.0000",
                            UUID.randomUUID()
                                .toString(), owner).status()).isEqualTo(201);
        when(clock.instant()).thenReturn(Instant.parse("2026-01-01T23:00:01Z"));
        assertThat(withdraw(midnightAccount, "600.0000",
                            UUID.randomUUID()
                                .toString(), owner).status()).isEqualTo(201);
        assertThat(transfer(midnightTransferSource, midnightTransferDestination, "600.0000",
                            UUID.randomUUID()
                                .toString(), owner).status()).isEqualTo(201);
        assertThat(jdbc.query("""
                                      select usage_date::text || ':' || used_amount::text
                                      from daily_limit_usage u
                                      join accounts a on a.id=u.account_id
                                      where a.account_number=? and u.operation_type='WITHDRAWAL'
                                      order by usage_date
                                      """, (resultSet, rowNumber) -> resultSet.getString(1), midnightAccount))
                .containsExactly("2026-01-01:600.0000", "2026-01-02:600.0000");
        assertReconciled(midnightAccount, "300.0000");
        assertThat(jdbc.query("""
                                      select usage_date::text || ':' || used_amount::text
                                      from daily_limit_usage u
                                      join accounts a on a.id=u.account_id
                                      where a.account_number=? and u.operation_type='TRANSFER'
                                      order by usage_date
                                      """, (resultSet, rowNumber) -> resultSet.getString(1), midnightTransferSource))
                .containsExactly("2026-01-01:600.0000", "2026-01-02:600.0000");
        assertReconciled(midnightTransferSource, "300.0000");
        assertReconciled(midnightTransferDestination, "1200.0000");
        assertAllAccountsReconciled();
    }

    @Test
    void matchingIdempotencyRaceHasOneDurableOutcomeAndRetriesReplayIt() throws Exception {
        String manager = login("manager", "TestPass123!");
        Long ownerId = createCustomer("phase7idempotency", "M7000005", manager);
        String owner = login("phase7idempotency", "StrongPwd123");
        String account = createAccount(ownerId, "EUR", manager);
        String key = UUID.randomUUID()
                         .toString();

        CyclicBarrier beforeReservation = new CyclicBarrier(2);
        doAnswer(invocation -> {
            beforeReservation.await(10, TimeUnit.SECONDS);
            return invocation.callRealMethod();
        }).when(queries)
          .findAccountScope(account);

        List<Response> responses = concurrently(
                () -> deposit(account, "25.0000", key, owner),
                () -> deposit(account, "25.0000", key, owner));

        assertThat(responses).allSatisfy(response -> {
            assertThat(response.status()).isIn(201, 409);
            if (response.status() == 409) {
                assertThat(response.body()).contains("\"code\":\"REQUEST_IN_PROGRESS\"");
            }
        });
        List<Response> completed = responses.stream()
                                            .filter(response -> response.status() == 201)
                                            .toList();
        assertThat(completed).isNotEmpty();
        assertThat(completed.stream()
                            .map(response -> extract(response.body(), "transactionId"))
                            .distinct()).hasSize(1);
        Response original = completed.getFirst();
        UUID transactionId = UUID.fromString(extract(original.body(), "transactionId"));
        assertThat(jdbc.queryForObject(
                "select count(*) from money_operation_idempotency where idempotency_key=?",
                Integer.class, UUID.fromString(key))).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select count(*) from transactions where id=?",
                Integer.class, transactionId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select count(*) from transaction_entries where transaction_id=?",
                Integer.class, transactionId)).isEqualTo(1);
        assertThat(balance(account)).isEqualByComparingTo("25.0000");
        reset(queries);

        assertThat(deposit(account, "5.0000", UUID.randomUUID()
                                                  .toString(), owner).status())
                .isEqualTo(201);
        assertThat(patchAccountStatusResponse(account, manager).status()).isEqualTo(200);
        assertThat(jdbc.queryForObject(
                "select management_version from accounts where account_number=?",
                Integer.class, account)).isEqualTo(1);
        int transactionsBeforeReplay = transactionCount(ownerId);
        int entriesBeforeReplay = customerEntryCount(ownerId);
        int auditsBeforeReplay = moneyAuditCount();
        int usageBeforeReplay = dailyUsageRowCount(account);

        Response replay = deposit(account, "25", key, owner);
        var originalEnvelope = json.readTree(original.body());
        var replayEnvelope = json.readTree(replay.body());
        assertThat(replay.status()).isEqualTo(original.status());
        assertThat(replayEnvelope.path("data")).isEqualTo(originalEnvelope.path("data"));
        assertThat(replayEnvelope.path("data")
                                 .path("balanceAfter")
                                 .asString())
                .isEqualTo("25.0000");
        assertThat(replayEnvelope.path("data")
                                 .path("completedAt"))
                .isEqualTo(originalEnvelope.path("data")
                                           .path("completedAt"));
        assertThat(replayEnvelope.path("correlationId")
                                 .asString())
                .isNotEqualTo(originalEnvelope.path("correlationId")
                                              .asString());
        assertThat(transactionCount(ownerId)).isEqualTo(transactionsBeforeReplay);
        assertThat(customerEntryCount(ownerId)).isEqualTo(entriesBeforeReplay);
        assertThat(moneyAuditCount()).isEqualTo(auditsBeforeReplay);
        assertThat(dailyUsageRowCount(account)).isEqualTo(usageBeforeReplay);
        assertThat(balance(account)).isEqualByComparingTo("30.0000");

        Response conflict = deposit(account, "26.0000", key, owner);
        assertThat(conflict.status()).isEqualTo(409);
        assertThat(conflict.body()).contains("\"code\":\"IDEMPOTENCY_CONFLICT\"");
        assertThat(patchAccountStatusResponse(account, "ACTIVE", "account-v1", manager).status())
                .isEqualTo(200);
        assertThat(balance(account)).isEqualByComparingTo("30.0000");

        String failedKey = UUID.randomUUID()
                               .toString();
        assertFailure(withdraw(account, "40.0000", failedKey, owner), "INSUFFICIENT_FUNDS");
        assertFailure(withdraw(account, "40", failedKey, owner), "INSUFFICIENT_FUNDS");
        assertThat(jdbc.queryForObject("""
                                               select count(*) from transactions t
                                               join money_operation_idempotency i on i.transaction_id=t.id
                                               where i.idempotency_key=? and t.status='FAILED'
                                               """, Integer.class, UUID.fromString(failedKey))).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                                               select count(*) from transaction_entries e
                                               join money_operation_idempotency i on i.transaction_id=e.transaction_id
                                               where i.idempotency_key=?
                                               """, Integer.class, UUID.fromString(failedKey))).isZero();
        assertAllAccountsReconciled();
    }

    @Test
    void preCommitExceptionRollsBackEverythingAndTheSameKeyCanRetry() throws Exception {
        String manager = login("manager", "TestPass123!");
        Long ownerId = createCustomer("phase7rollback", "M7000006", manager);
        String owner = login("phase7rollback", "StrongPwd123");
        String account = createAccount(ownerId, "EUR", manager);
        assertThat(deposit(account, "100.0000", UUID.randomUUID()
                                                    .toString(), owner).status())
                .isEqualTo(201);
        String key = UUID.randomUUID()
                         .toString();

        int transactionsBefore = transactionCount(ownerId);
        int entriesBefore = customerEntryCount(ownerId);
        int auditsBefore = moneyAuditCount();
        int idempotencyBefore = customerMoneyIdempotencyCount(ownerId);
        int usageBefore = dailyUsageRowCount(account);
        AtomicBoolean writesFlushed = new AtomicBoolean();
        AtomicBoolean hookReached = new AtomicBoolean();
        UUID idempotencyKey = UUID.fromString(key);
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            entityManager.flush();
            writesFlushed.set(true);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void beforeCommit(boolean readOnly) {
                    hookReached.set(true);
                    throw new TestRollbackException();
                }
            });
            return result;
        }).when(operations)
          .withdraw(eq(account), any(), eq(idempotencyKey),
                    eq(new BigDecimal("25.0000")), any(Execution.class));

        var result = mvc.perform(post("/api/v1/accounts/{account}/withdrawals", account)
                                         .header("Authorization", "Bearer " + owner)
                                         .header("Idempotency-Key", key)
                                         .contentType(MediaType.APPLICATION_JSON)
                                         .content("{\"amount\":\"25.0000\"}"))
                        .andReturn();

        assertThat(result.getResponse()
                         .getStatus()).isEqualTo(500);
        assertThat(result.getResolvedException()).isInstanceOf(TestRollbackException.class);
        assertThat(writesFlushed.get()).isTrue();
        assertThat(hookReached.get()).isTrue();
        assertThat(balance(account)).isEqualByComparingTo("100.0000");
        assertThat(transactionCount(ownerId)).isEqualTo(transactionsBefore);
        assertThat(customerEntryCount(ownerId)).isEqualTo(entriesBefore);
        assertThat(moneyAuditCount()).isEqualTo(auditsBefore);
        assertThat(customerMoneyIdempotencyCount(ownerId)).isEqualTo(idempotencyBefore);
        assertThat(dailyUsageRowCount(account)).isEqualTo(usageBefore);
        assertThat(jdbc.queryForObject(
                "select count(*) from money_operation_idempotency where idempotency_key=?",
                Integer.class, idempotencyKey)).isZero();
        assertAllAccountsReconciled();
        reset(operations);

        assertThat(withdraw(account, "25.0000", key, owner).status()).isEqualTo(201);
        assertThat(usage(account, "WITHDRAWAL")).isEqualByComparingTo("25.0000");
        assertReconciled(account, "75.0000");
        assertThat(jdbc.queryForObject("""
                                               select count(*) from money_operation_idempotency
                                               where idempotency_key=? and status='COMPLETED'
                                               """, Integer.class, UUID.fromString(key))).isEqualTo(1);
    }

    private List<Response> concurrently(Callable<Response> first,
                                        Callable<Response> second) throws Exception {
        try (var executor = Executors.newFixedThreadPool(2)) {
            var firstResult = executor.submit(first);
            var secondResult = executor.submit(second);
            return List.of(firstResult.get(15, TimeUnit.SECONDS),
                           secondResult.get(15, TimeUnit.SECONDS));
        }
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

    private Response patchAccountStatusResponse(String account,
                                                String manager) throws Exception {
        return patchAccountStatusResponse(account, "BLOCKED", "account-v0", manager);
    }

    private Response patchAccountStatusResponse(String account,
                                                String status,
                                                String etag,
                                                String manager) throws Exception {
        var response = mvc.perform(patch("/api/v1/accounts/{account}/status", account)
                                           .header("Authorization", "Bearer " + manager)
                                           .header("If-Match", etag)
                                           .contentType(MediaType.APPLICATION_JSON)
                                           .content("{\"status\":\"" + status + "\"}"))
                          .andReturn()
                          .getResponse();
        return new Response(response.getStatus(), response.getContentAsString());
    }

    private int dailyUsageRowCount(String account) {
        return jdbc.queryForObject("""
                                           select count(*) from daily_limit_usage u
                                           join accounts a on a.id=u.account_id
                                           where a.account_number=?
                                           """, Integer.class, account);
    }

    private int customerEntryCount(Long customerId) {
        return jdbc.queryForObject("""
                                           select count(*) from transaction_entries e
                                           join transactions t on t.id=e.transaction_id
                                           where t.initiated_by_customer_id=?
                                           """, Integer.class, customerId);
    }

    private int moneyAuditCount() {
        return jdbc.queryForObject("""
                                           select count(*) from audit_events
                                           where target_type='TRANSACTION' and action like 'MONEY_OPERATION_%'
                                           """, Integer.class);
    }

    private int customerMoneyIdempotencyCount(Long customerId) {
        return jdbc.queryForObject(
                "select count(*) from money_operation_idempotency where customer_id=?",
                Integer.class, customerId);
    }

    private void assertAllAccountsReconciled() {
        assertThat(jdbc.queryForObject("""
                                               select count(*) from (
                                                   select a.id
                                                   from accounts a
                                                   left join transaction_entries e on e.account_id=a.id
                                                   group by a.id, a.balance
                                                   having a.balance <> coalesce(sum(e.amount_delta), 0)
                                               ) unreconciled
                                               """, Integer.class)).isZero();
    }

    private void pauseAfterIdempotency(String key,
                                       CountDownLatch entered,
                                       CountDownLatch resume) {
        org.mockito.Mockito.doAnswer(invocation -> {
               Object result = invocation.callRealMethod();
               if (UUID.fromString(key)
                       .equals(invocation.getArgument(3))) {
                   entered.countDown();
                   if (!resume.await(15, TimeUnit.SECONDS)) {
                       throw new AssertionError("Timed out waiting to resume the money operation.");
                   }
               }
               return result;
           })
                           .when(queries)
                           .lockIdempotency(any(), any(), any(), any());
    }

    private void assertReconciled(String accountNumber,
                                  String expectedBalance) {
        BigDecimal stored = balance(accountNumber);
        BigDecimal ledger = jdbc.queryForObject("""
                                                        select coalesce(sum(e.amount_delta), 0)
                                                        from transaction_entries e
                                                        join accounts a on a.id=e.account_id
                                                        where a.account_number=?
                                                        """, BigDecimal.class, accountNumber);
        assertThat(stored).isEqualByComparingTo(expectedBalance);
        assertThat(stored).isEqualByComparingTo(ledger);
    }

    private void assertFailure(Response response,
                               String code) {
        assertThat(response.status()).isEqualTo(422);
        assertThat(response.body()).contains("\"code\":\"" + code + "\"");
    }

    private void assertTransaction(UUID id,
                                   String type,
                                   String status,
                                   String amount,
                                   String failureCode) {
        var row = jdbc.queryForMap("""
                                           select type, status, amount, currency, failure_code, correlation_id
                                           from transactions where id=?
                                           """, id);
        assertThat(row.get("type")).isEqualTo(type);
        assertThat(row.get("status")).isEqualTo(status);
        assertThat((BigDecimal) row.get("amount")).isEqualByComparingTo(amount);
        assertThat(row.get("currency")).isEqualTo("EUR");
        assertThat(row.get("failure_code")).isEqualTo(failureCode);
        assertThat(row.get("correlation_id")).isNotNull();
    }

    private void assertEntries(UUID transactionId,
                               List<Entry> expected) {
        List<Entry> entries = jdbc.query("""
                                                 select amount_delta, balance_after
                                                 from transaction_entries
                                                 where transaction_id=?
                                                 order by amount_delta
                                                 """, (rs, rowNum) -> new Entry(
                rs.getBigDecimal("amount_delta")
                  .toPlainString(),
                rs.getBigDecimal("balance_after")
                  .toPlainString()), transactionId);
        assertThat(entries).hasSize(expected.size());
        for (Entry entry : expected) {
            assertThat(entries).anySatisfy(actual -> {
                assertThat(new BigDecimal(actual.delta())).isEqualByComparingTo(entry.delta());
                assertThat(new BigDecimal(actual.balanceAfter())).isEqualByComparingTo(entry.balanceAfter());
            });
        }
    }

    private void assertAccountEntry(UUID transactionId,
                                    String accountNumber,
                                    String expectedDelta,
                                    String expectedBalanceAfter) {
        var row = jdbc.queryForMap("""
                                           select e.amount_delta, e.balance_after
                                           from transaction_entries e
                                           join accounts a on a.id=e.account_id
                                           where e.transaction_id=? and a.account_number=?
                                           """, transactionId, accountNumber);
        assertThat((BigDecimal) row.get("amount_delta")).isEqualByComparingTo(expectedDelta);
        assertThat((BigDecimal) row.get("balance_after")).isEqualByComparingTo(expectedBalanceAfter);
    }

    private BigDecimal balance(String accountNumber) {
        return jdbc.queryForObject("select balance from accounts where account_number=?",
                                   BigDecimal.class, accountNumber);
    }

    private BigDecimal usage(String accountNumber,
                             String type) {
        return jdbc.queryForObject("""
                                           select u.used_amount from daily_limit_usage u
                                           join accounts a on a.id=u.account_id
                                           where a.account_number=? and u.operation_type=?
                                           """, BigDecimal.class, accountNumber, type);
    }

    private int transactionCount(Long customerId) {
        return jdbc.queryForObject(
                "select count(*) from transactions where initiated_by_customer_id=?",
                Integer.class, customerId);
    }

    private Response deposit(String account,
                             String amount,
                             String key,
                             String token) throws Exception {
        return money(post("/api/v1/accounts/{account}/deposits", account)
                             .header("Idempotency-Key", key)
                             .content("{\"amount\":\"" + amount + "\"}"), token);
    }

    private Response depositWithoutKey(String account,
                                       String amount,
                                       String token) throws Exception {
        return money(post("/api/v1/accounts/{account}/deposits", account)
                             .content("{\"amount\":\"" + amount + "\"}"), token);
    }

    private Response withdraw(String account,
                              String amount,
                              String key,
                              String token) throws Exception {
        return money(post("/api/v1/accounts/{account}/withdrawals", account)
                             .header("Idempotency-Key", key)
                             .content("{\"amount\":\"" + amount + "\"}"), token);
    }

    private Response transfer(String source,
                              String destination,
                              String amount,
                              String key,
                              String token) throws Exception {
        return money(post("/api/v1/accounts/{account}/transfers", source)
                             .header("Idempotency-Key", key)
                             .content("{\"destinationAccountNumber\":\"" + destination
                                              + "\",\"amount\":\"" + amount + "\"}"), token);
    }

    private Response money(MockHttpServletRequestBuilder request,
                           String token) throws Exception {
        var result = mvc.perform(request
                                         .header("Authorization", "Bearer " + token)
                                         .contentType(MediaType.APPLICATION_JSON))
                        .andReturn();
        var response = result.getResponse();
        if (response.getStatus() == 500
                && result.getResolvedException() != null
                && !(result.getResolvedException() instanceof SimulatedFailureException)) {
            throw new AssertionError("Unexpected money-operation failure.", result.getResolvedException());
        }
        return new Response(response.getStatus(), response.getContentAsString());
    }

    private Long createCustomer(String username,
                                String document,
                                String manager) throws Exception {
        String response = mvc.perform(post("/api/v1/customers")
                                              .header("Authorization", "Bearer " + manager)
                                              .header("Idempotency-Key", UUID.randomUUID()
                                                                             .toString())
                                              .contentType(MediaType.APPLICATION_JSON)
                                              .content(customer(username, document)))
                             .andReturn()
                             .getResponse()
                             .getContentAsString();
        return Long.valueOf(response.replaceFirst(".*\\\"customerId\\\":(\\d+).*", "$1"));
    }

    private String createAccount(Long customerId,
                                 String currency,
                                 String manager) throws Exception {
        String body = mvc.perform(post("/api/v1/customers/{id}/accounts", customerId)
                                          .header("Authorization", "Bearer " + manager)
                                          .header("Idempotency-Key", UUID.randomUUID()
                                                                         .toString())
                                          .contentType(MediaType.APPLICATION_JSON)
                                          .content("{\"currency\":\"" + currency + "\"}"))
                         .andReturn()
                         .getResponse()
                         .getContentAsString();
        return extract(body, "accountNumber");
    }

    private void patchAccountStatus(String account,
                                    String status,
                                    String manager) throws Exception {
        mvc.perform(patch("/api/v1/accounts/{account}/status", account)
                            .header("Authorization", "Bearer " + manager)
                            .header("If-Match", "account-v0")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"" + status + "\"}"))
           .andReturn();
    }

    private String login(String username,
                         String password) throws Exception {
        String response = mvc.perform(post("/api/v1/auth/login")
                                              .contentType(MediaType.APPLICATION_JSON)
                                              .content("{\"username\":\"" + username
                                                               + "\",\"password\":\"" + password + "\"}"))
                             .andReturn()
                             .getResponse()
                             .getContentAsString();
        return extract(response, "accessToken");
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
        var matcher = Pattern.compile("\\\"" + Pattern.quote(field)
                                              + "\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
                             .matcher(body);
        if (!matcher.find()) {
            throw new AssertionError("Missing field " + field + " in " + body);
        }
        return matcher.group(1);
    }

    private static final class TestRollbackException extends RuntimeException {
    }

    private record Response(int status, String body) {
    }

    private record Entry(String delta, String balanceAfter) {
    }
}
