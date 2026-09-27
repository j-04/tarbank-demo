package com.tarbank.customer.domain;

import com.tarbank.security.domain.ManagerEntity;
import com.tarbank.security.domain.UserEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "customers")
public class CustomerEntity {
    @Id
    @Column(name = "user_id")
    private Long userId;

    @OneToOne(optional = false)
    @MapsId
    @JoinColumn(name = "user_id")
    private UserEntity user;

    @Column(name = "date_of_birth", nullable = false)
    private LocalDate dateOfBirth;

    @Column(length = 320)
    private String email;

    @Column(name = "phone_number", nullable = false, length = 16)
    private String phoneNumber;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "residence_country", nullable = false, length = 2, columnDefinition = "char(2)")
    private String residenceCountry;

    @Column(name = "residence_city", nullable = false, length = 100)
    private String residenceCity;

    @Column(name = "residence_postal_code", nullable = false, length = 20)
    private String residencePostalCode;

    @Column(name = "residence_address_line_1", nullable = false, length = 255)
    private String residenceAddressLine1;

    @Column(name = "residence_address_line_2", length = 255)
    private String residenceAddressLine2;

    @Column(name = "document_type", nullable = false, length = 30)
    private String documentType;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "document_issuing_country", nullable = false, length = 2, columnDefinition = "char(2)")
    private String documentIssuingCountry;

    @Column(name = "document_number_encrypted", nullable = false)
    private byte[] documentNumberEncrypted;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "document_number_hash", nullable = false, length = 64, columnDefinition = "char(64)")
    private String documentNumberHash;

    @Column(name = "document_expires_on")
    private LocalDate documentExpiresOn;

    @Column(nullable = false, length = 64)
    private String timezone;

    @ManyToOne(optional = false)
    @JoinColumn(name = "manager_id")
    private ManagerEntity manager;

    @Version
    @Column(nullable = false)
    private int version;

    @ManyToOne
    @JoinColumn(name = "status_changed_by_manager_id")
    private ManagerEntity statusChangedByManager;

    @Column(name = "status_changed_at")
    private Instant statusChangedAt;

    protected CustomerEntity() {
    }

    public CustomerEntity(UserEntity user, LocalDate dateOfBirth, String email, String phoneNumber, String residenceCountry, String residenceCity, String residencePostalCode, String line1, String line2, String documentType, String documentIssuingCountry, byte[] encryptedNumber, String documentHash, LocalDate documentExpiresOn, String timezone, ManagerEntity manager) {
        this.user = user;
        this.dateOfBirth = dateOfBirth;
        this.email = email;
        this.phoneNumber = phoneNumber;
        this.residenceCountry = residenceCountry;
        this.residenceCity = residenceCity;
        this.residencePostalCode = residencePostalCode;
        this.residenceAddressLine1 = line1;
        this.residenceAddressLine2 = line2;
        this.documentType = documentType;
        this.documentIssuingCountry = documentIssuingCountry;
        this.documentNumberEncrypted = encryptedNumber;
        this.documentNumberHash = documentHash;
        this.documentExpiresOn = documentExpiresOn;
        this.timezone = timezone;
        this.manager = manager;
    }

    public Long getUserId() {
        return userId;
    }

    public UserEntity getUser() {
        return user;
    }

    public LocalDate getDateOfBirth() {
        return dateOfBirth;
    }

    public String getEmail() {
        return email;
    }

    public String getPhoneNumber() {
        return phoneNumber;
    }

    public String getResidenceCountry() {
        return residenceCountry;
    }

    public String getResidenceCity() {
        return residenceCity;
    }

    public String getResidencePostalCode() {
        return residencePostalCode;
    }

    public String getResidenceAddressLine1() {
        return residenceAddressLine1;
    }

    public String getResidenceAddressLine2() {
        return residenceAddressLine2;
    }

    public String getDocumentType() {
        return documentType;
    }

    public String getDocumentIssuingCountry() {
        return documentIssuingCountry;
    }

    public LocalDate getDocumentExpiresOn() {
        return documentExpiresOn;
    }

    public String getTimezone() {
        return timezone;
    }
}