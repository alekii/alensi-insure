package io.alensi.insure.premium;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.repository.Repository;

interface PaymentReferenceRepository extends Repository<PaymentReference, UUID> {

    PaymentReference save(PaymentReference reference);

    Optional<PaymentReference> findByTenantCodeAndPayPaymentId(String tenantCode, String payPaymentId);
}
