package io.alensi.insure.premium;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class PremiumScheduleTest {

    static final LocalDate ACCEPTED = LocalDate.of(2026, 11, 22);
    static final LocalDate START = LocalDate.of(2026, 12, 1);

    @Test
    void theRoundingDifferenceGoesOnTheFirstInstallment() {
        PremiumSchedule schedule = schedule(PaymentFrequency.MONTHLY, "100000.00", 12);
        // 100,000 / 12 = 8,333.33 rounded down; the missing 0.04 is paid with the first installment.
        assertThat(schedule.getInstallments()).hasSize(12);
        assertThat(schedule.getInstallments().getFirst().getAmount()).isEqualByComparingTo("8333.37");
        assertThat(schedule.getInstallments().get(1).getAmount()).isEqualByComparingTo("8333.33");
        assertThat(schedule.getInstallments().stream().map(Installment::getAmount).reduce(BigDecimal.ZERO,
                BigDecimal::add)).isEqualByComparingTo("100000.00");
    }

    @Test
    void theFirstIsDueOnAcceptanceAndTheRestOneIntervalApartFromTheStart() {
        PremiumSchedule schedule = schedule(PaymentFrequency.QUARTERLY, "40000.00", 12);
        assertThat(schedule.getInstallments()).extracting(Installment::getDueDate)
                .containsExactly(ACCEPTED, START.plusMonths(3), START.plusMonths(6), START.plusMonths(9));
        assertThat(schedule.getInstallments()).extracting(Installment::getStatus).containsExactly(
                Installment.Status.DUE, Installment.Status.SCHEDULED, Installment.Status.SCHEDULED,
                Installment.Status.SCHEDULED);
    }

    @Test
    void aShortTermStillGetsAnInstallmentForItsPartialInterval() {
        assertThat(PaymentFrequency.QUARTERLY.installmentsFor(7)).isEqualTo(3);
        assertThat(PaymentFrequency.ANNUAL.installmentsFor(6)).isEqualTo(1);
    }

    private static PremiumSchedule schedule(PaymentFrequency frequency, String total, int termMonths) {
        return new PremiumSchedule(new PremiumSchedules.NewSchedule(UUID.randomUUID(), "U", frequency,
                new BigDecimal(total), "KES", ACCEPTED, START, termMonths));
    }
}
