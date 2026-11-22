package io.alensi.insure.premium;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import io.alensi.identity.client.AlensiAccess;
import io.alensi.insure.platform.security.CurrentTenant;
import io.alensi.insure.platform.security.InsurePermissions;
import io.alensi.insure.platform.web.ApiProblem;
import io.alensi.insure.platform.web.Money;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
class PremiumController {

    private final PremiumScheduleRepository schedules;
    private final AlensiAccess access;

    PremiumController(PremiumScheduleRepository schedules, AlensiAccess access) {
        this.schedules = schedules;
        this.access = access;
    }

    /** {@code premiums.view} on the policy's unit; 404 when there is none or the caller can't see it. */
    @GetMapping("/api/v1/policies/{policyId}/premium-schedule")
    @Transactional(readOnly = true)
    ResponseEntity<ScheduleView> schedule(@PathVariable UUID policyId) {
        PremiumSchedule schedule = schedules.findByTenantCodeAndPolicyId(CurrentTenant.require(), policyId)
                .filter(found -> access.can(InsurePermissions.PREMIUMS_VIEW, found.getUnitCode()))
                .orElseThrow(() -> ApiProblem.notFound("Premium schedule"));
        String currency = schedule.getCurrency();
        return ResponseEntity.ok(new ScheduleView(policyId, schedule.getFrequency(),
                Money.of(schedule.getTotalAmount(), currency),
                schedule.getInstallments().stream().map(installment -> new InstallmentView(installment.getId(),
                        installment.getSequenceNumber(), installment.getDueDate(),
                        Money.of(installment.getAmount(), currency), Money.of(installment.getPaidAmount(), currency),
                        installment.getStatus(), installment.getPaidAt())).toList()));
    }

    record ScheduleView(UUID policyId, PaymentFrequency frequency, Money totalAmount, List<InstallmentView> installments) {
    }

    record InstallmentView(UUID id, int sequenceNumber, LocalDate dueDate, Money amount, Money paidAmount,
            Installment.Status status, Instant paidAt) {
    }
}
