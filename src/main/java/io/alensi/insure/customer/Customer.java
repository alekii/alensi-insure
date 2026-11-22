package io.alensi.insure.customer;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(schema = "customer", name = "customer")
class Customer {

    enum Type { INDIVIDUAL, ORGANIZATION }

    enum Status { ACTIVE, INACTIVE }

    @Id
    private UUID id;

    private String tenantCode;
    private String customerNumber;

    @Enumerated(EnumType.STRING)
    private Type customerType;

    private String firstName;
    private String lastName;
    private String organizationName;
    private LocalDate dateOfBirth;
    private byte[] nationalIdEncrypted;
    private String nationalIdHash;
    private String email;
    private String phone;

    @Column(name = "address_line1")
    private String address;

    private String city;

    private String countryCode;

    private String unitCode;

    @Enumerated(EnumType.STRING)
    private Status status;

    @Version
    private Long version;

    private Instant createdAt;
    private Instant updatedAt;
    private UUID createdBy;
    private UUID updatedBy;

    protected Customer() {
    }

    Customer(UUID id, String tenantCode, String customerNumber, Type customerType, String unitCode, UUID actor,
            Instant now) {
        this.id = id;
        this.tenantCode = tenantCode;
        this.customerNumber = customerNumber;
        this.customerType = customerType;
        this.unitCode = unitCode;
        this.status = Status.ACTIVE;
        this.createdAt = now;
        this.updatedAt = now;
        this.createdBy = actor;
        this.updatedBy = actor;
    }

    void name(String firstName, String lastName, String organizationName) {
        this.firstName = firstName;
        this.lastName = lastName;
        this.organizationName = organizationName;
    }

    void identity(LocalDate dateOfBirth, byte[] nationalIdEncrypted, String nationalIdHash) {
        this.dateOfBirth = dateOfBirth;
        this.nationalIdEncrypted = nationalIdEncrypted;
        this.nationalIdHash = nationalIdHash;
    }

    void contact(String email, String phone, String address, String city, String countryCode) {
        this.email = email;
        this.phone = phone;
        this.address = address;
        this.city = city;
        this.countryCode = countryCode;
    }

    void setEmail(String email) {
        this.email = email;
    }

    void setPhone(String phone) {
        this.phone = phone;
    }

    void setAddress(String address) {
        this.address = address;
    }

    void deactivate() {
        this.status = Status.INACTIVE;
    }

    void touch(UUID actor, Instant now) {
        this.updatedBy = actor;
        this.updatedAt = now;
    }

    String displayName() {
        return customerType == Type.ORGANIZATION ? organizationName : firstName + " " + lastName;
    }

    UUID getId() {
        return id;
    }

    String getTenantCode() {
        return tenantCode;
    }

    String getCustomerNumber() {
        return customerNumber;
    }

    Type getCustomerType() {
        return customerType;
    }

    LocalDate getDateOfBirth() {
        return dateOfBirth;
    }

    byte[] getNationalIdEncrypted() {
        return nationalIdEncrypted;
    }

    String getEmail() {
        return email;
    }

    String getPhone() {
        return phone;
    }

    String getAddress() {
        return address;
    }

    String getCity() {
        return city;
    }

    String getCountryCode() {
        return countryCode;
    }

    String getUnitCode() {
        return unitCode;
    }

    Status getStatus() {
        return status;
    }

    long getVersion() {
        return version;
    }
}
