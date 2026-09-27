package com.tarbank.customer.api;

import com.tarbank.common.api.ApiSuccessResponse;
import com.tarbank.common.http.ApiException;
import com.tarbank.common.http.CorrelationIdContext;
import com.tarbank.customer.application.CustomerService;
import com.tarbank.security.application.TarbankPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/v1/customers")
public class CustomerController {
    private static final Pattern UUID_V4 = Pattern.compile(
            "(?i)^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");

    private final CustomerService service;

    public CustomerController(CustomerService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ApiSuccessResponse<CustomerSummary>> create(
            @AuthenticationPrincipal TarbankPrincipal principal,
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody CreateCustomerRequest request) {
        var result = service.create(principal, parseKey(key), request);
        return ResponseEntity.status(result.status())
                .body(new ApiSuccessResponse<>(result.body(), CorrelationIdContext.current()));
    }

    @GetMapping("/{customerId}")
    public ResponseEntity<ApiSuccessResponse<CustomerDetails>> get(@PathVariable Long customerId) {
        return ResponseEntity.ok(new ApiSuccessResponse<>(service.find(customerId), CorrelationIdContext.current()));
    }

    private UUID parseKey(String value) {
        try {
            if (value == null || !UUID_V4.matcher(value).matches()) throw new IllegalArgumentException();
            UUID key = UUID.fromString(value);
            if (key.version() != 4 || key.variant() != 2) throw new IllegalArgumentException();
            return key;
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "The request is invalid.", "TAR-API-001");
        }
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

    public record ResidentialAddress(@NotBlank @Size(max = 2) String country, @NotBlank @Size(max = 100) String city,
                                     @NotBlank @Size(max = 20) String postalCode,
                                     @NotBlank @Size(max = 255) String line1, @Size(max = 255) String line2) {
    }

    public record IdentityDocument(@NotBlank @Size(max = 30) String type,
                                   @NotBlank @Size(max = 2) String issuingCountry,
                                   @NotBlank String number, LocalDate expiresOn) {
    }

    public record CustomerSummary(Long customerId, String username, com.tarbank.security.domain.UserStatus status) {
    }

    public record CustomerDetails(Long customerId, String username, com.tarbank.security.domain.UserStatus status,
                                  String firstName, String lastName, LocalDate dateOfBirth, String email,
                                  String phoneNumber,
                                  ResidentialAddress residentialAddress, SafeIdentityDocument identityDocument,
                                  String timezone) {
    }

    public record SafeIdentityDocument(String type, String issuingCountry, LocalDate expiresOn) {
    }
}