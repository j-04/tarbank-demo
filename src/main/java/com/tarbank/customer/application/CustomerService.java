package com.tarbank.customer.application;

import com.tarbank.common.application.RequestIdempotencyService;
import com.tarbank.common.domain.AuditEventEntity;
import com.tarbank.common.http.ApiException;
import com.tarbank.common.http.CorrelationIdContext;
import com.tarbank.common.persistence.AuditEventRepository;
import com.tarbank.customer.api.CustomerController.CreateCustomerRequest;
import com.tarbank.customer.api.CustomerController.CustomerDetails;
import com.tarbank.customer.api.CustomerController.CustomerSummary;
import com.tarbank.customer.api.CustomerController.ResidentialAddress;
import com.tarbank.customer.api.CustomerController.SafeIdentityDocument;
import com.tarbank.customer.domain.CustomerEntity;
import com.tarbank.customer.persistence.CustomerRepository;
import com.tarbank.security.application.PasswordPolicy;
import com.tarbank.security.application.SensitiveDocumentService;
import com.tarbank.security.application.TarbankPrincipal;
import com.tarbank.security.domain.ManagerEntity;
import com.tarbank.security.domain.Role;
import com.tarbank.security.domain.UserEntity;
import com.tarbank.security.persistence.ManagerRepository;
import com.tarbank.security.persistence.UserRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class CustomerService {
    private final UserRepository users;

    private final ManagerRepository managers;

    private final CustomerRepository customers;

    private final PasswordEncoder passwords;

    private final PasswordPolicy passwordPolicy;

    private final SensitiveDocumentService documents;

    private final RequestIdempotencyService idempotency;

    private final AuditEventRepository audits;

    private final JsonMapper json;

    private final Clock clock = Clock.systemUTC();

    public CustomerService(UserRepository users,
                           ManagerRepository managers,
                           CustomerRepository customers,
                           PasswordEncoder passwords,
                           PasswordPolicy passwordPolicy,
                           SensitiveDocumentService documents,
                           RequestIdempotencyService idempotency,
                           AuditEventRepository audits,
                           JsonMapper json) {
        this.users = users;
        this.managers = managers;
        this.customers = customers;
        this.passwords = passwords;
        this.passwordPolicy = passwordPolicy;
        this.documents = documents;
        this.idempotency = idempotency;
        this.audits = audits;
        this.json = json;
    }

    public RequestIdempotencyService.Result<CustomerSummary> create(
            TarbankPrincipal principal,
            UUID key,
            CreateCustomerRequest request) {
        UserEntity actor = users.findById(principal.userId())
                                .orElseThrow(this::notFound);
        String username = lower(request.username());
        String documentNumber = documents.normalize(request.identityDocument()
                                                           .number());
        String documentHash = documents.lookupHash(documentNumber);
        validateReplayStable(request, username);
        String requestHash = hash(new CustomerCreateHash(username, request.password(), trim(request.firstName()),
                                                         nullableTrim(request.middleName()), trim(request.lastName()), request.dateOfBirth(),
                                                         nullableTrim(request.email()), request.phoneNumber()
                                                                                               .trim(),
                                                         new AddressHash(country(request.residentialAddress()
                                                                                        .country()), trim(request.residentialAddress()
                                                                                                                 .city()),
                                                                         trim(request.residentialAddress()
                                                                                     .postalCode()), trim(request.residentialAddress()
                                                                                                                 .line1()),
                                                                         nullableTrim(request.residentialAddress()
                                                                                             .line2())),
                                                         new DocumentHash(trim(request.identityDocument()
                                                                                      .type()).toUpperCase(Locale.ROOT),
                                                                          country(request.identityDocument()
                                                                                         .issuingCountry()), documentNumber,
                                                                          request.identityDocument()
                                                                                 .expiresOn()),
                                                         request.timezone()
                                                                .trim()));
        try {
            return idempotency.execute(actor, "CUSTOMER_CREATE", "customers", key, requestHash,
                                       CustomerSummary.class, HttpStatus.CREATED.value(), () -> {
                        validateFirstExecution(request);
                        return createFirst(actor, username, documentNumber, documentHash, request);
                    });
        } catch (DataIntegrityViolationException exception) {
            throw translateUniqueConstraint(exception);
        }
    }

    private CustomerSummary createFirst(UserEntity actor,
                                        String username,
                                        String documentNumber,
                                        String documentHash,
                                        CreateCustomerRequest request) {
        if (users.findByUsername(username)
                 .isPresent()) {
            throw conflict("USERNAME_ALREADY_EXISTS", "Username is already in use.", "TAR-CUSTOMER-002");
        }
        String type = trim(request.identityDocument()
                                  .type()).toUpperCase(Locale.ROOT);
        String issuingCountry = country(request.identityDocument()
                                               .issuingCountry());
        if (customers.findByDocumentTypeAndDocumentIssuingCountryAndDocumentNumberHash(type, issuingCountry, documentHash)
                     .isPresent()) {
            throw conflict("IDENTITY_DOCUMENT_ALREADY_EXISTS", "Identity document is already in use.", "TAR-CUSTOMER-003");
        }
        ManagerEntity manager = managers.findById(actor.getId())
                                        .orElseThrow(
                                                () -> new ApiException(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "Access is denied.", "TAR-AUTH-002"));
        Instant now = Instant.now(clock);
        UserEntity user = users.saveAndFlush(new UserEntity(Role.CUSTOMER, username, passwords.encode(request.password()),
                                                            trim(request.firstName()), nullableTrim(request.middleName()), trim(request.lastName()), now));
        CustomerEntity customer = customers.saveAndFlush(new CustomerEntity(user, request.dateOfBirth(),
                                                                            nullableTrim(request.email()), request.phoneNumber()
                                                                                                                  .trim(), country(request.residentialAddress()
                                                                                                                                          .country()),
                                                                            trim(request.residentialAddress()
                                                                                        .city()), trim(request.residentialAddress()
                                                                                                              .postalCode()),
                                                                            trim(request.residentialAddress()
                                                                                        .line1()), nullableTrim(request.residentialAddress()
                                                                                                                       .line2()), type,
                                                                            issuingCountry, documents.encrypt(documentNumber), documentHash, request.identityDocument()
                                                                                                                                                    .expiresOn(),
                                                                            request.timezone()
                                                                                   .trim(), manager));
        audits.save(new AuditEventEntity(actor, "CUSTOMER_CREATED", "CUSTOMER", String.valueOf(customer.getUserId()),
                                         CorrelationIdContext.current(), "{\"customerId\":" + customer.getUserId() + "}", now));
        return new CustomerSummary(customer.getUserId(), user.getUsername(), user.getStatus());
    }

    public CustomerDetails find(Long id) {
        CustomerEntity customer = customers.findById(id)
                                           .orElseThrow(this::notFound);
        return new CustomerDetails(customer.getUserId(), customer.getUser()
                                                                 .getUsername(), customer.getUser()
                                                                                         .getStatus(),
                                   customer.getUser()
                                           .getFirstName(), customer.getUser()
                                                                    .getMiddleName(), customer.getUser()
                                                                                              .getLastName(), customer.getDateOfBirth(),
                                   customer.getEmail(), customer.getPhoneNumber(), new ResidentialAddress(customer.getResidenceCountry(),
                                                                                                          customer.getResidenceCity(), customer.getResidencePostalCode(), customer.getResidenceAddressLine1(),
                                                                                                          customer.getResidenceAddressLine2()), new SafeIdentityDocument(customer.getDocumentType(),
                                                                                                                                                                         customer.getDocumentIssuingCountry(), customer.getDocumentExpiresOn()), customer.getTimezone());
    }

    private void validateReplayStable(CreateCustomerRequest request,
                                      String username) {
        if (!username.matches("^[a-z][a-z0-9._-]{2,31}$")) throw invalid();
        if (!passwordPolicy.isValid(request.password(), username)) throw invalid();
        if (!request.phoneNumber()
                    .trim()
                    .matches("^\\+[1-9]\\d{1,14}$")) throw invalid();
        country(request.residentialAddress()
                       .country());
        country(request.identityDocument()
                       .issuingCountry());
        try {
            ZoneId.of(request.timezone()
                             .trim());
        } catch (Exception exception) {
            throw invalid();
        }
    }

    private void validateFirstExecution(CreateCustomerRequest request) {
        if (request.dateOfBirth() == null || request.dateOfBirth()
                                                    .plusYears(18)
                                                    .isAfter(LocalDate.now(clock))) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "CUSTOMER_MUST_BE_ADULT",
                                   "Customer must be at least 18 years old.", "TAR-CUSTOMER-001");
        }
        if (request.identityDocument()
                   .expiresOn() != null
                && request.identityDocument()
                          .expiresOn()
                          .isBefore(LocalDate.now(clock))) throw invalid();
    }

    private ApiException translateUniqueConstraint(DataIntegrityViolationException exception) {
        String constraint = constraintName(exception);
        if ("uk_users_username".equals(constraint)) {
            return conflict("USERNAME_ALREADY_EXISTS", "Username is already in use.", "TAR-CUSTOMER-002");
        }
        if ("uk_customers_document".equals(constraint)) {
            return conflict("IDENTITY_DOCUMENT_ALREADY_EXISTS", "Identity document is already in use.", "TAR-CUSTOMER-003");
        }
        throw exception;
    }

    private String constraintName(Throwable exception) {
        for (Throwable current = exception; current != null; current = current.getCause()) {
            if (current instanceof ConstraintViolationException violation) return violation.getConstraintName();
        }
        return null;
    }

    private ApiException invalid() {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "The request is invalid.", "TAR-API-001");
    }

    private ApiException conflict(String code,
                                  String message,
                                  String internal) {
        return new ApiException(HttpStatus.CONFLICT, code, message, internal);
    }

    private ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "Requested resource was not found.", "TAR-CUSTOMER-004");
    }

    private String country(String value) {
        String country = trim(value).toUpperCase(Locale.ROOT);
        if (!Set.of(Locale.getISOCountries())
                .contains(country)) throw invalid();
        return country;
    }

    private String lower(String value) {
        return trim(value).toLowerCase(Locale.ROOT);
    }

    private String trim(String value) {
        if (value == null || value.trim()
                                  .isEmpty()) throw invalid();
        return value.trim();
    }

    private String nullableTrim(String value) {
        return value == null || value.trim()
                                     .isEmpty() ? null : value.trim();
    }

    private String hash(Object body) {
        try {
            return HexFormat.of()
                            .formatHex(MessageDigest.getInstance("SHA-256")
                                                    .digest(json.writeValueAsBytes(body)));
        } catch (Exception exception) {
            throw new IllegalStateException("Request hashing is unavailable.", exception);
        }
    }

    private record CustomerCreateHash(String username, String password, String firstName, String middleName,
                                      String lastName, LocalDate dateOfBirth, String email, String phone,
                                      AddressHash address,
                                      DocumentHash document, String timezone) {
    }

    private record AddressHash(String country, String city, String postal, String line1, String line2) {
    }

    private record DocumentHash(String type, String country, String number, LocalDate expires) {
    }
}