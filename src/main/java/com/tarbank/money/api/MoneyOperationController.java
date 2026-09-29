package com.tarbank.money.api;

import com.tarbank.account.domain.Currency;
import com.tarbank.common.api.ApiSuccessResponse;
import com.tarbank.common.http.CorrelationIdContext;
import com.tarbank.money.application.MoneyOperationOrchestrator;
import com.tarbank.money.application.MoneyOperationService;
import com.tarbank.security.application.TarbankPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping(value = "/api/v1/accounts/{accountNumber}",
        produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Money operations", description = "Idempotent customer-owned deposits, withdrawals, and transfers.")
public class MoneyOperationController {
    private static final java.util.regex.Pattern UUID_V4 = java.util.regex.Pattern.compile(
            "(?i)^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");

    private final MoneyOperationOrchestrator operations;

    public MoneyOperationController(MoneyOperationOrchestrator operations) {
        this.operations = operations;
    }

    @PostMapping("/deposits")
    @Operation(summary = "Deposit into an owned account")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Deposit completed or idempotently replayed.",
                    useReturnTypeSchema = true),
            @ApiResponse(responseCode = "404", description = "RESOURCE_NOT_FOUND."),
            @ApiResponse(responseCode = "409", description = "IDEMPOTENCY_CONFLICT or REQUEST_IN_PROGRESS."),
            @ApiResponse(responseCode = "422", description = "ACCOUNT_NOT_ACTIVE or BALANCE_LIMIT_EXCEEDED.")
    })
    public ResponseEntity<ApiSuccessResponse<AccountOperationResponse>> deposit(
            @PathVariable String accountNumber,
            @RequestHeader("Idempotency-Key") String key,
            @AuthenticationPrincipal TarbankPrincipal principal,
            @Valid @RequestBody AmountRequest request) {
        return response(operations.deposit(accountNumber, principal, parseKey(key), request.amount()));
    }

    @PostMapping("/withdrawals")
    @Operation(summary = "Withdraw from an owned account")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Withdrawal completed or idempotently replayed.",
                    useReturnTypeSchema = true),
            @ApiResponse(responseCode = "404", description = "RESOURCE_NOT_FOUND."),
            @ApiResponse(responseCode = "409", description = "IDEMPOTENCY_CONFLICT or REQUEST_IN_PROGRESS."),
            @ApiResponse(responseCode = "422", description = "Minimum amount, funds, daily limit, or account status rejection.")
    })
    public ResponseEntity<ApiSuccessResponse<AccountOperationResponse>> withdraw(
            @PathVariable String accountNumber,
            @RequestHeader("Idempotency-Key") String key,
            @AuthenticationPrincipal TarbankPrincipal principal,
            @Valid @RequestBody WithdrawalRequest request) {
        return response(operations.withdraw(accountNumber, principal, parseKey(key), request.amount()));
    }

    @PostMapping("/transfers")
    @Operation(summary = "Transfer from an owned account")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Transfer completed or idempotently replayed.",
                    useReturnTypeSchema = true),
            @ApiResponse(responseCode = "404", description = "RESOURCE_NOT_FOUND."),
            @ApiResponse(responseCode = "409", description = "IDEMPOTENCY_CONFLICT or REQUEST_IN_PROGRESS."),
            @ApiResponse(responseCode = "422", description = "Funds, limits, account status, currency, or balance rejection.")
    })
    public ResponseEntity<ApiSuccessResponse<TransferResponse>> transfer(
            @PathVariable String accountNumber,
            @RequestHeader("Idempotency-Key") String key,
            @AuthenticationPrincipal TarbankPrincipal principal,
            @Valid @RequestBody TransferRequest request) {
        return response(operations.transfer(accountNumber, principal, parseKey(key),
                                            request.destinationAccountNumber(), request.amount()));
    }

    private <T> ResponseEntity<ApiSuccessResponse<T>> response(MoneyOperationService.Result<T> result) {
        if (result.failure() != null) {
            throw result.failure()
                        .toException();
        }
        return ResponseEntity.status(result.httpStatus())
                             .body(new ApiSuccessResponse<>(result.body(), CorrelationIdContext.current()));
    }

    private UUID parseKey(String value) {
        try {
            if (value == null || !UUID_V4.matcher(value)
                                         .matches()) {
                throw new IllegalArgumentException();
            }
            UUID key = UUID.fromString(value);
            if (key.version() != 4 || key.variant() != 2) {
                throw new IllegalArgumentException();
            }
            return key;
        } catch (Exception exception) {
            throw MoneyOperationService.validation();
        }
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
