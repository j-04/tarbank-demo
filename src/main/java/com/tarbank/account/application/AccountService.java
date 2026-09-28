package com.tarbank.account.application;

import com.tarbank.account.api.AccountController.AccountCreatedResponse;
import com.tarbank.account.api.AccountController.AccountDetails;
import com.tarbank.account.api.AccountController.AccountPage;
import com.tarbank.account.api.AccountController.AccountStatusResponse;
import com.tarbank.account.api.AccountController.AccountSummary;
import com.tarbank.account.api.AccountController.ChangeAccountStatusRequest;
import com.tarbank.account.api.AccountController.CreateAccountRequest;
import com.tarbank.account.api.AccountController.DailyLimits;
import com.tarbank.account.api.AccountController.LimitDetails;
import com.tarbank.account.domain.AccountEntity;
import com.tarbank.account.domain.AccountStatus;
import com.tarbank.account.persistence.AccountRepository;
import com.tarbank.common.application.RequestIdempotencyService;
import com.tarbank.common.domain.AuditEventEntity;
import com.tarbank.common.http.ApiException;
import com.tarbank.common.http.CorrelationIdContext;
import com.tarbank.common.persistence.AuditEventRepository;
import com.tarbank.customer.domain.CustomerEntity;
import com.tarbank.customer.persistence.CustomerRepository;
import com.tarbank.money.application.EffectiveLimitService;
import com.tarbank.money.domain.LimitOperationType;
import com.tarbank.security.application.TarbankPrincipal;
import com.tarbank.security.domain.ManagerEntity;
import com.tarbank.security.domain.Role;
import com.tarbank.security.domain.UserEntity;
import com.tarbank.security.domain.UserStatus;
import com.tarbank.security.persistence.ManagerRepository;
import com.tarbank.security.persistence.UserRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AccountService {
    private static final String CREATE_OPERATION = "ACCOUNT_CREATE";

    private final AccountRepository accounts;

    private final CustomerRepository customers;

    private final UserRepository users;

    private final ManagerRepository managers;

    private final AuditEventRepository audits;

    private final RequestIdempotencyService idempotency;

    private final AccountNumberGenerator accountNumbers;

    private final EffectiveLimitService effectiveLimits;

    private final JsonMapper json;

    public AccountService(AccountRepository accounts,
                          CustomerRepository customers,
                          UserRepository users,
                          ManagerRepository managers,
                          AuditEventRepository audits,
                          RequestIdempotencyService idempotency,
                          AccountNumberGenerator accountNumbers,
                          EffectiveLimitService effectiveLimits,
                          JsonMapper json) {
        this.accounts = accounts;
        this.customers = customers;
        this.users = users;
        this.managers = managers;
        this.audits = audits;
        this.idempotency = idempotency;
        this.accountNumbers = accountNumbers;
        this.effectiveLimits = effectiveLimits;
        this.json = json;
    }

    public RequestIdempotencyService.Result<AccountCreatedResponse> create(
            Long customerId,
            TarbankPrincipal principal,
            UUID key,
            CreateAccountRequest request) {
        UserEntity actor = users.findById(principal.userId())
                                .orElseThrow(this::accessDenied);
        String scope = "customer:" + customerId;
        String requestHash = sha256(CREATE_OPERATION + "|" + scope + "|" + request.currency()
                                                                                  .name());
        return idempotency.execute(actor, CREATE_OPERATION, scope, key, requestHash,
                                   AccountCreatedResponse.class, HttpStatus.CREATED.value(),
                                   () -> createFirst(customerId, actor, request));
    }

    private AccountCreatedResponse createFirst(Long customerId,
                                               UserEntity actor,
                                               CreateAccountRequest request) {
        CustomerEntity customer = lockCustomer(customerId);
        if (customer.getUser()
                    .getStatus() != UserStatus.ACTIVE) {
            throw customerNotActive();
        }
        ManagerEntity manager = managers.findById(actor.getId())
                                        .orElseThrow(this::accessDenied);
        Instant now = Instant.now();
        AccountEntity account = accounts.saveAndFlush(
                new AccountEntity(accountNumbers.next(), customer, request.currency(), manager, now));
        audits.save(new AuditEventEntity(actor, "ACCOUNT_CREATED", "ACCOUNT", account.getAccountNumber(),
                                         CorrelationIdContext.current(),
                                         json(Map.of("customerId", customerId,
                                                     "currency", request.currency()
                                                                        .name(),
                                                     "managerId", manager.getUserId())), now));
        return created(account);
    }

    @Transactional(readOnly = true)
    public AccountPage listForManager(Long customerId,
                                      String cursor,
                                      int limit) {
        validateLimit(limit);
        if (!customers.existsById(customerId)) {
            throw notFound();
        }
        return page(customerId, cursor, limit);
    }

    @Transactional(readOnly = true)
    public AccountPage listForOwner(TarbankPrincipal principal,
                                    String cursor,
                                    int limit) {
        validateLimit(limit);
        if (principal.role() != Role.CUSTOMER) {
            throw accessDenied();
        }
        return page(principal.userId(), cursor, limit);
    }

    private AccountPage page(Long customerId,
                             String cursor,
                             int limit) {
        Long afterId = decodeCursor(cursor);
        List<AccountEntity> rows = accounts.findPageByCustomerId(
                customerId, afterId, PageRequest.of(0, limit + 1));
        boolean hasNext = rows.size() > limit;
        List<AccountEntity> selected = hasNext ? rows.subList(0, limit) : rows;
        List<AccountSummary> items = selected.stream()
                                             .map(this::summary)
                                             .toList();
        String nextCursor = hasNext ? encodeCursor(selected.getLast()
                                                           .getId()) : null;
        return new AccountPage(items, nextCursor);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public VersionedAccount find(String accountNumber,
                                 TarbankPrincipal principal) {
        AccountEntity account = accounts.findByAccountNumber(accountNumber)
                                        .orElseThrow(this::notFound);
        requireReadAccess(account, principal);
        var window = effectiveLimits.window(account.getCustomer()
                                                   .getTimezone());
        var withdrawal = effectiveLimits.effectiveLimit(
                account, LimitOperationType.WITHDRAWAL, window);
        var transfer = effectiveLimits.effectiveLimit(
                account, LimitOperationType.TRANSFER, window);
        DailyLimits limits = new DailyLimits(
                new LimitDetails(amount(withdrawal.amount()), withdrawal.expiresAt()),
                new LimitDetails(amount(transfer.amount()), transfer.expiresAt()));
        AccountDetails details = new AccountDetails(
                account.getAccountNumber(), account.getCurrency(), amount(account.getBalance()),
                account.getStatus(), limits);
        return new VersionedAccount(details, account.getManagementVersion());
    }

    @Transactional
    public VersionedStatus changeStatus(String accountNumber,
                                        int expectedVersion,
                                        ChangeAccountStatusRequest request,
                                        TarbankPrincipal principal) {
        Long customerId = accounts.findCustomerIdByAccountNumber(accountNumber)
                                  .orElseThrow(this::notFound);
        CustomerEntity customer = lockCustomer(customerId);
        AccountEntity account = accounts.lockByAccountNumber(accountNumber)
                                        .orElseThrow(this::notFound);
        if (!customerId.equals(account.getCustomer()
                                      .getUserId())) {
            throw notFound();
        }
        requireVersion(account, expectedVersion);

        AccountStatus previous = account.getStatus();
        AccountStatus next = request.status();
        if (!allowed(previous, next)) {
            throw invalidTransition();
        }
        if (next == AccountStatus.ACTIVE && customer.getUser()
                                                    .getStatus() != UserStatus.ACTIVE) {
            throw invalidTransition();
        }

        ManagerEntity manager = manager(principal);
        Instant now = Instant.now();
        account.changeStatus(next, manager, now);
        audits.save(new AuditEventEntity(manager.getUser(), "ACCOUNT_STATUS_CHANGED", "ACCOUNT",
                                         account.getAccountNumber(), CorrelationIdContext.current(),
                                         json(Map.of("oldStatus", previous.name(), "newStatus", next.name(),
                                                     "managerId", manager.getUserId())), now));
        accounts.flush();
        return new VersionedStatus(
                new AccountStatusResponse(account.getAccountNumber(), account.getStatus()),
                account.getManagementVersion());
    }

    private boolean allowed(AccountStatus previous,
                            AccountStatus next) {
        return previous == AccountStatus.ACTIVE
                && (next == AccountStatus.BLOCKED || next == AccountStatus.DEACTIVATED)
                || previous == AccountStatus.BLOCKED
                && (next == AccountStatus.ACTIVE || next == AccountStatus.DEACTIVATED);
    }

    private void requireReadAccess(AccountEntity account,
                                   TarbankPrincipal principal) {
        if (principal.role() == Role.MANAGER) {
            return;
        }
        if (principal.role() != Role.CUSTOMER
                || !account.getCustomer()
                           .getUserId()
                           .equals(principal.userId())) {
            throw accessDenied();
        }
    }

    private CustomerEntity lockCustomer(Long customerId) {
        customers.lockRowById(customerId)
                 .orElseThrow(this::notFound);
        return customers.findById(customerId)
                        .orElseThrow(this::notFound);
    }

    private void requireVersion(AccountEntity account,
                                int expectedVersion) {
        if (account.getManagementVersion() != expectedVersion) {
            throw new ApiException(HttpStatus.PRECONDITION_FAILED, "PRECONDITION_FAILED",
                                   "The supplied account version is stale.", "TAR-ACCOUNT-003");
        }
    }

    private ManagerEntity manager(TarbankPrincipal principal) {
        if (principal.role() != Role.MANAGER) {
            throw accessDenied();
        }
        return managers.findById(principal.userId())
                       .orElseThrow(this::accessDenied);
    }

    private AccountCreatedResponse created(AccountEntity account) {
        return new AccountCreatedResponse(
                account.getAccountNumber(), account.getCustomer()
                                                   .getUserId(), account.getCurrency(),
                amount(account.getBalance()), account.getStatus());
    }

    private AccountSummary summary(AccountEntity account) {
        return new AccountSummary(
                account.getAccountNumber(), account.getCurrency(), amount(account.getBalance()), account.getStatus());
    }

    private String amount(BigDecimal value) {
        return value.setScale(4)
                    .toPlainString();
    }

    private void validateLimit(int limit) {
        if (limit < 1 || limit > 100) {
            throw validation();
        }
    }

    private Long decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder()
                                              .decode(cursor), StandardCharsets.UTF_8);
            if (!decoded.matches("[1-9][0-9]*")) {
                throw new IllegalArgumentException();
            }
            return Long.valueOf(decoded);
        } catch (Exception exception) {
            throw validation();
        }
    }

    private String encodeCursor(Long id) {
        return Base64.getUrlEncoder()
                     .withoutPadding()
                     .encodeToString(id.toString()
                                       .getBytes(StandardCharsets.UTF_8));
    }

    private String sha256(String value) {
        try {
            return HexFormat.of()
                            .formatHex(
                                    MessageDigest.getInstance("SHA-256")
                                                 .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Account request fingerprinting is unavailable.", exception);
        }
    }

    private String json(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Audit serialization is unavailable.", exception);
        }
    }

    private ApiException validation() {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                                "The request is invalid.", "TAR-API-001");
    }

    private ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND",
                                "Requested resource was not found.", "TAR-ACCOUNT-001");
    }

    private ApiException accessDenied() {
        return new ApiException(HttpStatus.FORBIDDEN, "ACCESS_DENIED",
                                "Access is denied.", "TAR-AUTH-002");
    }

    private ApiException customerNotActive() {
        return new ApiException(HttpStatus.CONFLICT, "INVALID_STATUS_TRANSITION",
                                "Accounts can be created only for an active customer.", "TAR-ACCOUNT-002");
    }

    private ApiException invalidTransition() {
        return new ApiException(HttpStatus.CONFLICT, "INVALID_STATUS_TRANSITION",
                                "The requested status transition is not allowed.", "TAR-ACCOUNT-002");
    }

    public record VersionedAccount(AccountDetails details, int version) {
    }

    public record VersionedStatus(AccountStatusResponse response, int version) {
    }
}
