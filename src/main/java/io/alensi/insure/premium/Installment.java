package io.alensi.insure.premium;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import io.alensi.insure.platform.persistence.TenantOwned;
import io.alensi.insure.platform.web.Ids;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(schema = "premium", name = "installment")
class Installment extends TenantOwned {

    enum Status { SCHEDULED, DUE, PAYMENT_PENDING, PAID, OVERDUE, WAIVED, CANCELLED }

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "schedule_id")
    private PremiumSchedule schedule;

    private int sequenceNumber;
    private LocalDate dueDate;
    private BigDecimal amount;
    private BigDecimal paidAmount;

    @Enumerated(EnumType.STRING)
    private Status status;

    private Instant paidAt;

    protected Installment() {
    }

    Installment(PremiumSchedule schedule, int sequenceNumber, LocalDate dueDate, BigDecimal amount, Status status) {
        this.id = Ids.newId();
        this.schedule = schedule;
        this.sequenceNumber = sequenceNumber;
        this.dueDate = dueDate;
        this.amount = amount;
        this.paidAmount = BigDecimal.ZERO;
        this.status = status;
    }

    /** Adds a succeeded payment; true when this payment completes the installment. */
    boolean applyPayment(BigDecimal payment, Instant at) {
        paidAmount = paidAmount.add(payment);
        if (status != Status.PAID && paidAmount.compareTo(amount) >= 0) {
            status = Status.PAID;
            paidAt = at;
            return true;
        }
        return false;
    }

    UUID getId() {
        return id;
    }

    PremiumSchedule getSchedule() {
        return schedule;
    }

    int getSequenceNumber() {
        return sequenceNumber;
    }

    LocalDate getDueDate() {
        return dueDate;
    }

    BigDecimal getAmount() {
        return amount;
    }

    BigDecimal getPaidAmount() {
        return paidAmount;
    }

    Status getStatus() {
        return status;
    }

    Instant getPaidAt() {
        return paidAt;
    }
}
