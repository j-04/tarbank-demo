package com.tarbank.money.application;

import com.tarbank.common.http.CorrelationIdContext;
import com.tarbank.common.observability.OperationalMetrics;
import com.tarbank.common.resilience.FailurePoint;
import com.tarbank.common.resilience.FailureSimulator;
import com.tarbank.common.resilience.FailureSimulator.Execution;
import com.tarbank.common.resilience.RateLimitService;
import com.tarbank.money.api.MoneyOperationController.AccountOperationResponse;
import com.tarbank.money.api.MoneyOperationController.TransferResponse;
import com.tarbank.money.domain.TransactionType;
import com.tarbank.security.application.TarbankPrincipal;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.apache.logging.log4j.CloseableThreadContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

@Service
public class MoneyOperationOrchestrator {
    private static final Logger LOGGER = LogManager.getLogger(MoneyOperationOrchestrator.class);

    private final MoneyOperationService operations;
    private final FailureSimulator failures;
    private final RateLimitService rateLimits;
    private final OperationalMetrics metrics;
    private final ObservationRegistry observations;

    public MoneyOperationOrchestrator(MoneyOperationService operations,
                                      FailureSimulator failures,
                                      RateLimitService rateLimits,
                                      OperationalMetrics metrics,
                                      ObservationRegistry observations) {
        this.operations = operations;
        this.failures = failures;
        this.rateLimits = rateLimits;
        this.metrics = metrics;
        this.observations = observations;
    }

    public MoneyOperationService.Result<AccountOperationResponse> deposit(
            String accountNumber,
            TarbankPrincipal principal,
            UUID key,
            BigDecimal amount) {
        checkSourceAccount(accountNumber, principal);
        Execution failure = failures.select(TransactionType.DEPOSIT);
        return execute(TransactionType.DEPOSIT, failure,
                       () -> operations.deposit(accountNumber, principal, key, amount, failure));
    }

    public MoneyOperationService.Result<AccountOperationResponse> withdraw(
            String accountNumber,
            TarbankPrincipal principal,
            UUID key,
            BigDecimal amount) {
        checkSourceAccount(accountNumber, principal);
        Execution failure = failures.select(TransactionType.WITHDRAWAL);
        return execute(TransactionType.WITHDRAWAL, failure,
                       () -> operations.withdraw(accountNumber, principal, key, amount, failure));
    }

    public MoneyOperationService.Result<TransferResponse> transfer(
            String sourceAccountNumber,
            TarbankPrincipal principal,
            UUID key,
            String destinationAccountNumber,
            BigDecimal amount) {
        checkSourceAccount(sourceAccountNumber, principal);
        Execution failure = failures.select(TransactionType.TRANSFER);
        return execute(TransactionType.TRANSFER, failure,
                       () -> operations.transfer(sourceAccountNumber, principal, key,
                                                 destinationAccountNumber, amount, failure));
    }

    private void checkSourceAccount(String accountNumber,
                                    TarbankPrincipal principal) {
        String canonicalAccountNumber = operations.authorizeSourceAccount(accountNumber, principal)
                                                  .accountNumber();
        rateLimits.checkMoneyAccount(canonicalAccountNumber);
    }

    private <T> MoneyOperationService.Result<T> execute(
            TransactionType type,
            Execution failure,
            Supplier<MoneyOperationService.Result<T>> operation) {
        Observation observation = Observation.createNotStarted("tarbank.money.operation", observations)
                                             .lowCardinalityKeyValue("operation.type", type.name());
        if (CorrelationIdContext.current() != null) {
            observation.highCardinalityKeyValue(
                    "correlation.id", CorrelationIdContext.current().toString());
        }
        observation.start();
        try (Observation.Scope ignored = observation.openScope()) {
            failure.inject(FailurePoint.BEFORE_TRANSACTION);
            MoneyOperationService.Result<T> result = operation.get();
            if (!result.replayed()) {
                failure.inject(FailurePoint.AFTER_COMMIT_BEFORE_RESPONSE);
            }
            String outcome = result.failure() == null ? "completed" : "business_failure";
            metrics.moneyOutcome(type, outcome);
            logOutcome(type, outcome, result.replayed(), null);
            return result;
        } catch (RuntimeException exception) {
            observation.error(exception);
            metrics.moneyOutcome(type, "technical_failure");
            logOutcome(type, "technical_failure", false, exception);
            throw exception;
        } finally {
            observation.stop();
        }
    }

    private void logOutcome(TransactionType type,
                            String outcome,
                            boolean replayed,
                            RuntimeException exception) {
        try (CloseableThreadContext.Instance ignored = CloseableThreadContext.putAll(Map.of(
                "operationType", type.name(),
                "outcome", outcome))) {
            if (exception == null) {
                LOGGER.info("Money operation finished replayed={}", replayed);
            } else {
                LOGGER.warn("Money operation failed exceptionType={}", exception.getClass().getName());
            }
        }
    }
}
