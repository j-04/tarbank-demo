package com.tarbank.account.api;

import com.tarbank.account.domain.AccountStatus;
import com.tarbank.account.domain.Currency;
import com.tarbank.money.domain.TransactionStatus;
import com.tarbank.money.domain.TransactionType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class AccountContracts {
    private AccountContracts() {
    }

    public record CreateAccountRequest(@NotNull Currency currency) {
    }

    public record AccountCreatedResponse(String accountNumber, Long customerId, Currency currency,
                                         String availableBalance, AccountStatus status) {
    }

    public record AccountSummary(String accountNumber, Currency currency, String availableBalance,
                                 AccountStatus status) {
    }

    public record AccountPage(List<AccountSummary> items, String nextCursor) {
    }

    public record LimitDetails(String amount, Instant expiresAt) {
    }

    public record DailyLimits(LimitDetails withdrawal, LimitDetails transfer) {
    }

    public record AccountDetails(String accountNumber, Currency currency, String availableBalance,
                                 AccountStatus status, DailyLimits dailyLimits) {
    }

    public record ChangeAccountStatusRequest(@NotNull AccountStatus status) {
    }

    public record AccountStatusResponse(String accountNumber, AccountStatus status) {
    }

    public record DailyLimitUpdateRequest(
            @Schema(minimum = "1000.0000", maximum = "3000.0000", multipleOf = 0.0001)
            BigDecimal withdrawalLimit,
            @Schema(minimum = "1000.0000", maximum = "3000.0000", multipleOf = 0.0001)
            BigDecimal transferLimit) {
    }

    public record DailyLimitUpdateResponse(String accountNumber, String withdrawalLimit,
                                           String transferLimit, Instant expiresAt) {
    }

    public record TransactionHistoryItem(UUID transactionId, TransactionType type, TransactionStatus status,
                                         String amountDelta, String balanceAfter, Currency currency,
                                         Instant createdAt) {
    }

    public record TransactionHistoryPage(List<TransactionHistoryItem> items, String nextCursor) {
    }
}
