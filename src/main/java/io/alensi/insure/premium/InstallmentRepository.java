package io.alensi.insure.premium;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.repository.Repository;

interface InstallmentRepository extends Repository<Installment, UUID> {

    Optional<Installment> findByTenantCodeAndId(String tenantCode, UUID id);
}
