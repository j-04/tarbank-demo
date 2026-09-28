package com.tarbank.money;

import com.tarbank.money.application.NotificationService;
import com.tarbank.money.persistence.MoneyQueryRepository;
import com.tarbank.support.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class MoneyOperationIntegrationTest extends AbstractIntegrationTest {
    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoSpyBean
    private NotificationService notifications;

    @MockitoSpyBean
    private MoneyQueryRepository queries;

    @AfterEach
    void resetNotificationAdapter() {
        reset(notifications, queries);
    }

    @Test
    void completedOperationsPersistExactAtomicFinancialRecords() throws Exception {
        String manager = login("manager", "TestPass123!");
        Long ownerId = createCustomer("moneyownerone", "M6000001", manager);
        Long recipientId = createCustomer("moneyrecipientone", "M6000002", manager);
        String owner = login("moneyownerone", "StrongPwd123");
        String source = createAccount(ownerId, "EUR", manager);
        String destination = createAccount(recipientId, "EUR", manager);

        Response deposit = deposit(source, "200.0000", UUID.randomUUID().toString(), owner);
        Response withdrawal = withdraw(source, "25.0000", UUID.randomUUID().toString(), owner);
        Response transfer = transfer(source, destination, "50.0000", UUID.randomUUID().toString(), owner);

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

        String depositKey = UUID.randomUUID().toString();
        Response firstDeposit = deposit(account, "40", depositKey, owner);
        Response replayedDeposit = deposit(account, "40.0000", depositKey, owner);
        assertThat(replayedDeposit.status()).isEqualTo(201);
        assertThat(extract(replayedDeposit.body(), "transactionId"))
                .isEqualTo(extract(firstDeposit.body(), "transactionId"));
        assertThat(balance(account)).isEqualByComparingTo("40.0000");
        assertThat(deposit(account, "41.0000", depositKey, owner).status()).isEqualTo(409);

        String withdrawalKey = UUID.randomUUID().toString();
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

        assertThat(deposit(source, "2000.0000", UUID.randomUUID().toString(), owner).status()).isEqualTo(201);
        assertFailure(withdraw(source, "4.9999", UUID.randomUUID().toString(), owner),
                      "MINIMUM_WITHDRAWAL_AMOUNT");
        assertFailure(withdraw(source, "1000.0001", UUID.randomUUID().toString(), owner),
                      "DAILY_LIMIT_EXCEEDED");
        assertFailure(transfer(source, eurDestination, "1000.0001", UUID.randomUUID().toString(), owner),
                      "DAILY_LIMIT_EXCEEDED");
        assertFailure(transfer(source, usdDestination, "10.0000", UUID.randomUUID().toString(), owner),
                      "CURRENCY_MISMATCH");

        String empty = createAccount(ownerId, "EUR", manager);
        assertFailure(withdraw(empty, "5.0000", UUID.randomUUID().toString(), owner),
                      "INSUFFICIENT_FUNDS");

        patchAccountStatus(source, "BLOCKED", manager);
        assertFailure(deposit(source, "10.0000", UUID.randomUUID().toString(), owner),
                      "ACCOUNT_NOT_ACTIVE");
        assertFailure(withdraw(source, "10.0000", UUID.randomUUID().toString(), owner),
                      "ACCOUNT_NOT_ACTIVE");
        assertFailure(transfer(source, eurDestination, "10.0000", UUID.randomUUID().toString(), owner),
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
        assertThat(transfer(source, source, "10.0000", UUID.randomUUID().toString(), owner).status()).isEqualTo(400);
        assertThat(deposit(source, "0", UUID.randomUUID().toString(), owner).status()).isEqualTo(400);
        assertThat(deposit(source, "1.00001", UUID.randomUUID().toString(), owner).status()).isEqualTo(400);
        assertThat(deposit(source, "10.0000", "not-a-uuid", owner).status()).isEqualTo(400);
        assertThat(deposit(source, "10.0000", UUID.randomUUID().toString(), otherOwner).status()).isEqualTo(403);
        assertThat(deposit(source, "10.0000", UUID.randomUUID().toString(), manager).status()).isEqualTo(403);
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
        String delayedKey = UUID.randomUUID().toString();
        CountDownLatch idempotencyLoaded = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        pauseAfterIdempotency(delayedKey, idempotencyLoaded, resume);

        Response delayed;
        Response competing;
        try (var executor = Executors.newFixedThreadPool(2)) {
            var delayedRequest = executor.submit(() -> deposit(account, "200.0000", delayedKey, owner));
            assertThat(idempotencyLoaded.await(10, TimeUnit.SECONDS)).isTrue();
            competing = executor.submit(() -> deposit(
                    account, "100.0000", UUID.randomUUID().toString(), owner)).get(10, TimeUnit.SECONDS);
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
        assertThat(deposit(account, "100.0000", UUID.randomUUID().toString(), owner).status()).isEqualTo(201);
        String delayedKey = UUID.randomUUID().toString();
        CountDownLatch idempotencyLoaded = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        pauseAfterIdempotency(delayedKey, idempotencyLoaded, resume);

        Response delayed;
        Response competing;
        try (var executor = Executors.newFixedThreadPool(2)) {
            var delayedRequest = executor.submit(() -> withdraw(account, "70.0000", delayedKey, owner));
            assertThat(idempotencyLoaded.await(10, TimeUnit.SECONDS)).isTrue();
            competing = executor.submit(() -> withdraw(
                    account, "70.0000", UUID.randomUUID().toString(), owner)).get(10, TimeUnit.SECONDS);
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
        assertThat(deposit(source, "100.0000", UUID.randomUUID().toString(), owner).status()).isEqualTo(201);
        String delayedKey = UUID.randomUUID().toString();
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
                    source, "70.0000", UUID.randomUUID().toString(), owner)).get(10, TimeUnit.SECONDS);
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
        assertThat(deposit(firstSource, "100.0000", UUID.randomUUID().toString(), firstOwner).status())
                .isEqualTo(201);
        assertThat(deposit(secondSource, "100.0000", UUID.randomUUID().toString(), secondOwner).status())
                .isEqualTo(201);
        String delayedKey = UUID.randomUUID().toString();
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
                    secondSource, destination, "40.0000", UUID.randomUUID().toString(), secondOwner))
                    .get(10, TimeUnit.SECONDS);
            resume.countDown();
            delayed = delayedRequest.get(10, TimeUnit.SECONDS);
        } finally {
            resume.countDown();
        }

        assertThat(List.of(delayed.status(), competing.status())).containsOnly(201);
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
        assertThat(deposit(full, "999999999999999.9999", UUID.randomUUID().toString(), owner).status())
                .isEqualTo(201);
        assertThat(deposit(source, "10.0000", UUID.randomUUID().toString(), owner).status()).isEqualTo(201);

        String depositKey = UUID.randomUUID().toString();
        Response depositFailure = deposit(full, "0.0001", depositKey, owner);
        Response depositReplay = deposit(full, "0.0001", depositKey, owner);
        String transferKey = UUID.randomUUID().toString();
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
        doThrow(new IllegalStateException("adapter unavailable")).when(notifications).send(any());

        Response result = deposit(account, "15.0000", UUID.randomUUID().toString(), owner);

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
        assertThat(jdbc.queryForObject(
                "select count(*) from transaction_entries where transaction_id=?",
                Integer.class, transactionId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select count(*) from audit_events where target_id=?",
                Integer.class, transactionId.toString())).isEqualTo(1);
    }

    private void pauseAfterIdempotency(String key,
                                       CountDownLatch entered,
                                       CountDownLatch resume) {
        org.mockito.Mockito.doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            if (UUID.fromString(key).equals(invocation.getArgument(3))) {
                entered.countDown();
                if (!resume.await(15, TimeUnit.SECONDS)) {
                    throw new AssertionError("Timed out waiting to resume the money operation.");
                }
            }
            return result;
        }).when(queries).lockIdempotency(any(), any(), any(), any());
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
                rs.getBigDecimal("amount_delta").toPlainString(),
                rs.getBigDecimal("balance_after").toPlainString()), transactionId);
        assertThat(entries).hasSize(expected.size());
        for (Entry entry : expected) {
            assertThat(entries).anySatisfy(actual -> {
                assertThat(new BigDecimal(actual.delta())).isEqualByComparingTo(entry.delta());
                assertThat(new BigDecimal(actual.balanceAfter())).isEqualByComparingTo(entry.balanceAfter());
            });
        }
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
        if (response.getStatus() == 500 && result.getResolvedException() != null) {
            throw new AssertionError("Unexpected money-operation failure.", result.getResolvedException());
        }
        return new Response(response.getStatus(), response.getContentAsString());
    }

    private Long createCustomer(String username,
                                String document,
                                String manager) throws Exception {
        String response = mvc.perform(post("/api/v1/customers")
                                              .header("Authorization", "Bearer " + manager)
                                              .header("Idempotency-Key", UUID.randomUUID().toString())
                                              .contentType(MediaType.APPLICATION_JSON)
                                              .content(customer(username, document)))
                .andReturn().getResponse().getContentAsString();
        return Long.valueOf(response.replaceFirst(".*\\\"customerId\\\":(\\d+).*", "$1"));
    }

    private String createAccount(Long customerId,
                                 String currency,
                                 String manager) throws Exception {
        String body = mvc.perform(post("/api/v1/customers/{id}/accounts", customerId)
                                          .header("Authorization", "Bearer " + manager)
                                          .header("Idempotency-Key", UUID.randomUUID().toString())
                                          .contentType(MediaType.APPLICATION_JSON)
                                          .content("{\"currency\":\"" + currency + "\"}"))
                .andReturn().getResponse().getContentAsString();
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
                .andReturn().getResponse().getContentAsString();
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
        if (!matcher.find()) throw new AssertionError("Missing field " + field + " in " + body);
        return matcher.group(1);
    }

    private record Response(int status, String body) {
    }

    private record Entry(String delta, String balanceAfter) {
    }
}
