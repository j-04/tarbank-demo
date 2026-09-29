package com.tarbank.money.api;

import com.tarbank.money.api.MoneyOperationContracts.*;

import com.tarbank.common.api.ApiSuccessResponse;
import com.tarbank.common.http.ApiRequestHeaders;
import com.tarbank.common.http.CorrelationIdContext;
import com.tarbank.money.application.MoneyOperationOrchestrator;
import com.tarbank.money.application.MoneyOperationService;
import com.tarbank.security.application.TarbankPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/api/v1/accounts/{accountNumber}",
        produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Money operations", description = "Idempotent customer-owned deposits, withdrawals, and transfers.")
public class MoneyOperationController {
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
        return response(operations.deposit(accountNumber, principal, ApiRequestHeaders.parseUuidV4(key),
                                           request.amount()));
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
        return response(operations.withdraw(accountNumber, principal, ApiRequestHeaders.parseUuidV4(key),
                                            request.amount()));
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
        return response(operations.transfer(accountNumber, principal, ApiRequestHeaders.parseUuidV4(key),
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

}
