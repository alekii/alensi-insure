package io.alensi.insure.customer;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import io.alensi.insure.platform.security.CurrentTenant;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * What other modules may know about a customer in the current tenant. No permission checks: the
 * caller checks {@code customers.view} on {@link CustomerSummary#unitCode()} before showing it.
 */
@Component
public class CustomerDirectory {

    private final CustomerRepository customers;

    CustomerDirectory(CustomerRepository customers) {
        this.customers = customers;
    }

    @Transactional(readOnly = true)
    public Optional<CustomerSummary> find(UUID customerId) {
        return customers.findByTenantCodeAndId(CurrentTenant.require(), customerId).map(customer -> new CustomerSummary(
                customer.getId(), customer.getCustomerNumber(), customer.displayName(), customer.getUnitCode(),
                customer.getStatus() == Customer.Status.ACTIVE,
                customer.getCustomerType() == Customer.Type.INDIVIDUAL, customer.getDateOfBirth()));
    }

    /** {@code dateOfBirth} is null for organisations. */
    public record CustomerSummary(UUID id, String customerNumber, String displayName, String unitCode, boolean active,
            boolean individual, LocalDate dateOfBirth) {
    }
}
