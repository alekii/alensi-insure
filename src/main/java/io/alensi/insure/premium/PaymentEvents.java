package io.alensi.insure.premium;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import io.alensi.insure.platform.audit.AuditLog;
import io.alensi.insure.platform.security.CurrentTenant;
import io.alensi.insure.platform.web.BusinessCalendar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Where Alensi Pay's payment outcomes enter Insure. The RabbitMQ consumer for {@code pay.payment.*}
 * calls this once Pay exists; it carries the tenant Insure put in the payment's metadata, which is
 * set before anything is read (row-level security). Applying the same Pay payment twice changes
 * nothing, so redelivered events are harmless.
 */
@Component
public class PaymentEvents {

    private static final Logger log = LoggerFactory.getLogger(PaymentEvents.class);

    private final InstallmentRepository installments;
    private final PaymentReferenceRepository references;
    private final ApplicationEventPublisher events;
    private final AuditLog audit;
    private final BusinessCalendar calendar;
    private final TransactionTemplate tx;

    PaymentEvents(InstallmentRepository installments, PaymentReferenceRepository references,
            ApplicationEventPublisher events, AuditLog audit, BusinessCalendar calendar, TransactionTemplate tx) {
        this.installments = installments;
        this.references = references;
        this.events = events;
        this.audit = audit;
        this.calendar = calendar;
        this.tx = tx;
    }

    /** {@code pay.payment.succeeded} for a premium collection. */
    public void collectionSucceeded(CollectionSucceeded event) {
        CurrentTenant.run(event.tenantCode(), () -> tx.executeWithoutResult(status -> apply(event)));
    }

    private void apply(CollectionSucceeded event) {
        String tenant = CurrentTenant.require();
        PaymentReference reference = references.findByTenantCodeAndPayPaymentId(tenant, event.payPaymentId())
                .orElse(null);
        if (reference != null && reference.getStatus() == PaymentReference.Status.SUCCEEDED) {
            log.info("Payment {} already applied; ignoring the repeated event", event.payPaymentId());
            return;
        }
        Installment installment = installments.findByTenantCodeAndId(tenant, event.installmentId())
                .orElseThrow(() -> new IllegalArgumentException("No installment " + event.installmentId()));
        PremiumSchedule schedule = installment.getSchedule();
        if (!schedule.getCurrency().equals(event.currency())) {
            throw new IllegalArgumentException("Payment %s is in %s, the schedule in %s"
                    .formatted(event.payPaymentId(), event.currency(), schedule.getCurrency()));
        }
        if (reference == null) {
            references.save(PaymentReference.succeededCollection(installment.getId(), event.payPaymentId(),
                    event.amount(), event.currency(), event.succeededAt()));
        } else {
            reference.succeeded(event.succeededAt());
        }
        boolean completed = installment.applyPayment(event.amount(), event.succeededAt());
        schedule.paymentApplied();

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("installment", installment.getSequenceNumber());
        details.put("amount", event.amount().toPlainString());
        details.put("payPaymentId", event.payPaymentId());
        details.put("installmentPaid", completed);
        audit.record("PREMIUM_PAYMENT_RECEIVED", "POLICY", schedule.getPolicyId(), schedule.getUnitCode(), details);
        if (completed) {
            events.publishEvent(new InstallmentPaid(schedule.getPolicyId(), installment.getId(),
                    installment.getSequenceNumber(), calendar.dateOf(event.succeededAt())));
        }
    }

    /** From Pay's event: {@code tenantCode} and {@code installmentId} are the metadata Insure sent. */
    public record CollectionSucceeded(String tenantCode, UUID installmentId, String payPaymentId, BigDecimal amount,
            String currency, Instant succeededAt) {
    }
}
