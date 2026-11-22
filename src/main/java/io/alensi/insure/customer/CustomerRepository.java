package io.alensi.insure.customer;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

/** Every lookup names the tenant, even by ID; row-level security checks it again in the database. */
interface CustomerRepository extends Repository<Customer, UUID>, JpaSpecificationExecutor<Customer> {

    Customer save(Customer customer);

    Customer saveAndFlush(Customer customer);

    Optional<Customer> findByTenantCodeAndId(String tenantCode, UUID id);

    Optional<Customer> findByTenantCodeAndNationalIdHash(String tenantCode, String nationalIdHash);

    @Query(value = "select nextval('customer.customer_number_seq')", nativeQuery = true)
    long nextCustomerNumber();
}
