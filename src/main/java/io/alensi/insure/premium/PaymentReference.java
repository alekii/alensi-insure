package io.alensi.insure.premium;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import io.alensi.insure.platform.persistence.TenantOwned;
import io.alensi.insure.platform.web.Ids;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Insure's record of a payment executed by Alensi Pay: only Pay's IDs and statuses, never account details. */
@Entity
@Table(schema = "premium", name = "payment_reference")
class PaymentReference extends TenantOwned {

    enum Direction { COLLECTION, DISBURSEMENT, REFUND }

    enum Status { INITIATED, PENDING, SUCCEEDED, FAILED, CANCELLED, REFUNDED, UNKNOWN }

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    private Direction direction;

    private UUID installmentId;
    private String payPaymentId;
    private String idempotencyKey;
    private BigDecimal amount;
    private String currency;

    @Enumerated(EnumType.STRING)
    private Status status;

    private Instant lastEventAt;

    protected PaymentReference() {
    }

    /** A collection Pay reports as succeeded that Insure didn't request itself (e.g. paid by paybill). */
    static PaymentReference succeededCollection(UUID installmentId, String payPaymentId, BigDecimal amount,
            String currency, Instant at) {
        PaymentReference reference = new PaymentReference();
        reference.id = Ids.newId();
        reference.direction = Direction.COLLECTION;
        reference.installmentId = installmentId;
        reference.payPaymentId = payPaymentId;
        reference.idempotencyKey = "pay:" + payPaymentId;
        reference.amount = amount;
        reference.currency = currency;
        reference.status = Status.SUCCEEDED;
        reference.lastEventAt = at;
        return reference;
    }

    void succeeded(Instant at) {
        this.status = Status.SUCCEEDED;
        this.lastEventAt = at;
    }

    Status getStatus() {
        return status;
    }

    BigDecimal getAmount() {
        return amount;
    }
}
