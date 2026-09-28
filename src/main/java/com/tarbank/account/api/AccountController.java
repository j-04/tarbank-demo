package com.tarbank.account.api;

import com.tarbank.account.application.AccountService;
import com.tarbank.account.domain.AccountStatus;
import com.tarbank.account.domain.Currency;
import com.tarbank.common.api.ApiSuccessResponse;
import com.tarbank.common.http.ApiException;
import com.tarbank.common.http.CorrelationIdContext;
import com.tarbank.security.application.TarbankPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
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
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {
    private static final Pattern ACCOUNT_ETAG = Pattern.compile("^account-v(0|[1-9][0-9]*)$");

    private final AccountService accounts;

    public AccountController(AccountService accounts) {
        this.accounts = accounts;
    }

    @GetMapping
    public ApiSuccessResponse<AccountPage> list(
            @AuthenticationPrincipal TarbankPrincipal principal,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit) {
        return new ApiSuccessResponse<>(accounts.listForOwner(principal, cursor, limit),
                                        CorrelationIdContext.current());
    }

    @GetMapping("/{accountNumber}")
    public ResponseEntity<ApiSuccessResponse<AccountDetails>> get(
            @PathVariable String accountNumber,
            @AuthenticationPrincipal TarbankPrincipal principal) {
        var result = accounts.find(accountNumber, principal);
        return ResponseEntity.ok()
                             .eTag(etag(result.version()))
                             .body(new ApiSuccessResponse<>(result.details(), CorrelationIdContext.current()));
    }

    @PatchMapping("/{accountNumber}/status")
    public ResponseEntity<ApiSuccessResponse<AccountStatusResponse>> changeStatus(
            @PathVariable String accountNumber,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @AuthenticationPrincipal TarbankPrincipal principal,
            @Valid @RequestBody ChangeAccountStatusRequest request) {
        var result = accounts.changeStatus(accountNumber, parseIfMatch(ifMatch), request, principal);
        return ResponseEntity.ok()
                             .eTag(etag(result.version()))
                             .body(new ApiSuccessResponse<>(result.response(), CorrelationIdContext.current()));
    }

    private int parseIfMatch(String value) {
        if (value == null) {
            throw new ApiException(HttpStatus.PRECONDITION_REQUIRED, "PRECONDITION_REQUIRED",
                                   "If-Match is required.", "TAR-ACCOUNT-004");
        }
        String normalized = value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")
                ? value.substring(1, value.length() - 1) : value;
        Matcher matcher = ACCOUNT_ETAG.matcher(normalized);
        if (!matcher.matches()) throw validation();
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException exception) {
            throw validation();
        }
    }

    private String etag(int version) {
        return "\"account-v" + version + "\"";
    }

    private ApiException validation() {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                                "The request is invalid.", "TAR-API-001");
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
}
