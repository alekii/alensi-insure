package io.alensi.insure.premium;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.repository.Repository;

/** Every lookup names the tenant; row-level security checks it again in the database. */
interface PremiumScheduleRepository extends Repository<PremiumSchedule, UUID> {

    PremiumSchedule saveAndFlush(PremiumSchedule schedule);

    Optional<PremiumSchedule> findByTenantCodeAndPolicyId(String tenantCode, UUID policyId);

    boolean existsByTenantCodeAndPolicyId(String tenantCode, UUID policyId);
}
