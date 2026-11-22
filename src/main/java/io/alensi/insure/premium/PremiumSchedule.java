package io.alensi.insure.premium;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import io.alensi.insure.platform.persistence.AuditedEntity;
import io.alensi.insure.platform.web.Ids;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

/** A policy's premium split into installments. Payments on any installment bump its version. */
@Entity
@Table(schema = "premium", name = "premium_schedule")
class PremiumSchedule extends AuditedEntity {

    @Id
    private UUID id;

    private UUID policyId;
    private String unitCode;

    @Enumerated(EnumType.STRING)
    private PaymentFrequency frequency;

    private BigDecimal totalAmount;
    private String currency;

    @OneToMany(mappedBy = "schedule", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sequenceNumber")
    private List<Installment> installments = new ArrayList<>();

    protected PremiumSchedule() {
    }

    /**
     * Equal installments rounded down to cents, with the rounding difference on the first
     * (policy-lifecycle.md). The first is due on {@code firstDueDate} (acceptance); the rest one
     * interval apart from the policy's effective date.
     */
    PremiumSchedule(PremiumSchedules.NewSchedule request) {
        this.id = Ids.newId();
        this.policyId = request.policyId();
        this.unitCode = request.unitCode();
        this.frequency = request.frequency();
        this.totalAmount = request.totalPremium();
        this.currency = request.currency();
        int count = frequency.installmentsFor(request.termMonths());
        BigDecimal each = totalAmount.divide(BigDecimal.valueOf(count), 2, RoundingMode.DOWN);
        BigDecimal first = totalAmount.subtract(each.multiply(BigDecimal.valueOf(count - 1)));
        for (int n = 1; n <= count; n++) {
            LocalDate due = n == 1 ? request.firstDueDate()
                    : request.effectiveDate().plusMonths((long) (n - 1) * frequency.intervalMonths());
            installments.add(new Installment(this, n, due, n == 1 ? first : each,
                    n == 1 ? Installment.Status.DUE : Installment.Status.SCHEDULED));
        }
    }

    void paymentApplied() {
        markModified();
    }

    UUID getId() {
        return id;
    }

    UUID getPolicyId() {
        return policyId;
    }

    String getUnitCode() {
        return unitCode;
    }

    PaymentFrequency getFrequency() {
        return frequency;
    }

    BigDecimal getTotalAmount() {
        return totalAmount;
    }

    String getCurrency() {
        return currency;
    }

    List<Installment> getInstallments() {
        return installments;
    }
}
