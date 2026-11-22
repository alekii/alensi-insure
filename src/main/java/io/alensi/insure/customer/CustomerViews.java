package io.alensi.insure.customer;

import java.time.LocalDate;
import java.util.UUID;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Request and response bodies of the customer API (openapi.yaml: CustomerCreate, Customer). */
final class CustomerViews {

    static final String E164 = "^\\+[1-9]\\d{6,14}$";

    private CustomerViews() {
    }

    /**
     * {@code nationalId} identifies individuals (national ID or passport), {@code taxNumber}
     * organisations (company registration or KRA PIN). Either is stored encrypted and never returned.
     */
    record CustomerCreate(
            @NotNull Customer.Type customerType,
            @NotBlank @Size(max = 64) String unitCode,
            @Size(max = 100) String firstName,
            @Size(max = 100) String lastName,
            @Size(max = 255) String organisationName,
            @Past LocalDate dateOfBirth,
            @Size(min = 4, max = 64) String nationalId,
            @Size(min = 4, max = 64) String taxNumber,
            @Pattern(regexp = E164, message = "must be E.164, e.g. +254712345678") String phone,
            @Email @Size(max = 320) String email,
            @Size(max = 255) String address,
            @Size(max = 100) String city,
            @Pattern(regexp = "^[A-Z]{2}$", message = "must be ISO 3166-1 alpha-2, e.g. KE") String countryCode) {
    }

    record CustomerView(UUID id, String tenantCode, String unitCode, String customerNumber,
            Customer.Type customerType, Customer.Status status, String displayName, LocalDate dateOfBirth,
            String nationalIdMasked, String phone, String email, String address, String city, String countryCode,
            long version) {
    }
}
