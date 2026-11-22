package io.alensi.insure.policy;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

/** Every lookup names the tenant; row-level security checks it again in the database. */
interface PolicyRepository extends Repository<Policy, UUID>, JpaSpecificationExecutor<Policy> {

    Policy saveAndFlush(Policy policy);

    void flush();

    Optional<Policy> findByTenantCodeAndId(String tenantCode, UUID id);

    @Query(value = "select nextval('policy.policy_number_seq')", nativeQuery = true)
    long nextPolicyNumber();
}
