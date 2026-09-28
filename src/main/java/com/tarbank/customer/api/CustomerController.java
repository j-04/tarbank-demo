package com.tarbank.customer.api;

import com.tarbank.common.api.ApiSuccessResponse;
import com.tarbank.common.http.ApiException;
import com.tarbank.common.http.CorrelationIdContext;
import com.tarbank.customer.application.CustomerLifecycleService;
import com.tarbank.customer.application.CustomerService;
import com.tarbank.security.application.TarbankPrincipal;
import com.tarbank.security.domain.UserStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/v1/customers")
public class CustomerController {
    private static final Pattern UUID_V4 = Pattern.compile(
            "(?i)^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");

    private static final Pattern CUSTOMER_ETAG = Pattern.compile("^customer-v(0|[1-9][0-9]*)$");

    private final CustomerService onboarding;

    private final CustomerLifecycleService lifecycle;

    public CustomerController(CustomerService onboarding,
                              CustomerLifecycleService lifecycle) {
        this.onboarding = onboarding;
        this.lifecycle = lifecycle;
    }

    @PostMapping
    public ResponseEntity<ApiSuccessResponse<CustomerSummary>> create(
            @AuthenticationPrincipal TarbankPrincipal principal,
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody CreateCustomerRequest request) {
        var result = onboarding.create(principal, parseKey(key), request);
        return ResponseEntity.status(result.status())
                             .body(new ApiSuccessResponse<>(result.body(), CorrelationIdContext.current()));
    }

    @GetMapping
    public ApiSuccessResponse<CustomerPage> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit) {
        return new ApiSuccessResponse<>(lifecycle.list(status, cursor, limit), CorrelationIdContext.current());
    }

    @GetMapping("/{customerId}")
    public ResponseEntity<ApiSuccessResponse<CustomerDetails>> get(@PathVariable Long customerId) {
        var result = lifecycle.find(customerId);
        return ResponseEntity.ok()
                             .eTag(etag(result.version()))
                             .body(new ApiSuccessResponse<>(result.customer(), CorrelationIdContext.current()));
    }

    @PatchMapping("/{customerId}")
    public ResponseEntity<ApiSuccessResponse<CustomerDetails>> update(
            @PathVariable Long customerId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @AuthenticationPrincipal TarbankPrincipal principal,
            @Valid @RequestBody UpdateCustomerRequest request) {
        var result = lifecycle.update(customerId, parseIfMatch(ifMatch), request, principal);
        return ResponseEntity.ok()
                             .eTag(etag(result.version()))
                             .body(new ApiSuccessResponse<>(result.customer(), CorrelationIdContext.current()));
    }

    @PatchMapping("/{customerId}/status")
    public ResponseEntity<ApiSuccessResponse<CustomerStatusResponse>> changeStatus(
            @PathVariable Long customerId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @AuthenticationPrincipal TarbankPrincipal principal,
            @Valid @RequestBody ChangeCustomerStatusRequest request) {
        var result = lifecycle.changeStatus(customerId, parseIfMatch(ifMatch), request, principal);
        return ResponseEntity.ok()
                             .eTag(etag(result.version()))
                             .body(new ApiSuccessResponse<>(result.response(), CorrelationIdContext.current()));
    }

    @PostMapping("/{customerId}/password-reset")
    public ResponseEntity<ApiSuccessResponse<PasswordResetResponse>> resetPassword(
            @PathVariable Long customerId,
            @RequestHeader("Idempotency-Key") String key,
            @AuthenticationPrincipal TarbankPrincipal principal,
            @Valid @RequestBody PasswordResetRequest request) {
        var result = lifecycle.resetPassword(customerId, principal, parseKey(key), request);
        return ResponseEntity.status(result.status())
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
            throw validation();
        }
    }

    private int parseIfMatch(String value) {
        if (value == null) {
            throw new ApiException(HttpStatus.PRECONDITION_REQUIRED, "PRECONDITION_REQUIRED",
                                   "If-Match is required.", "TAR-CUSTOMER-007");
        }
        String normalized = value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")
                ? value.substring(1, value.length() - 1) : value;
        Matcher matcher = CUSTOMER_ETAG.matcher(normalized);
        if (!matcher.matches()) {
            throw validation();
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException exception) {
            throw validation();
        }
    }

    private String etag(int version) {
        return "\"customer-v" + version + "\"";
    }

    private ApiException validation() {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                                "The request is invalid.", "TAR-API-001");
    }

    public record CreateCustomerRequest(
            @NotBlank String username, @NotBlank String password,
            @NotBlank @Size(max = 100) String firstName, @Size(max = 100) String middleName,
            @NotBlank @Size(max = 100) String lastName, @NotNull LocalDate dateOfBirth,
            @Email @Size(max = 320) String email, @NotBlank String phoneNumber,
            @NotNull @Valid ResidentialAddress residentialAddress,
            @NotNull @Valid IdentityDocument identityDocument,
            @NotBlank @Size(max = 64) String timezone) {
    }

    public record ResidentialAddress(@NotBlank @Size(max = 2) String country,
                                     @NotBlank @Size(max = 100) String city,
                                     @NotBlank @Size(max = 20) String postalCode,
                                     @NotBlank @Size(max = 255) String line1, @Size(max = 255) String line2) {
    }

    public record IdentityDocument(@NotBlank @Size(max = 30) String type,
                                   @NotBlank @Size(max = 2) String issuingCountry, @NotBlank String number,
                                   LocalDate expiresOn) {
    }

    public record CustomerSummary(Long customerId, String username, UserStatus status) {
    }

    public record CustomerPage(List<CustomerSummary> items, String nextCursor) {
    }

    public record CustomerDetails(Long customerId, String username, UserStatus status, String firstName,
                                  String middleName, String lastName, LocalDate dateOfBirth, String email,
                                  String phoneNumber,
                                  ResidentialAddress residentialAddress, SafeIdentityDocument identityDocument,
                                  String timezone) {
    }

    public record SafeIdentityDocument(String type, String issuingCountry, LocalDate expiresOn) {
    }

    public record ChangeCustomerStatusRequest(@NotNull UserStatus status) {
    }

    public record CustomerStatusResponse(Long customerId, UserStatus status) {
    }

    public record PasswordResetRequest(@NotBlank String newPassword) {
    }

    public record PasswordResetResponse(Long customerId, String status) {
    }

    public static final class UpdateCustomerRequest {
        private String firstName;

        private boolean firstNameSupplied;

        private String middleName;

        private boolean middleNameSupplied;

        private String lastName;

        private boolean lastNameSupplied;

        private String email;

        private boolean emailSupplied;

        private String phoneNumber;

        private boolean phoneNumberSupplied;

        @Valid
        private ResidentialAddress residentialAddress;

        private boolean residentialAddressSupplied;

        public String getFirstName() {
            return firstName;
        }

        public void setFirstName(String firstName) {
            this.firstName = firstName;
            this.firstNameSupplied = true;
        }

        public boolean firstNameSupplied() {
            return firstNameSupplied;
        }

        public String getMiddleName() {
            return middleName;
        }

        public void setMiddleName(String middleName) {
            this.middleName = middleName;
            this.middleNameSupplied = true;
        }

        public boolean middleNameSupplied() {
            return middleNameSupplied;
        }

        public String getLastName() {
            return lastName;
        }

        public void setLastName(String lastName) {
            this.lastName = lastName;
            this.lastNameSupplied = true;
        }

        public boolean lastNameSupplied() {
            return lastNameSupplied;
        }

        public String getEmail() {
            return email;
        }

        public void setEmail(String email) {
            this.email = email;
            this.emailSupplied = true;
        }

        public boolean emailSupplied() {
            return emailSupplied;
        }

        public String getPhoneNumber() {
            return phoneNumber;
        }

        public void setPhoneNumber(String phoneNumber) {
            this.phoneNumber = phoneNumber;
            this.phoneNumberSupplied = true;
        }

        public boolean phoneNumberSupplied() {
            return phoneNumberSupplied;
        }

        public ResidentialAddress getResidentialAddress() {
            return residentialAddress;
        }

        public void setResidentialAddress(ResidentialAddress residentialAddress) {
            this.residentialAddress = residentialAddress;
            this.residentialAddressSupplied = true;
        }

        public boolean residentialAddressSupplied() {
            return residentialAddressSupplied;
        }
    }
}
