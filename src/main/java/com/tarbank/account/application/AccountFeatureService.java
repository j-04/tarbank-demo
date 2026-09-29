package com.tarbank.account.application;

import com.tarbank.account.api.AccountContracts.DailyLimitUpdateRequest;
import com.tarbank.account.api.AccountContracts.DailyLimitUpdateResponse;
import com.tarbank.account.api.AccountContracts.TransactionHistoryItem;
import com.tarbank.account.api.AccountContracts.TransactionHistoryPage;
import com.tarbank.account.domain.AccountEntity;
import com.tarbank.account.domain.AccountLimitOverrideEntity;
import com.tarbank.account.persistence.AccountFeatureQueryRepository;
import com.tarbank.account.persistence.AccountFeatureQueryRepository.HistoryPosition;
import com.tarbank.account.persistence.AccountFeatureQueryRepository.HistoryRow;
import com.tarbank.account.persistence.AccountLimitOverrideRepository;
import com.tarbank.account.persistence.AccountRepository;
import com.tarbank.common.application.RequestIdempotencyService;
import com.tarbank.common.domain.AuditEventEntity;
import com.tarbank.common.http.ApiException;
import com.tarbank.common.http.CorrelationIdContext;
import com.tarbank.common.persistence.AuditEventRepository;
import com.tarbank.money.application.EffectiveLimitService;
import com.tarbank.money.application.EffectiveLimitService.EffectiveLimit;
import com.tarbank.money.application.EffectiveLimitService.LimitWindow;
import com.tarbank.money.domain.LimitOperationType;
import com.tarbank.security.application.TarbankPrincipal;
import com.tarbank.security.domain.Role;
import com.tarbank.security.domain.UserEntity;
import com.tarbank.security.persistence.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AccountFeatureService {
    private static final String LIMIT_UPDATE_OPERATION = "DAILY_LIMIT_UPDATE";

    private static final BigDecimal MINIMUM_LIMIT = new BigDecimal("1000.0000");

    private static final BigDecimal MAXIMUM_LIMIT = new BigDecimal("3000.0000");

    private final AccountRepository accounts;

    private final AccountLimitOverrideRepository overrides;

    private final AccountFeatureQueryRepository queries;

    private final UserRepository users;

    private final AuditEventRepository audits;

    private final RequestIdempotencyService idempotency;

    private final EffectiveLimitService effectiveLimits;

    private final JsonMapper json;

    public AccountFeatureService(AccountRepository accounts,
                                 AccountLimitOverrideRepository overrides,
                                 AccountFeatureQueryRepository queries,
                                 UserRepository users,
                                 AuditEventRepository audits,
                                 RequestIdempotencyService idempotency,
                                 EffectiveLimitService effectiveLimits,
                                 JsonMapper json) {
        this.accounts = accounts;
        this.overrides = overrides;
        this.queries = queries;
        this.users = users;
        this.audits = audits;
        this.idempotency = idempotency;
        this.effectiveLimits = effectiveLimits;
        this.json = json;
    }

    /**
     * Raises one or both limits for the current customer-local day. The account version guards
     * against stale ETags, and the idempotency key makes a transport retry return the original
     * version and response without creating another override or audit event.
     *
     * @param accountNumber account whose limits are being raised
     * @param expectedVersion version parsed from the request's {@code If-Match} header
     * @param principal authenticated owner or authorized manager
     * @param key UUID identifying this logical update and any safe retry
     * @param request optional withdrawal and transfer values; at least one must be supplied
     * @return stored response, resulting entity version, effective date, and replay indicator
     */
    public RequestIdempotencyService.Result<StoredLimitUpdate> updateLimits(String accountNumber,
                                                                            int expectedVersion,
                                                                            TarbankPrincipal principal,
                                                                            UUID key,
                                                                            DailyLimitUpdateRequest request) {
        BigDecimal withdrawalInput = request.withdrawalLimit();
        BigDecimal transferInput = request.transferLimit();
        if (withdrawalInput == null && transferInput == null) {
            throw validation();
        }
        validatePrecision(withdrawalInput);
        validatePrecision(transferInput);
        validateAllowedRange(withdrawalInput);
        validateAllowedRange(transferInput);
        BigDecimal withdrawal = normalizeLimit(withdrawalInput);
        BigDecimal transfer = normalizeLimit(transferInput);
        UserEntity actor = users.findById(principal.userId())
                                .orElseThrow(this::accessDenied);
        String scope = "account:" + accountNumber;
        String requestHash = sha256(
                LIMIT_UPDATE_OPERATION + "|" + scope + "|" + value(withdrawal) + "|" + value(transfer));
        return idempotency.execute(actor, LIMIT_UPDATE_OPERATION, scope, key, requestHash, StoredLimitUpdate.class,
                                   HttpStatus.OK.value(),
                                   () -> updateLimitsFirst(accountNumber, expectedVersion, principal, actor, withdrawal,
                                                           transfer));
    }

    private StoredLimitUpdate updateLimitsFirst(String accountNumber,
                                                int expectedVersion,
                                                TarbankPrincipal principal,
                                                UserEntity actor,
                                                BigDecimal requestedWithdrawal,
                                                BigDecimal requestedTransfer) {
        AccountEntity account = accounts.lockByAccountNumber(accountNumber)
                                        .orElseThrow(this::notFound);
        requireAccess(account, principal);
        requireVersion(account, expectedVersion);
        LimitWindow window = effectiveLimits.window(account.getCustomer()
                                                           .getTimezone());
        EffectiveLimit currentWithdrawal = effectiveLimits.effectiveLimit(account, LimitOperationType.WITHDRAWAL,
                                                                          window);
        EffectiveLimit currentTransfer = effectiveLimits.effectiveLimit(account, LimitOperationType.TRANSFER, window);
        rejectDecrease(requestedWithdrawal, currentWithdrawal.amount());
        rejectDecrease(requestedTransfer, currentTransfer.amount());

        boolean withdrawalChanged = requestedWithdrawal != null && requestedWithdrawal.compareTo(
                currentWithdrawal.amount()) > 0;
        boolean transferChanged = requestedTransfer != null && requestedTransfer.compareTo(
                currentTransfer.amount()) > 0;
        if (withdrawalChanged) {
            saveOverride(account, LimitOperationType.WITHDRAWAL, requestedWithdrawal, actor, window);
        }
        if (transferChanged) {
            saveOverride(account, LimitOperationType.TRANSFER, requestedTransfer, actor, window);
        }

        BigDecimal withdrawal = withdrawalChanged ? requestedWithdrawal : currentWithdrawal.amount();
        BigDecimal transfer = transferChanged ? requestedTransfer : currentTransfer.amount();
        Instant expiresAt = withdrawalChanged || transferChanged ? window.expiresAt() : expiration(currentWithdrawal,
                                                                                                   currentTransfer);
        if (withdrawalChanged || transferChanged) {
            account.markLimitsChanged(window.now());
            audits.save(new AuditEventEntity(actor, "DAILY_LIMIT_UPDATED", "ACCOUNT", account.getAccountNumber(),
                                             CorrelationIdContext.current(),
                                             auditMetadata(currentWithdrawal.amount(), withdrawal,
                                                           currentTransfer.amount(), transfer), window.now()));
            accounts.flush();
        }
        DailyLimitUpdateResponse response = new DailyLimitUpdateResponse(account.getAccountNumber(), amount(withdrawal),
                                                                         amount(transfer), expiresAt);
        return new StoredLimitUpdate(response, account.getManagementVersion(), window.effectiveDate());
    }

    private void saveOverride(AccountEntity account,
                              LimitOperationType operation,
                              BigDecimal amount,
                              UserEntity actor,
                              LimitWindow window) {
        AccountLimitOverrideEntity override = queries.findOverride(account.getId(), operation, window.effectiveDate())
                                                     .orElseGet(() -> new AccountLimitOverrideEntity(account, operation,
                                                                                                     amount,
                                                                                                     window.effectiveDate(),
                                                                                                     window.expiresAt(),
                                                                                                     actor,
                                                                                                     window.now()));
        if (override.getLimitAmount()
                    .compareTo(amount) != 0) {
            override.increaseTo(amount, window.expiresAt(), actor, window.now());
        }
        overrides.save(override);
    }

    /**
     * Reads immutable ledger entries using a cursor bound to this account. The service fetches one
     * extra row to determine whether another page exists, so concurrent inserts cannot cause older
     * entries to move between pages.
     *
     * @param accountNumber account whose ledger history is requested
     * @param principal authenticated owner or authorized manager
     * @param cursor opaque position returned by the preceding page, or {@code null}
     * @param limit requested page size from 1 through 100
     * @param from optional inclusive creation timestamp
     * @param to optional exclusive creation timestamp
     * @return newest-first transaction-entry page and the next opaque cursor when more rows exist
     */
    @Transactional(readOnly = true)
    public TransactionHistoryPage history(String accountNumber,
                                          TarbankPrincipal principal,
                                          String cursor,
                                          int limit,
                                          Instant from,
                                          Instant to) {
        if (limit < 1 || limit > 100 || from != null && to != null && !from.isBefore(to)) {
            throw validation();
        }
        AccountEntity account = accounts.findByAccountNumber(accountNumber)
                                        .orElseThrow(this::notFound);
        requireAccess(account, principal);
        HistoryPosition position = decodeCursor(cursor, accountNumber, from, to);
        List<HistoryRow> rows = queries.findHistory(account.getId(), from, to, position, limit + 1);
        boolean hasNext = rows.size() > limit;
        List<HistoryRow> selected = hasNext ? rows.subList(0, limit) : rows;
        List<TransactionHistoryItem> items = selected.stream()
                                                     .map(this::historyItem)
                                                     .toList();
        String nextCursor = hasNext ? encodeCursor(accountNumber, from, to, selected.getLast()) : null;
        return new TransactionHistoryPage(items, nextCursor);
    }

    private TransactionHistoryItem historyItem(HistoryRow row) {
        return new TransactionHistoryItem(row.transactionId(), row.type(), row.status(), amount(row.amountDelta()),
                                          amount(row.balanceAfter()), row.currency(), row.createdAt());
    }

    private String encodeCursor(String accountNumber,
                                Instant from,
                                Instant to,
                                HistoryRow row) {
        String value = String.join("|", "v1", accountNumber, instant(from), instant(to), row.createdAt()
                                                                                            .toString(), row.entryId()
                                                                                                            .toString());
        return Base64.getUrlEncoder()
                     .withoutPadding()
                     .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private HistoryPosition decodeCursor(String cursor,
                                         String accountNumber,
                                         Instant from,
                                         Instant to) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder()
                                              .decode(cursor), StandardCharsets.UTF_8);
            String[] fields = decoded.split("\\|", -1);
            if (fields.length != 6 || !fields[0].equals("v1") || !fields[1].equals(accountNumber) || !fields[2].equals(
                    instant(from)) || !fields[3].equals(instant(to))) {
                throw new IllegalArgumentException();
            }
            return new HistoryPosition(Instant.parse(fields[4]), Long.valueOf(fields[5]));
        } catch (Exception exception) {
            throw validation();
        }
    }

    private void requireAccess(AccountEntity account,
                               TarbankPrincipal principal) {
        if (principal == null) {
            throw accessDenied();
        }
        if (principal.role() == Role.MANAGER) {
            return;
        }
        if (principal.role() != Role.CUSTOMER || !account.getCustomer()
                                                         .getUserId()
                                                         .equals(principal.userId())) {
            throw accessDenied();
        }
    }

    private void requireVersion(AccountEntity account,
                                int expectedVersion) {
        if (account.getManagementVersion() != expectedVersion) {
            throw new ApiException(HttpStatus.PRECONDITION_FAILED, "PRECONDITION_FAILED",
                                   "The supplied account version is stale.", "TAR-ACCOUNT-003");
        }
    }

    private BigDecimal normalizeLimit(BigDecimal value) {
        return value == null ? null : value.setScale(4);
    }

    private void validatePrecision(BigDecimal value) {
        if (value != null && value.scale() > 4) {
            throw validation();
        }
    }

    private void validateAllowedRange(BigDecimal value) {
        if (value != null && (value.compareTo(MINIMUM_LIMIT) < 0 || value.compareTo(MAXIMUM_LIMIT) > 0)) {
            throw limitOutOfRange();
        }
    }

    private void rejectDecrease(BigDecimal requested,
                                BigDecimal current) {
        if (requested != null && requested.compareTo(current) < 0) {
            throw limitOutOfRange();
        }
    }

    private ApiException limitOutOfRange() {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "DAILY_LIMIT_OUT_OF_RANGE",
                                "The requested daily limit is outside the permitted range.", "TAR-LIMIT-001");
    }

    private Instant expiration(EffectiveLimit withdrawal,
                               EffectiveLimit transfer) {
        return withdrawal.expiresAt() != null ? withdrawal.expiresAt() : transfer.expiresAt();
    }

    private String auditMetadata(BigDecimal oldWithdrawal,
                                 BigDecimal newWithdrawal,
                                 BigDecimal oldTransfer,
                                 BigDecimal newTransfer) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("oldWithdrawalLimit", amount(oldWithdrawal));
        metadata.put("newWithdrawalLimit", amount(newWithdrawal));
        metadata.put("oldTransferLimit", amount(oldTransfer));
        metadata.put("newTransferLimit", amount(newTransfer));
        try {
            return json.writeValueAsString(metadata);
        } catch (Exception exception) {
            throw new IllegalStateException("Daily-limit audit serialization failed.", exception);
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of()
                            .formatHex(MessageDigest.getInstance("SHA-256")
                                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Daily-limit request fingerprinting failed.", exception);
        }
    }

    private String value(BigDecimal value) {
        return value == null ? "-" : amount(value);
    }

    private String instant(Instant value) {
        return value == null ? "-" : value.toString();
    }

    private String amount(BigDecimal value) {
        return value.setScale(4)
                    .toPlainString();
    }

    private ApiException validation() {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "The request is invalid.", "TAR-API-001");
    }

    private ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "Requested resource was not found.",
                                "TAR-ACCOUNT-001");
    }

    private ApiException accessDenied() {
        return new ApiException(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "Access is denied.", "TAR-AUTH-002");
    }

    public record StoredLimitUpdate(DailyLimitUpdateResponse response, int version, LocalDate effectiveDate) {
    }
}
