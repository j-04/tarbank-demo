package com.tarbank.customer.api;

import com.tarbank.common.api.ApiSuccessResponse;
import com.tarbank.common.http.ApiException;
import com.tarbank.common.http.CorrelationIdContext;
import com.tarbank.customer.application.CustomerLifecycleService;
import com.tarbank.customer.application.CustomerService;
import com.tarbank.security.application.TarbankPrincipal;
import com.tarbank.security.domain.UserStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
@RequestMapping(value = "/api/v1/customers", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Customers", description = "Manager-only customer lifecycle operations.")
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
    @Operation(summary = "Create an adult customer")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Customer created or idempotently replayed.",
                    useReturnTypeSchema = true),
            @ApiResponse(responseCode = "409", description = "Duplicate identity, idempotency conflict, or request in progress."),
            @ApiResponse(responseCode = "422", description = "CUSTOMER_MUST_BE_ADULT.")
    })
    public ResponseEntity<ApiSuccessResponse<CustomerSummary>> create(
            @AuthenticationPrincipal TarbankPrincipal principal,
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody CreateCustomerRequest request) {
        var result = onboarding.create(principal, parseKey(key), request);
        return ResponseEntity.status(result.status())
                             .body(new ApiSuccessResponse<>(result.body(), CorrelationIdContext.current()));
    }

    @GetMapping
    @Operation(summary = "List customers")
    @ApiResponse(responseCode = "200", description = "Cursor-paginated customer page.",
            useReturnTypeSchema = true)
    public ApiSuccessResponse<CustomerPage> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit) {
        return new ApiSuccessResponse<>(lifecycle.list(status, cursor, limit), CorrelationIdContext.current());
    }

    @GetMapping("/{customerId}")
    @Operation(summary = "Get a customer")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Customer returned.", useReturnTypeSchema = true,
                    headers = @Header(name = HttpHeaders.ETAG, description = "Current customer-vN version.")),
            @ApiResponse(responseCode = "404", description = "RESOURCE_NOT_FOUND.")
    })
    public ResponseEntity<ApiSuccessResponse<CustomerDetails>> get(@PathVariable Long customerId) {
        var result = lifecycle.find(customerId);
        return ResponseEntity.ok()
                             .eTag(etag(result.version()))
                             .body(new ApiSuccessResponse<>(result.customer(), CorrelationIdContext.current()));
    }

    @PatchMapping("/{customerId}")
    @Operation(summary = "Update a customer profile")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Customer updated.", useReturnTypeSchema = true,
                    headers = @Header(name = HttpHeaders.ETAG, description = "Updated customer-vN version.")),
            @ApiResponse(responseCode = "404", description = "RESOURCE_NOT_FOUND."),
            @ApiResponse(responseCode = "412", description = "PRECONDITION_FAILED."),
            @ApiResponse(responseCode = "428", description = "PRECONDITION_REQUIRED.")
    })
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
    @Operation(summary = "Change customer status")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Customer status changed.", useReturnTypeSchema = true,
                    headers = @Header(name = HttpHeaders.ETAG, description = "Updated customer-vN version.")),
            @ApiResponse(responseCode = "404", description = "RESOURCE_NOT_FOUND."),
            @ApiResponse(responseCode = "409", description = "INVALID_STATUS_TRANSITION."),
            @ApiResponse(responseCode = "412", description = "PRECONDITION_FAILED."),
            @ApiResponse(responseCode = "428", description = "PRECONDITION_REQUIRED.")
    })
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
    @Operation(summary = "Reset a customer password", description = "Manager-assisted branch-terminal operation that invalidates existing customer JWTs.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Password reset or idempotently replayed.",
                    useReturnTypeSchema = true),
            @ApiResponse(responseCode = "404", description = "RESOURCE_NOT_FOUND."),
            @ApiResponse(responseCode = "409", description = "IDEMPOTENCY_CONFLICT or REQUEST_IN_PROGRESS.")
    })
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
            @Schema(minLength = 3, maxLength = 32, pattern = "^[a-z][a-z0-9._-]{2,31}$")
            @NotBlank String username,
            @Schema(minLength = 12, maxLength = 12, pattern = "^[\\x20-\\x7E]{12}$",
                    description = "Exactly 12 printable ASCII characters and different from the username.")
            @NotBlank String password,
            @NotBlank @Size(max = 100) String firstName, @Size(max = 100) String middleName,
            @NotBlank @Size(max = 100) String lastName, @NotNull LocalDate dateOfBirth,
            @Email @Size(max = 320) String email,
            @Schema(pattern = "^\\+[1-9][0-9]{7,14}$", description = "E.164 phone number.")
            @NotBlank String phoneNumber,
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

    public record PasswordResetRequest(
            @Schema(minLength = 12, maxLength = 12, pattern = "^[\\x20-\\x7E]{12}$",
                    description = "Exactly 12 printable ASCII characters and different from the username.")
            @NotBlank String newPassword) {
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
