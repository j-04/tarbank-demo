package com.tarbank.customer.api;

import com.tarbank.security.domain.UserStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

public final class CustomerContracts {
    private CustomerContracts() {
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
                                     @NotBlank @Size(max = 255) String line1,
                                     @Size(max = 255) String line2) {
    }

    public record IdentityDocument(@NotBlank @Size(max = 30) String type,
                                   @NotBlank @Size(max = 2) String issuingCountry,
                                   @NotBlank String number,
                                   LocalDate expiresOn) {
    }

    public record CustomerSummary(Long customerId, String username, UserStatus status) {
    }

    public record CustomerPage(List<CustomerSummary> items, String nextCursor) {
    }

    public record CustomerDetails(Long customerId, String username, UserStatus status, String firstName,
                                  String middleName, String lastName, LocalDate dateOfBirth, String email,
                                  String phoneNumber, ResidentialAddress residentialAddress,
                                  SafeIdentityDocument identityDocument, String timezone) {
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
