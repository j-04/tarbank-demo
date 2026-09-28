package com.tarbank.money.application;

import com.tarbank.account.domain.AccountEntity;
import com.tarbank.account.domain.AccountStatus;
import com.tarbank.common.config.IdempotencyProperties;
import com.tarbank.common.domain.AuditEventEntity;
import com.tarbank.common.http.ApiException;
import com.tarbank.common.http.CorrelationIdContext;
import com.tarbank.common.observability.OperationalMetrics;
import com.tarbank.common.persistence.AuditEventRepository;
import com.tarbank.common.resilience.FailurePoint;
import com.tarbank.common.resilience.FailureSimulator.Execution;
import com.tarbank.money.api.MoneyOperationController.AccountOperationResponse;
import com.tarbank.money.api.MoneyOperationController.TransferResponse;
import com.tarbank.money.domain.DailyLimitUsageEntity;
import com.tarbank.money.domain.LimitOperationType;
import com.tarbank.money.domain.MoneyIdempotencyStatus;
import com.tarbank.money.domain.MoneyOperationIdempotencyEntity;
import com.tarbank.money.domain.MoneyTransactionEntity;
import com.tarbank.money.domain.TransactionEntryEntity;
import com.tarbank.money.domain.TransactionStatus;
import com.tarbank.money.domain.TransactionType;
import com.tarbank.money.persistence.DailyLimitUsageRepository;
import com.tarbank.money.persistence.MoneyOperationIdempotencyRepository;
import com.tarbank.money.persistence.MoneyQueryRepository;
import com.tarbank.money.persistence.MoneyQueryRepository.AccountScope;
import com.tarbank.money.persistence.MoneyTransactionRepository;
import com.tarbank.money.persistence.TransactionEntryRepository;
import com.tarbank.security.application.TarbankPrincipal;
import com.tarbank.security.domain.Role;
import org.apache.logging.log4j.CloseableThreadContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

@Service
public class MoneyOperationService {
    private static final Logger LOGGER = LogManager.getLogger(MoneyOperationService.class);

    private static final BigDecimal MINIMUM_WITHDRAWAL = new BigDecimal("5.0000");

    private static final BigDecimal MAXIMUM_BALANCE = new BigDecimal("999999999999999.9999");

    private final MoneyQueryRepository queries;

    private final MoneyTransactionRepository transactions;

    private final TransactionEntryRepository entries;

    private final MoneyOperationIdempotencyRepository idempotency;

    private final DailyLimitUsageRepository dailyUsage;

    private final AuditEventRepository audits;

    private final JsonMapper json;

    private final IdempotencyProperties idempotencyProperties;

    private final EffectiveLimitService limits;

    private final NotificationService notifications;

    private final OperationalMetrics metrics;

    public MoneyOperationService(MoneyQueryRepository queries,
                                 MoneyTransactionRepository transactions,
                                 TransactionEntryRepository entries,
                                 MoneyOperationIdempotencyRepository idempotency,
                                 DailyLimitUsageRepository dailyUsage,
                                 AuditEventRepository audits,
                                 JsonMapper json,
                                 IdempotencyProperties idempotencyProperties,
                                 EffectiveLimitService limits,
                                 NotificationService notifications,
                                 OperationalMetrics metrics) {
        this.queries = queries;
        this.transactions = transactions;
        this.entries = entries;
        this.idempotency = idempotency;
        this.dailyUsage = dailyUsage;
        this.audits = audits;
        this.json = json;
        this.idempotencyProperties = idempotencyProperties;
        this.limits = limits;
        this.notifications = notifications;
        this.metrics = metrics;
    }

    public static ApiException validation() {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                                "The request is invalid.", "TAR-API-001");
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Result<AccountOperationResponse> deposit(String accountNumber,
                                                    TarbankPrincipal principal,
                                                    UUID key,
                                                    BigDecimal requestedAmount) {
        return deposit(accountNumber, principal, key, requestedAmount, Execution.none());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Result<AccountOperationResponse> deposit(String accountNumber,
                                                    TarbankPrincipal principal,
                                                    UUID key,
                                                    BigDecimal requestedAmount,
                                                    Execution failure) {
        try {
            AccountScope owner = authorizeSourceAccount(accountNumber, principal);
            BigDecimal amount = normalize(requestedAmount);
            return execute(owner, TransactionType.DEPOSIT, key,
                           hash(TransactionType.DEPOSIT, accountNumber, null, amount),
                           AccountOperationResponse.class, failure,
                           () -> depositFirst(owner, amount));
        } catch (PessimisticLockingFailureException exception) {
            throw requestInProgress();
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Result<AccountOperationResponse> withdraw(String accountNumber,
                                                     TarbankPrincipal principal,
                                                     UUID key,
                                                     BigDecimal requestedAmount) {
        return withdraw(accountNumber, principal, key, requestedAmount, Execution.none());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Result<AccountOperationResponse> withdraw(String accountNumber,
                                                     TarbankPrincipal principal,
                                                     UUID key,
                                                     BigDecimal requestedAmount,
                                                     Execution failure) {
        try {
            AccountScope owner = authorizeSourceAccount(accountNumber, principal);
            BigDecimal amount = normalize(requestedAmount);
            return execute(owner, TransactionType.WITHDRAWAL, key,
                           hash(TransactionType.WITHDRAWAL, accountNumber, null, amount),
                           AccountOperationResponse.class, failure,
                           () -> withdrawFirst(owner, amount));
        } catch (PessimisticLockingFailureException exception) {
            throw requestInProgress();
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Result<TransferResponse> transfer(String sourceAccountNumber,
                                             TarbankPrincipal principal,
                                             UUID key,
                                             String destinationAccountNumber,
                                             BigDecimal requestedAmount) {
        return transfer(sourceAccountNumber, principal, key, destinationAccountNumber,
                        requestedAmount, Execution.none());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Result<TransferResponse> transfer(String sourceAccountNumber,
                                             TarbankPrincipal principal,
                                             UUID key,
                                             String destinationAccountNumber,
                                             BigDecimal requestedAmount,
                                             Execution failure) {
        if (sourceAccountNumber.equals(destinationAccountNumber)) {
            throw validation();
        }
        try {
            AccountScope owner = authorizeSourceAccount(sourceAccountNumber, principal);
            BigDecimal amount = normalize(requestedAmount);
            return execute(owner, TransactionType.TRANSFER, key,
                           hash(TransactionType.TRANSFER, sourceAccountNumber,
                                destinationAccountNumber, amount),
                           TransferResponse.class, failure,
                           () -> transferFirst(owner, destinationAccountNumber, amount));
        } catch (PessimisticLockingFailureException exception) {
            throw requestInProgress();
        }
    }

    private <T> Result<T> execute(AccountScope scope,
                                  TransactionType type,
                                  UUID key,
                                  String requestHash,
                                  Class<T> responseType,
                                  Execution failure,
                                  Supplier<Attempt<T>> operation) {
        setLockTimeout();
        Instant now = limits.now();
        Long customerId = scope.customerId();
        int inserted = idempotency.insertInProgress(
                customerId, scope.id(), type.name(), key, requestHash,
                now.plus(idempotencyProperties.retention()), now);
        MoneyOperationIdempotencyEntity record = queries.lockIdempotency(
                                                                customerId, scope.id(), type, key)
                                                        .orElseThrow(this::requestInProgress);
        if (!constantTimeEquals(record.getRequestHash(), requestHash)) {
            throw idempotencyConflict();
        }
        if (inserted == 0) {
            return replay(record, responseType);
        }
        if (record.getStatus() != MoneyIdempotencyStatus.IN_PROGRESS) {
            throw new IllegalStateException("New idempotency record is final.");
        }

        Attempt<T> attempt = operation.get();
        MoneyIdempotencyStatus status = attempt.failure() == null
                ? MoneyIdempotencyStatus.COMPLETED
                : MoneyIdempotencyStatus.FAILED;
        int httpStatus = attempt.failure() == null
                ? HttpStatus.CREATED.value()
                : attempt.failure()
                         .httpStatus();
        Object response = attempt.failure() == null ? attempt.body() : attempt.failure();
        record.finalizeWith(attempt.transaction(), status, httpStatus, writeJson(response), limits.now());
        notifyAfterCommit(attempt.transaction()
                                 .getId(), type,
                          attempt.failure() == null ? TransactionStatus.COMPLETED : TransactionStatus.FAILED,
                          attempt.failure() == null ? null : attempt.failure()
                                                                    .code());
        failure.inject(FailurePoint.DURING_TRANSACTION_BEFORE_COMMIT);
        return new Result<>(httpStatus, attempt.body(), attempt.failure(), false);
    }

    private <T> Result<T> replay(MoneyOperationIdempotencyEntity record,
                                 Class<T> responseType) {
        if (record.getStatus() == MoneyIdempotencyStatus.IN_PROGRESS) {
            throw requestInProgress();
        }
        try {
            if (record.getStatus() == MoneyIdempotencyStatus.FAILED) {
                return new Result<>(record.getResponseStatus(), null,
                                    json.readValue(record.getResponseBody(), Failure.class), true);
            }
            return new Result<>(record.getResponseStatus(),
                                json.readValue(record.getResponseBody(), responseType), null, true);
        } catch (Exception exception) {
            throw new IllegalStateException("Stored money response is unreadable.", exception);
        }
    }

    private Attempt<AccountOperationResponse> depositFirst(AccountScope expected,
                                                           BigDecimal amount) {
        AccountEntity account = lockAccount(expected.id());
        UUID transactionId = UUID.randomUUID();
        Instant now = limits.now();
        if (account.getStatus() != AccountStatus.ACTIVE) {
            return failed(transactionId, TransactionType.DEPOSIT, account, null, amount,
                          failure("ACCOUNT_NOT_ACTIVE", "The account is not active.", "TAR-MONEY-001"));
        }

        BigDecimal balanceAfter = account.getBalance()
                                         .add(amount);
        if (balanceAfter.compareTo(MAXIMUM_BALANCE) > 0) {
            return failed(transactionId, TransactionType.DEPOSIT, account, null, amount,
                          balanceLimitExceeded());
        }
        MoneyTransactionEntity transaction = insertTransaction(
                transactionId, TransactionType.DEPOSIT, TransactionStatus.COMPLETED,
                amount, account, null, account, null, now);
        queries.updateBalance(account.getId(), balanceAfter, now);
        insertEntry(transaction, account, amount, balanceAfter, now);
        insertAudit(account, transaction, TransactionType.DEPOSIT,
                    TransactionStatus.COMPLETED, null, now);
        return success(transaction, new AccountOperationResponse(
                transactionId, TransactionStatus.COMPLETED.name(), account.getAccountNumber(),
                money(amount), account.getCurrency(), money(balanceAfter), now));
    }

    private Attempt<AccountOperationResponse> withdrawFirst(AccountScope expected,
                                                            BigDecimal amount) {
        AccountEntity account = lockAccount(expected.id());
        UUID transactionId = UUID.randomUUID();
        Instant now = limits.now();
        if (account.getStatus() != AccountStatus.ACTIVE) {
            return failed(transactionId, TransactionType.WITHDRAWAL, account, null, amount,
                          failure("ACCOUNT_NOT_ACTIVE", "The account is not active.", "TAR-MONEY-001"));
        }
        if (amount.compareTo(MINIMUM_WITHDRAWAL) < 0) {
            return failed(transactionId, TransactionType.WITHDRAWAL, account, null, amount,
                          failure("MINIMUM_WITHDRAWAL_AMOUNT",
                                  "The withdrawal amount is below the permitted minimum.",
                                  "TAR-MONEY-002"));
        }
        if (account.getBalance()
                   .compareTo(amount) < 0) {
            return failed(transactionId, TransactionType.WITHDRAWAL, account, null, amount,
                          failure("INSUFFICIENT_FUNDS", "The account has insufficient funds.",
                                  "TAR-MONEY-003"));
        }

        var limitWindow = limits.window(account.getCustomer()
                                               .getTimezone(), now);
        Usage usage = lockUsage(account, LimitOperationType.WITHDRAWAL,
                                limitWindow.effectiveDate(), now);
        BigDecimal limit = limits.effectiveLimit(
                                         account, LimitOperationType.WITHDRAWAL, limitWindow)
                                 .amount();
        if (usage.entity()
                 .getUsedAmount()
                 .add(amount)
                 .compareTo(limit) > 0) {
            removeUnusedRow(usage);
            return failed(transactionId, TransactionType.WITHDRAWAL, account, null, amount,
                          failure("DAILY_LIMIT_EXCEEDED",
                                  "The operation exceeds the remaining daily limit.",
                                  "TAR-MONEY-004"));
        }

        BigDecimal balanceAfter = account.getBalance()
                                         .subtract(amount);
        MoneyTransactionEntity transaction = insertTransaction(
                transactionId, TransactionType.WITHDRAWAL, TransactionStatus.COMPLETED,
                amount, account, account, null, null, now);
        queries.updateBalance(account.getId(), balanceAfter, now);
        insertEntry(transaction, account, amount.negate(), balanceAfter, now);
        usage.entity()
             .consume(amount, now);
        insertAudit(account, transaction, TransactionType.WITHDRAWAL,
                    TransactionStatus.COMPLETED, null, now);
        return success(transaction, new AccountOperationResponse(
                transactionId, TransactionStatus.COMPLETED.name(), account.getAccountNumber(),
                money(amount), account.getCurrency(), money(balanceAfter), now));
    }

    private Attempt<TransferResponse> transferFirst(AccountScope expectedSource,
                                                    String destinationAccountNumber,
                                                    BigDecimal amount) {
        Long destinationId = queries.findAccountId(destinationAccountNumber)
                                    .orElseThrow(this::notFound);
        List<AccountEntity> locked = queries.lockAccounts(expectedSource.id(), destinationId);
        AccountEntity source = accountById(locked, expectedSource.id());
        AccountEntity destination = accountById(locked, destinationId);
        UUID transactionId = UUID.randomUUID();
        Instant now = limits.now();

        if (source.getStatus() != AccountStatus.ACTIVE
                || destination.getStatus() != AccountStatus.ACTIVE) {
            return failed(transactionId, TransactionType.TRANSFER, source, destination, amount,
                          failure("ACCOUNT_NOT_ACTIVE", "An account is not active.", "TAR-MONEY-001"));
        }
        if (source.getCurrency() != destination.getCurrency()) {
            return failed(transactionId, TransactionType.TRANSFER, source, destination, amount,
                          failure("CURRENCY_MISMATCH",
                                  "Source and destination accounts must use the same currency.",
                                  "TAR-MONEY-005"));
        }
        if (source.getBalance()
                  .compareTo(amount) < 0) {
            return failed(transactionId, TransactionType.TRANSFER, source, destination, amount,
                          failure("INSUFFICIENT_FUNDS", "The account has insufficient funds.",
                                  "TAR-TRANSFER-001"));
        }
        if (destination.getBalance()
                       .add(amount)
                       .compareTo(MAXIMUM_BALANCE) > 0) {
            return failed(transactionId, TransactionType.TRANSFER, source, destination, amount,
                          balanceLimitExceeded());
        }

        var limitWindow = limits.window(source.getCustomer()
                                              .getTimezone(), now);
        Usage usage = lockUsage(source, LimitOperationType.TRANSFER,
                                limitWindow.effectiveDate(), now);
        BigDecimal limit = limits.effectiveLimit(
                                         source, LimitOperationType.TRANSFER, limitWindow)
                                 .amount();
        if (usage.entity()
                 .getUsedAmount()
                 .add(amount)
                 .compareTo(limit) > 0) {
            removeUnusedRow(usage);
            return failed(transactionId, TransactionType.TRANSFER, source, destination, amount,
                          failure("DAILY_LIMIT_EXCEEDED",
                                  "The operation exceeds the remaining daily limit.",
                                  "TAR-MONEY-004"));
        }

        BigDecimal sourceBalanceAfter = source.getBalance()
                                              .subtract(amount);
        BigDecimal destinationBalanceAfter = destination.getBalance()
                                                        .add(amount);
        MoneyTransactionEntity transaction = insertTransaction(
                transactionId, TransactionType.TRANSFER, TransactionStatus.COMPLETED,
                amount, source, source, destination, null, now);
        queries.updateBalance(source.getId(), sourceBalanceAfter, now);
        queries.updateBalance(destination.getId(), destinationBalanceAfter, now);
        insertEntry(transaction, source, amount.negate(), sourceBalanceAfter, now);
        insertEntry(transaction, destination, amount, destinationBalanceAfter, now);
        usage.entity()
             .consume(amount, now);
        insertAudit(source, transaction, TransactionType.TRANSFER,
                    TransactionStatus.COMPLETED, null, now);
        return success(transaction, new TransferResponse(
                transactionId, TransactionStatus.COMPLETED.name(), source.getAccountNumber(),
                destination.getAccountNumber(), money(amount), source.getCurrency(), now));
    }

    private <T> Attempt<T> failed(UUID transactionId,
                                  TransactionType type,
                                  AccountEntity sourceOrDepositTarget,
                                  AccountEntity transferDestination,
                                  BigDecimal amount,
                                  Failure failure) {
        AccountEntity source = type == TransactionType.DEPOSIT ? null : sourceOrDepositTarget;
        AccountEntity destination = type == TransactionType.DEPOSIT
                ? sourceOrDepositTarget
                : transferDestination;
        Instant now = limits.now();
        MoneyTransactionEntity transaction = insertTransaction(
                transactionId, type, TransactionStatus.FAILED, amount,
                sourceOrDepositTarget, source, destination, failure.code(), now);
        insertAudit(sourceOrDepositTarget, transaction, type,
                    TransactionStatus.FAILED, failure.code(), now);
        return new Attempt<>(transaction, null, failure);
    }

    private <T> Attempt<T> success(MoneyTransactionEntity transaction,
                                   T body) {
        return new Attempt<>(transaction, body, null);
    }

    @Transactional(readOnly = true)
    public AccountScope authorizeSourceAccount(String accountNumber,
                                               TarbankPrincipal principal) {
        if (principal == null || principal.role() != Role.CUSTOMER) {
            throw accessDenied();
        }
        AccountScope account = queries.findAccountScope(accountNumber)
                                      .orElseThrow(this::notFound);
        if (!account.customerId()
                    .equals(principal.userId())) {
            throw accessDenied();
        }
        return account;
    }

    private AccountEntity lockAccount(Long id) {
        return queries.lockAccount(id)
                      .orElseThrow(this::notFound);
    }

    private AccountEntity accountById(List<AccountEntity> accounts,
                                      Long id) {
        return accounts.stream()
                       .filter(account -> account.getId()
                                                 .equals(id))
                       .findFirst()
                       .orElseThrow(this::notFound);
    }

    private Usage lockUsage(AccountEntity account,
                            LimitOperationType operation,
                            LocalDate usageDate,
                            Instant now) {
        boolean inserted = dailyUsage.insertIfAbsent(
                account.getId(), operation.name(), usageDate, now) == 1;
        DailyLimitUsageEntity usage = queries.lockUsage(account.getId(), operation, usageDate)
                                             .orElseThrow(() -> new IllegalStateException(
                                                     "Daily usage row is unavailable."));
        return new Usage(usage, inserted);
    }

    private void removeUnusedRow(Usage usage) {
        if (usage.inserted()) {
            queries.detach(usage.entity());
            dailyUsage.deleteUnused(usage.entity()
                                         .getId());
        }
    }

    private MoneyTransactionEntity insertTransaction(UUID id,
                                                     TransactionType type,
                                                     TransactionStatus status,
                                                     BigDecimal amount,
                                                     AccountEntity initiatedFrom,
                                                     AccountEntity source,
                                                     AccountEntity destination,
                                                     String failureCode,
                                                     Instant now) {
        return transactions.save(new MoneyTransactionEntity(
                id, type, status, amount, initiatedFrom.getCurrency(),
                initiatedFrom.getCustomer(), source, destination, failureCode,
                CorrelationIdContext.current(), now));
    }

    private void insertEntry(MoneyTransactionEntity transaction,
                             AccountEntity account,
                             BigDecimal delta,
                             BigDecimal balanceAfter,
                             Instant now) {
        entries.save(new TransactionEntryEntity(transaction, account, delta, balanceAfter, now));
    }

    private void insertAudit(AccountEntity actorAccount,
                             MoneyTransactionEntity transaction,
                             TransactionType type,
                             TransactionStatus status,
                             String failureCode,
                             Instant now) {
        Map<String, Object> metadata = failureCode == null
                ? Map.of("type", type.name(), "status", status.name())
                : Map.of("type", type.name(), "status", status.name(), "failureCode", failureCode);
        audits.save(new AuditEventEntity(
                actorAccount.getCustomer()
                            .getUser(), "MONEY_OPERATION_" + status.name(),
                "TRANSACTION", transaction.getId()
                                          .toString(), CorrelationIdContext.current(),
                writeJson(metadata), now));
    }

    private void setLockTimeout() {
        long millis = Math.max(1, idempotencyProperties.lockTimeout()
                                                       .toMillis());
        idempotency.setLocalLockTimeout(millis + "ms");
    }

    private void notifyAfterCommit(UUID transactionId,
                                   TransactionType type,
                                   TransactionStatus status,
                                   String failureCode) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    notifications.send(new NotificationService.Outcome(
                            transactionId, type, status, failureCode));
                    metrics.notification("success");
                } catch (Exception exception) {
                    metrics.notification("failure");
                    try (CloseableThreadContext.Instance ignored = CloseableThreadContext.putAll(Map.of(
                            "operationType", type.name(),
                            "outcome", "notification_failure"))) {
                        LOGGER.warn(
                                "Notification adapter failed after commit status={} exceptionType={}",
                                status, exception.getClass().getName());
                    }
                }
            }
        });
    }

    private BigDecimal normalize(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0
                || amount.scale() > 4 || amount.precision() - amount.scale() > 15) {
            throw validation();
        }
        return amount.setScale(4);
    }

    private String hash(TransactionType type,
                        String sourceOrTarget,
                        String destination,
                        BigDecimal amount) {
        String canonical = type.name() + "|" + sourceOrTarget + "|"
                + (destination == null ? "-" : destination) + "|" + money(amount);
        try {
            return HexFormat.of()
                            .formatHex(
                                    MessageDigest.getInstance("SHA-256")
                                                 .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Money request fingerprinting is unavailable.", exception);
        }
    }

    private boolean constantTimeEquals(String left,
                                       String right) {
        return MessageDigest.isEqual(
                left.getBytes(StandardCharsets.US_ASCII), right.getBytes(StandardCharsets.US_ASCII));
    }

    private String writeJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Money response serialization is unavailable.", exception);
        }
    }

    private String money(BigDecimal value) {
        return value.setScale(4)
                    .toPlainString();
    }

    private Failure balanceLimitExceeded() {
        return failure("BALANCE_LIMIT_EXCEEDED",
                       "The operation would exceed the maximum account balance.",
                       "TAR-MONEY-006");
    }

    private Failure failure(String code,
                            String message,
                            String internalCode) {
        return new Failure(HttpStatus.UNPROCESSABLE_ENTITY.value(), code, message, internalCode);
    }

    private ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND",
                                "Requested resource was not found.", "TAR-ACCOUNT-001");
    }

    private ApiException accessDenied() {
        return new ApiException(HttpStatus.FORBIDDEN, "ACCESS_DENIED",
                                "Access is denied.", "TAR-AUTH-002");
    }

    private ApiException idempotencyConflict() {
        return new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT",
                                "The idempotency key was reused with different input.",
                                "TAR-IDEMPOTENCY-001");
    }

    private ApiException requestInProgress() {
        return new ApiException(HttpStatus.CONFLICT, "REQUEST_IN_PROGRESS",
                                "A matching request is still in progress.",
                                "TAR-IDEMPOTENCY-002");
    }

    public record Result<T>(int httpStatus, T body, Failure failure, boolean replayed) {
    }

    public record Failure(int httpStatus, String code, String message, String internalCode) {
        public ApiException toException() {
            return new ApiException(HttpStatus.valueOf(httpStatus), code, message, internalCode);
        }
    }

    private record Attempt<T>(MoneyTransactionEntity transaction, T body, Failure failure) {
    }

    private record Usage(DailyLimitUsageEntity entity, boolean inserted) {
    }
}
