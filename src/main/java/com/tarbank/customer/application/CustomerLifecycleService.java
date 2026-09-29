package com.tarbank.customer.application;

import com.tarbank.account.domain.AccountEntity;
import com.tarbank.account.domain.AccountStatus;
import com.tarbank.account.persistence.AccountRepository;
import com.tarbank.common.api.ApiErrorResponse;
import com.tarbank.common.application.IdempotencyFingerprintService;
import com.tarbank.common.application.RequestIdempotencyService;
import com.tarbank.common.domain.AuditEventEntity;
import com.tarbank.common.http.ApiException;
import com.tarbank.common.http.CorrelationIdContext;
import com.tarbank.common.persistence.AuditEventRepository;
import com.tarbank.customer.api.CustomerController.ChangeCustomerStatusRequest;
import com.tarbank.customer.api.CustomerController.CustomerDetails;
import com.tarbank.customer.api.CustomerController.CustomerPage;
import com.tarbank.customer.api.CustomerController.CustomerStatusResponse;
import com.tarbank.customer.api.CustomerController.CustomerSummary;
import com.tarbank.customer.api.CustomerController.PasswordResetRequest;
import com.tarbank.customer.api.CustomerController.PasswordResetResponse;
import com.tarbank.customer.api.CustomerController.ResidentialAddress;
import com.tarbank.customer.api.CustomerController.SafeIdentityDocument;
import com.tarbank.customer.api.CustomerController.UpdateCustomerRequest;
import com.tarbank.customer.domain.CustomerEntity;
import com.tarbank.customer.persistence.CustomerQueryRepository;
import com.tarbank.customer.persistence.CustomerRepository;
import com.tarbank.security.application.PasswordPolicy;
import com.tarbank.security.application.TarbankPrincipal;
import com.tarbank.security.domain.ManagerEntity;
import com.tarbank.security.domain.UserEntity;
import com.tarbank.security.domain.UserStatus;
import com.tarbank.security.persistence.ManagerRepository;
import com.tarbank.security.persistence.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

@Service
public class CustomerLifecycleService {
    private final CustomerRepository customers;

    private final CustomerQueryRepository customerQueries;

    private final UserRepository users;

    private final ManagerRepository managers;

    private final AccountRepository accounts;

    private final AuditEventRepository audits;

    private final RequestIdempotencyService idempotency;

    private final IdempotencyFingerprintService fingerprints;

    private final PasswordEncoder passwords;

    private final PasswordPolicy passwordPolicy;

    private final JsonMapper json;

    public CustomerLifecycleService(CustomerRepository customers,
                                    CustomerQueryRepository customerQueries,
                                    UserRepository users,
                                    ManagerRepository managers,
                                    AccountRepository accounts,
                                    AuditEventRepository audits,
                                    RequestIdempotencyService idempotency,
                                    IdempotencyFingerprintService fingerprints,
                                    PasswordEncoder passwords,
                                    PasswordPolicy passwordPolicy,
                                    JsonMapper json) {
        this.customers = customers;
        this.customerQueries = customerQueries;
        this.users = users;
        this.managers = managers;
        this.accounts = accounts;
        this.audits = audits;
        this.idempotency = idempotency;
        this.fingerprints = fingerprints;
        this.passwords = passwords;
        this.passwordPolicy = passwordPolicy;
        this.json = json;
    }

    @Transactional(readOnly = true)
    public CustomerPage list(String statusValue,
                             String cursor,
                             int limit) {
        if (limit < 1 || limit > 100) {
            throw validation();
        }
        UserStatus status = parseStatus(statusValue);
        Long afterId = decodeCursor(cursor);
        List<CustomerEntity> rows = customerQueries.findPage(status, afterId, limit + 1);
        boolean hasNext = rows.size() > limit;
        List<CustomerEntity> page = hasNext ? rows.subList(0, limit) : rows;
        List<CustomerSummary> items = page.stream()
                                          .map(this::summary)
                                          .toList();
        String nextCursor = hasNext ? encodeCursor(page.getLast()
                                                       .getUserId()) : null;
        return new CustomerPage(items, nextCursor);
    }

    @Transactional(readOnly = true)
    public VersionedCustomer find(Long id) {
        CustomerEntity customer = customers.findById(id)
                                           .orElseThrow(this::notFound);
        return new VersionedCustomer(details(customer), customer.getVersion());
    }

    @Transactional
    public VersionedCustomer update(Long id,
                                    int expectedVersion,
                                    UpdateCustomerRequest request,
                                    TarbankPrincipal principal) {
        CustomerEntity customer = lockCustomer(id);
        requireVersion(customer, expectedVersion);
        ManagerEntity manager = manager(principal);
        Instant now = Instant.now();
        Set<String> changedFields = new TreeSet<>();

        String firstName = suppliedText(request.firstNameSupplied(), request.getFirstName(), "firstName", 100);
        String middleName = suppliedNullableText(request.middleNameSupplied(), request.getMiddleName(), "middleName",
                                                 100);
        String lastName = suppliedText(request.lastNameSupplied(), request.getLastName(), "lastName", 100);
        customer.getUser()
                .updateNames(firstName, request.firstNameSupplied(), middleName,
                             request.middleNameSupplied(), lastName, request.lastNameSupplied(), now);
        if (request.firstNameSupplied()) {
            changedFields.add("firstName");
        }
        if (request.middleNameSupplied()) {
            changedFields.add("middleName");
        }
        if (request.lastNameSupplied()) {
            changedFields.add("lastName");
        }

        String email = validateEmail(request.emailSupplied(), request.getEmail());
        String phone = validatePhone(request.phoneNumberSupplied(), request.getPhoneNumber());
        ResidentialAddress address = request.getResidentialAddress();
        if (request.residentialAddressSupplied() && address == null) {
            throw validation("residentialAddress", "NotNull", "must not be null");
        }
        String country = address == null ? null : country(address.country(), "residentialAddress.country");
        String city = address == null ? null : required(address.city(), 100, "residentialAddress.city");
        String postal = address == null ? null : required(address.postalCode(), 20, "residentialAddress.postalCode");
        String line1 = address == null ? null : required(address.line1(), 255, "residentialAddress.line1");
        String line2 = address == null ? null : optional(address.line2(), 255, "residentialAddress.line2");
        boolean customerChanged = customer.updateContact(email, request.emailSupplied(), phone, country, city,
                                                         postal, line1, line2);
        if (request.emailSupplied()) {
            changedFields.add("email");
        }
        if (request.phoneNumberSupplied()) {
            changedFields.add("phoneNumber");
        }
        if (request.residentialAddressSupplied()) {
            changedFields.add("residentialAddress");
        }
        if (changedFields.isEmpty()) {
            throw validation();
        }

        customers.flush();
        if (!customerChanged && customers.incrementVersionIfCurrent(id, expectedVersion) != 1) {
            throw staleVersion();
        }
        audits.save(new AuditEventEntity(manager.getUser(), "CUSTOMER_UPDATED", "CUSTOMER", id.toString(),
                                         CorrelationIdContext.current(), json(Map.of("changedFields", changedFields)),
                                         now));
        return new VersionedCustomer(details(customer), expectedVersion + 1);
    }

    @Transactional
    public VersionedStatus changeStatus(Long id,
                                        int expectedVersion,
                                        ChangeCustomerStatusRequest request,
                                        TarbankPrincipal principal) {
        CustomerEntity customer = lockCustomer(id);
        requireVersion(customer, expectedVersion);
        UserStatus previous = customer.getUser()
                                      .getStatus();
        UserStatus next = request.status();
        if (!allowed(previous, next)) {
            throw new ApiException(HttpStatus.CONFLICT, "INVALID_STATUS_TRANSITION",
                                   "The requested status transition is not allowed.", "TAR-CUSTOMER-005");
        }
        ManagerEntity manager = manager(principal);
        Instant now = Instant.now();
        customer.changeStatus(next, manager, now);
        List<AccountEntity> affected = List.of();
        if (next == UserStatus.BLOCKED || next == UserStatus.DEACTIVATED) {
            affected = accounts.lockNonDeactivatedByCustomerId(id);
            AccountStatus target = next == UserStatus.BLOCKED ? AccountStatus.BLOCKED : AccountStatus.DEACTIVATED;
            for (AccountEntity account : affected) {
                AccountStatus old = account.getStatus();
                if (old != target) {
                    account.changeStatus(target, manager, now);
                    audits.save(new AuditEventEntity(manager.getUser(), "ACCOUNT_STATUS_CHANGED", "ACCOUNT",
                                                     account.getAccountNumber(), CorrelationIdContext.current(),
                                                     json(Map.of("oldStatus", old.name(), "newStatus", target.name())),
                                                     now));
                }
            }
        }
        audits.save(new AuditEventEntity(manager.getUser(), "CUSTOMER_STATUS_CHANGED", "CUSTOMER", id.toString(),
                                         CorrelationIdContext.current(),
                                         json(Map.of("oldStatus", previous.name(), "newStatus", next.name())), now));
        customers.flush();
        return new VersionedStatus(new CustomerStatusResponse(id, next), customer.getVersion());
    }

    public RequestIdempotencyService.Result<PasswordResetResponse> resetPassword(Long customerId,
                                                                                 TarbankPrincipal principal,
                                                                                 UUID key,
                                                                                 PasswordResetRequest request) {
        UserEntity actor = users.findById(principal.userId())
                                .orElseThrow(this::notFound);
        String username = customers.findById(customerId)
                                   .map(c -> c.getUser()
                                              .getUsername())
                                   .orElseThrow(this::notFound);
        if (!passwordPolicy.isValid(request.newPassword(), username)) {
            throw validation("newPassword", "PasswordPolicy", "must satisfy the password policy");
        }
        String operation = "CUSTOMER_PASSWORD_RESET";
        String scope = "customer:" + customerId;
        IdempotencyFingerprintService.Fingerprints requestFingerprints =
                fingerprints.credential(operation, scope, request.newPassword());
        return idempotency.execute(actor, operation, scope, key,
                                   requestFingerprints.current(), requestFingerprints.legacy(),
                                   PasswordResetResponse.class,
                                   HttpStatus.OK.value(),
                                   () -> resetPasswordFirst(customerId, actor, request.newPassword()));
    }

    private PasswordResetResponse resetPasswordFirst(Long customerId,
                                                     UserEntity actor,
                                                     String newPassword) {
        lockCustomer(customerId);
        UserEntity customerUser = users.lockById(customerId)
                                       .orElseThrow(this::notFound);
        if (customerUser.getRole() != com.tarbank.security.domain.Role.CUSTOMER) {
            throw notFound();
        }
        Instant now = Instant.now();
        customerUser.resetPassword(passwords.encode(newPassword), now);
        audits.save(new AuditEventEntity(actor, "CUSTOMER_PASSWORD_RESET", "CUSTOMER", customerId.toString(),
                                         CorrelationIdContext.current(), null, now));
        return new PasswordResetResponse(customerId, "PASSWORD_RESET");
    }

    private boolean allowed(UserStatus previous,
                            UserStatus next) {
        return previous == UserStatus.ACTIVE && (next == UserStatus.BLOCKED || next == UserStatus.DEACTIVATED)
                || previous == UserStatus.BLOCKED && (next == UserStatus.ACTIVE || next == UserStatus.DEACTIVATED);
    }

    private CustomerEntity lockCustomer(Long id) {
        customers.lockRowById(id)
                 .orElseThrow(this::notFound);
        return customers.findById(id)
                        .orElseThrow(this::notFound);
    }

    private void requireVersion(CustomerEntity customer,
                                int expected) {
        if (customer.getVersion() != expected) {
            throw staleVersion();
        }
    }

    private ApiException staleVersion() {
        return new ApiException(HttpStatus.PRECONDITION_FAILED, "PRECONDITION_FAILED",
                                "The supplied customer version is stale.", "TAR-CUSTOMER-006");
    }

    private ManagerEntity manager(TarbankPrincipal principal) {
        return managers.findById(principal.userId())
                       .orElseThrow(
                               () -> new ApiException(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "Access is denied.",
                                                      "TAR-AUTH-002"));
    }

    private CustomerSummary summary(CustomerEntity customer) {
        return new CustomerSummary(customer.getUserId(), customer.getUser()
                                                                 .getUsername(), customer.getUser()
                                                                                         .getStatus());
    }

    private CustomerDetails details(CustomerEntity customer) {
        return new CustomerDetails(customer.getUserId(), customer.getUser()
                                                                 .getUsername(), customer.getUser()
                                                                                         .getStatus(),
                                   customer.getUser()
                                           .getFirstName(), customer.getUser()
                                                                    .getMiddleName(), customer.getUser()
                                                                                              .getLastName(),
                                   customer.getDateOfBirth(), customer.getEmail(), customer.getPhoneNumber(),
                                   new ResidentialAddress(customer.getResidenceCountry(), customer.getResidenceCity(),
                                                          customer.getResidencePostalCode(),
                                                          customer.getResidenceAddressLine1(),
                                                          customer.getResidenceAddressLine2()),
                                   new SafeIdentityDocument(customer.getDocumentType(),
                                                            customer.getDocumentIssuingCountry(),
                                                            customer.getDocumentExpiresOn()), customer.getTimezone());
    }

    private UserStatus parseStatus(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UserStatus.valueOf(value);
        } catch (Exception exception) {
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

    private String validatePhone(boolean supplied,
                                 String phone) {
        if (!supplied) {
            return null;
        }
        if (phone == null) {
            throw validation("phoneNumber", "NotNull", "must not be null");
        }
        String value = phone.trim();
        if (!value.matches("^\\+[1-9]\\d{1,14}$")) {
            throw validation("phoneNumber", "Pattern", "must be a valid E.164 phone number");
        }
        return value;
    }

    private String validateEmail(boolean supplied,
                                 String email) {
        if (!supplied || email == null) {
            return null;
        }
        String value = email.trim();
        if (value.length() > 320 || !value.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            throw validation("email", "Email", "must be a well-formed email address");
        }
        return value;
    }

    private String suppliedText(boolean supplied,
                                String value,
                                String field,
                                int max) {
        return supplied ? required(value, max, field) : null;
    }

    private String suppliedNullableText(boolean supplied,
                                        String value,
                                        String field,
                                        int max) {
        return supplied ? optional(value, max, field) : null;
    }

    private String required(String value,
                            int max,
                            String field) {
        if (value == null || value.trim()
                                  .isEmpty() || value.trim()
                                                     .length() > max) {
            throw validation(field, "Size", "must be non-blank and at most " + max + " characters");
        }
        return value.trim();
    }

    private String optional(String value,
                            int max,
                            String field) {
        if (value == null || value.trim()
                                  .isEmpty()) {
            return null;
        }
        if (value.trim()
                 .length() > max) {
            throw validation(field, "Size", "must be at most " + max + " characters");
        }
        return value.trim();
    }

    private String country(String value,
                           String field) {
        String normalized = required(value, 2, field).toUpperCase(Locale.ROOT);
        if (!Set.of(Locale.getISOCountries())
                .contains(normalized)) {
            throw validation(field, "CountryCode", "must be an ISO 3166-1 alpha-2 country code");
        }
        return normalized;
    }

    private String json(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Audit serialization is unavailable.", exception);
        }
    }

    private ApiException validation() {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "The request is invalid.", "TAR-API-001");
    }

    private ApiException validation(String field,
                                    String code,
                                    String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "The request is invalid.", "TAR-API-001",
                                List.of(new ApiErrorResponse.FieldError(field, code, message)));
    }

    private ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND",
                                "Requested resource was not found.", "TAR-CUSTOMER-004");
    }

    public record VersionedCustomer(CustomerDetails customer, int version) {
    }

    public record VersionedStatus(CustomerStatusResponse response, int version) {
    }
}