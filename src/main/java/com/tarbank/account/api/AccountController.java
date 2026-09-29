package com.tarbank.account.api;

import com.tarbank.account.api.AccountContracts.*;

import com.tarbank.account.application.AccountFeatureService;
import com.tarbank.account.application.AccountService;
import com.tarbank.common.api.ApiSuccessResponse;
import com.tarbank.common.http.ApiRequestHeaders;
import com.tarbank.common.http.CorrelationIdContext;
import com.tarbank.security.application.TarbankPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping(value = "/api/v1/accounts", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Accounts", description = "Customer-owned account access, lifecycle, limits, and history.")
public class AccountController {
    private final AccountService accounts;

    private final AccountFeatureService features;

    public AccountController(AccountService accounts,
                             AccountFeatureService features) {
        this.accounts = accounts;
        this.features = features;
    }

    @GetMapping
    @Operation(summary = "List the authenticated customer's accounts")
    @ApiResponse(responseCode = "200", description = "Cursor-paginated account page.",
            useReturnTypeSchema = true)
    public ApiSuccessResponse<AccountPage> list(
            @AuthenticationPrincipal TarbankPrincipal principal,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit) {
        return new ApiSuccessResponse<>(accounts.listForOwner(principal, cursor, limit),
                                        CorrelationIdContext.current());
    }

    @GetMapping("/{accountNumber}")
    @Operation(summary = "Get account balance, status, and effective limits")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Account returned.", useReturnTypeSchema = true,
                    headers = @Header(name = HttpHeaders.ETAG, description = "Current account-vN version.")),
            @ApiResponse(responseCode = "404", description = "RESOURCE_NOT_FOUND.")
    })
    public ResponseEntity<ApiSuccessResponse<AccountDetails>> get(
            @PathVariable String accountNumber,
            @AuthenticationPrincipal TarbankPrincipal principal) {
        var result = accounts.find(accountNumber, principal);
        return ResponseEntity.ok()
                             .eTag(ApiRequestHeaders.entityTag("account", result.version()))
                             .body(new ApiSuccessResponse<>(result.details(), CorrelationIdContext.current()));
    }

    @PatchMapping("/{accountNumber}/status")
    @Operation(summary = "Change account status")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Account status changed.", useReturnTypeSchema = true,
                    headers = @Header(name = HttpHeaders.ETAG, description = "Updated account-vN version.")),
            @ApiResponse(responseCode = "404", description = "RESOURCE_NOT_FOUND."),
            @ApiResponse(responseCode = "409", description = "INVALID_STATUS_TRANSITION."),
            @ApiResponse(responseCode = "412", description = "PRECONDITION_FAILED."),
            @ApiResponse(responseCode = "428", description = "PRECONDITION_REQUIRED.")
    })
    public ResponseEntity<ApiSuccessResponse<AccountStatusResponse>> changeStatus(
            @PathVariable String accountNumber,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @AuthenticationPrincipal TarbankPrincipal principal,
            @Valid @RequestBody ChangeAccountStatusRequest request) {
        var result = accounts.changeStatus(
                accountNumber, ApiRequestHeaders.parseEntityVersion(ifMatch, "account", "TAR-ACCOUNT-004"),
                request, principal);
        return ResponseEntity.ok()
                             .eTag(ApiRequestHeaders.entityTag("account", result.version()))
                             .body(new ApiSuccessResponse<>(result.response(), CorrelationIdContext.current()));
    }

    @PatchMapping("/{accountNumber}/daily-limits")
    @Operation(summary = "Increase today's withdrawal or transfer limits")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Daily limits updated or idempotently replayed.",
                    useReturnTypeSchema = true,
                    headers = @Header(name = HttpHeaders.ETAG, description = "Updated account-vN version.")),
            @ApiResponse(responseCode = "404", description = "RESOURCE_NOT_FOUND."),
            @ApiResponse(responseCode = "409", description = "IDEMPOTENCY_CONFLICT or REQUEST_IN_PROGRESS."),
            @ApiResponse(responseCode = "412", description = "PRECONDITION_FAILED."),
            @ApiResponse(responseCode = "422", description = "DAILY_LIMIT_OUT_OF_RANGE."),
            @ApiResponse(responseCode = "428", description = "PRECONDITION_REQUIRED.")
    })
    public ResponseEntity<ApiSuccessResponse<DailyLimitUpdateResponse>> updateDailyLimits(
            @PathVariable String accountNumber,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @AuthenticationPrincipal TarbankPrincipal principal,
            @Valid @RequestBody DailyLimitUpdateRequest request) {
        var result = features.updateLimits(
                accountNumber, ApiRequestHeaders.parseEntityVersion(ifMatch, "account", "TAR-ACCOUNT-004"),
                principal, ApiRequestHeaders.parseUuidV4(idempotencyKey), request);
        return ResponseEntity.status(result.status())
                             .eTag(ApiRequestHeaders.entityTag("account", result.body()
                                                                                .version()))
                             .body(new ApiSuccessResponse<>(result.body()
                                                                  .response(),
                                                            CorrelationIdContext.current()));
    }

    @GetMapping("/{accountNumber}/transactions")
    @Operation(summary = "Get cursor-paginated account history")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Account history page.", useReturnTypeSchema = true),
            @ApiResponse(responseCode = "404", description = "RESOURCE_NOT_FOUND.")
    })
    public ApiSuccessResponse<TransactionHistoryPage> history(
            @PathVariable String accountNumber,
            @AuthenticationPrincipal TarbankPrincipal principal,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to) {
        return new ApiSuccessResponse<>(features.history(accountNumber, principal, cursor, limit, from, to),
                                        CorrelationIdContext.current());
    }

}
