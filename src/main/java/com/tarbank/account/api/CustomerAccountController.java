package com.tarbank.account.api;

import com.tarbank.account.api.AccountController.AccountCreatedResponse;
import com.tarbank.account.api.AccountController.AccountPage;
import com.tarbank.account.api.AccountController.CreateAccountRequest;
import com.tarbank.account.application.AccountService;
import com.tarbank.common.api.ApiSuccessResponse;
import com.tarbank.common.http.ApiException;
import com.tarbank.common.http.CorrelationIdContext;
import com.tarbank.security.application.TarbankPrincipal;
import jakarta.validation.Valid;
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

import java.util.UUID;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/v1/customers/{customerId}/accounts")
public class CustomerAccountController {
    private static final Pattern UUID_V4 = Pattern.compile(
            "(?i)^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");

    private final AccountService accounts;

    public CustomerAccountController(AccountService accounts) {
        this.accounts = accounts;
    }

    @PostMapping
    public ResponseEntity<ApiSuccessResponse<AccountCreatedResponse>> create(
            @PathVariable Long customerId,
            @AuthenticationPrincipal TarbankPrincipal principal,
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody CreateAccountRequest request) {
        var result = accounts.create(customerId, principal, parseKey(key), request);
        return ResponseEntity.status(result.status())
                             .body(new ApiSuccessResponse<>(result.body(), CorrelationIdContext.current()));
    }

    @GetMapping
    public ApiSuccessResponse<AccountPage> list(
            @PathVariable Long customerId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit) {
        return new ApiSuccessResponse<>(accounts.listForManager(customerId, cursor, limit),
                                        CorrelationIdContext.current());
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
            throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                                   "The request is invalid.", "TAR-API-001");
        }
    }
}
