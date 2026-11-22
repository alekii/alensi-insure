package io.alensi.insure.premium;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Published, in the transaction that applied the payment, when an installment becomes fully paid.
 * Installment 1 being paid is what issues a policy.
 */
public record InstallmentPaid(UUID policyId, UUID installmentId, int sequenceNumber, LocalDate paidOn) {
}
