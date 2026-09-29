package com.tarbank.account.api;

import com.tarbank.account.api.AccountContracts.AccountCreatedResponse;
import com.tarbank.account.api.AccountContracts.AccountPage;
import com.tarbank.account.api.AccountContracts.CreateAccountRequest;
import com.tarbank.account.application.AccountService;
import com.tarbank.common.api.ApiSuccessResponse;
import com.tarbank.common.http.ApiRequestHeaders;
import com.tarbank.common.http.CorrelationIdContext;
import com.tarbank.security.application.TarbankPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/api/v1/customers/{customerId}/accounts",
        produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Manager account administration",
        description = "Manager-only account creation and customer account listing.")
public class CustomerAccountController {
    private final AccountService accounts;

    public CustomerAccountController(AccountService accounts) {
        this.accounts = accounts;
    }

    @PostMapping
    @Operation(summary = "Create a zero-balance customer account")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Account created or idempotently replayed.",
                    useReturnTypeSchema = true),
            @ApiResponse(responseCode = "404", description = "RESOURCE_NOT_FOUND."),
            @ApiResponse(responseCode = "409", description = "IDEMPOTENCY_CONFLICT or REQUEST_IN_PROGRESS.")
    })
    public ResponseEntity<ApiSuccessResponse<AccountCreatedResponse>> create(
            @PathVariable Long customerId,
            @AuthenticationPrincipal TarbankPrincipal principal,
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody CreateAccountRequest request) {
        var result = accounts.create(customerId, principal, ApiRequestHeaders.parseUuidV4(key), request);
        return ResponseEntity.status(result.status())
                             .body(new ApiSuccessResponse<>(result.body(), CorrelationIdContext.current()));
    }

    @GetMapping
    @Operation(summary = "List one customer's accounts")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Cursor-paginated account page.",
                    useReturnTypeSchema = true),
            @ApiResponse(responseCode = "404", description = "RESOURCE_NOT_FOUND.")
    })
    public ApiSuccessResponse<AccountPage> list(
            @PathVariable Long customerId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit) {
        return new ApiSuccessResponse<>(accounts.listForManager(customerId, cursor, limit),
                                        CorrelationIdContext.current());
    }

}
