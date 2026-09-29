package com.tarbank.money.api;

import com.tarbank.account.domain.Currency;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public final class MoneyOperationContracts {
    private MoneyOperationContracts() {
    }

    public record AmountRequest(
            @Schema(minimum = "0.0001", maximum = "999999999999999.9999", multipleOf = 0.0001)
            @NotNull @DecimalMin(value = "0.0001") @Digits(integer = 15, fraction = 4) BigDecimal amount) {
    }

    public record WithdrawalRequest(
            @Schema(minimum = "5.0000", maximum = "999999999999999.9999", multipleOf = 0.0001)
            @NotNull @Digits(integer = 15, fraction = 4) BigDecimal amount) {
    }

    public record TransferRequest(
            @NotBlank @Pattern(regexp = "^TB[0-9]{14}$") String destinationAccountNumber,
            @Schema(minimum = "0.0001", maximum = "999999999999999.9999", multipleOf = 0.0001)
            @NotNull @DecimalMin(value = "0.0001") @Digits(integer = 15, fraction = 4) BigDecimal amount) {
    }

    public record AccountOperationResponse(UUID transactionId, String status, String accountNumber,
                                           String amount, Currency currency, String balanceAfter,
                                           Instant completedAt) {
    }

    public record TransferResponse(UUID transactionId, String status, String sourceAccountNumber,
                                   String destinationAccountNumber, String amount, Currency currency,
                                   Instant completedAt) {
    }
}
