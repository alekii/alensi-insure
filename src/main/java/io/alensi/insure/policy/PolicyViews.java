package io.alensi.insure.policy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.alensi.insure.platform.web.Money;
import io.alensi.insure.policy.QuoteEngine.EligibilityResult;
import io.alensi.insure.policy.QuoteEngine.RatingBreakdown;
import io.alensi.insure.premium.PaymentFrequency;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Request and response bodies of the policy API (openapi.yaml: Policies). */
final class PolicyViews {

    private PolicyViews() {
    }

    /** {@code coverages} are the optional ones wanted; mandatory coverages are always included. */
    record QuoteRequest(@NotNull UUID customerId, String unitCode, @NotBlank String productCode,
            @NotNull LocalDate effectiveDate, @NotNull PaymentFrequency paymentFrequency,
            @NotEmpty @Size(max = 1, message = "v1 insures one item per policy") List<@Valid ItemSpec> insuredItems,
            List<String> coverages) {
    }

    record ItemSpec(@NotNull ItemType itemType, @NotBlank @Size(max = 255) String description, Money sumInsured,
            Map<String, Object> attributes) {
    }

    record PolicySummary(UUID id, String tenantCode, String unitCode, String policyNumber, String productCode,
            Policy.Status status, LocalDate effectiveDate, LocalDate expiryDate, Money totalPremium) {
    }

    record PolicyView(UUID id, String tenantCode, String unitCode, String policyNumber, String productCode,
            int productVersion, Policy.Status status, LocalDate effectiveDate, LocalDate expiryDate,
            Money totalPremium, Money totalSumInsured, PaymentFrequency paymentFrequency, CustomerRef customer,
            Instant quoteExpiresAt, Instant acceptedAt, Instant issuedAt, List<ItemView> insuredItems,
            List<CoverageView> coverages, RatingBreakdown rating, List<EligibilityResult> eligibility, long version) {
    }

    /** The customer, or only {@code id} and {@code restricted: true} when the caller can't view them. */
    record CustomerRef(UUID id, String customerNumber, String displayName, Boolean restricted) {
    }

    record ItemView(UUID id, ItemType itemType, String description, Money sumInsured, Map<String, Object> attributes) {
    }

    record CoverageView(String code, String name, Money limit, String limitBasis, Deductible deductible,
            int waitingPeriodDays, Money premium, Money consumed, Money reserved, Money remaining) {
    }

    record Deductible(String type, String value, Money minimum) {
    }
}
