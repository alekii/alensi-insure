package io.alensi.insure.product;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import io.alensi.insure.platform.web.Money;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Request and response bodies of the product API (openapi.yaml: Products). */
final class ProductViews {

    private ProductViews() {
    }

    record ProductCreate(@NotBlank String code, @NotBlank @Size(max = 255) String name,
            @NotNull LineOfBusiness lineOfBusiness, String currency) {
    }

    record ProductView(UUID id, String tenantCode, String code, String name, LineOfBusiness lineOfBusiness,
            String currency, Product.Status status, Integer currentVersion, List<VersionSummary> versions,
            long version) {
    }

    record VersionSummary(int versionNumber, ProductVersion.Status status, LocalDate effectiveFrom,
            LocalDate effectiveTo) {
    }

    record VersionCreate(Integer copyFromVersion) {
    }

    record Terms(String currency, Integer termMonths, Integer gracePeriodDays) {
    }

    record CoverageConfiguration(List<CoverageSpec> coverages, List<ExclusionSpec> exclusions) {
    }

    record CoverageSpec(String code, String name, String description, Boolean mandatory, List<String> incidentTypes,
            LimitSpec limit, DeductibleSpec deductible, Integer waitingPeriodDays) {
    }

    /** {@code amount} is required for {@code FIXED} and ignored for {@code SUM_INSURED}. */
    record LimitSpec(CoverageDefinition.LimitType type, Money amount, CoverageDefinition.LimitBasis basis) {
    }

    /** {@code value} is an amount for {@code FIXED}, a percentage of the loss for {@code PERCENTAGE}. */
    record DeductibleSpec(CoverageDefinition.DeductibleType type, String value, Money minimum) {
    }

    record ExclusionSpec(String code, String text, String clauseReference, List<String> coverageCodes,
            Condition rule) {
    }

    record EligibilitySpec(String code, Condition rule, EligibilityRule.Outcome outcome, String message) {
    }

    record Rating(Money minimumPremium, List<BaseRateSpec> baseRates, List<FactorSpec> factors) {
    }

    /** Exactly one of {@code basePremium} (annual amount) and {@code ratePercent} (of the sum insured). */
    record BaseRateSpec(String coverageCode, Money basePremium, String ratePercent) {
    }

    /** Applies to {@code coverageCode}, or to every coverage when it is null. */
    record FactorSpec(String attribute, String coverageCode, List<BandSpec> bands) {
    }

    /** {@code from}/{@code to} (inclusive, either may be open) for numbers, or {@code in} for values. */
    record BandSpec(BigDecimal from, BigDecimal to, List<String> in, String multiplier) {
    }

    record Publish(UUID wordingDocumentId, LocalDate effectiveFrom) {
    }

    record VersionView(UUID productId, int versionNumber, ProductVersion.Status status, String currency,
            int termMonths, int gracePeriodDays, LocalDate effectiveFrom, LocalDate effectiveTo, Instant publishedAt,
            UUID wordingDocumentId, CoverageConfiguration coverages, List<EligibilitySpec> eligibility,
            Rating rating, long version) {
    }
}
