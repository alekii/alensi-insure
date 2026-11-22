package io.alensi.insure.premium;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import io.alensi.insure.platform.audit.AuditLog;
import io.alensi.insure.platform.security.CurrentTenant;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Creates a policy's premium schedule, in the caller's transaction (accepting the quote). */
@Component
public class PremiumSchedules {

    private final PremiumScheduleRepository schedules;
    private final AuditLog audit;

    PremiumSchedules(PremiumScheduleRepository schedules, AuditLog audit) {
        this.schedules = schedules;
        this.audit = audit;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID create(NewSchedule request) {
        if (schedules.existsByTenantCodeAndPolicyId(CurrentTenant.require(), request.policyId())) {
            throw new IllegalStateException("Policy " + request.policyId() + " already has a premium schedule");
        }
        PremiumSchedule schedule = schedules.saveAndFlush(new PremiumSchedule(request));
        audit.record("PREMIUM_SCHEDULE_CREATED", "POLICY", request.policyId(), request.unitCode(),
                Map.of("installments", schedule.getInstallments().size(), "frequency", request.frequency().name()));
        return schedule.getId();
    }

    /**
     * @param firstDueDate when the first installment is due (the day the quote is accepted); the
     *                     rest fall one interval apart from {@code effectiveDate}
     */
    public record NewSchedule(UUID policyId, String unitCode, PaymentFrequency frequency, BigDecimal totalPremium,
            String currency, LocalDate firstDueDate, LocalDate effectiveDate, int termMonths) {
    }
}
