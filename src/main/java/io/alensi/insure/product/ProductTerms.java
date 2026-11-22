package io.alensi.insure.product;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

/**
 * The immutable terms of one published product version, as policies are quoted and claims checked
 * against them. Amounts are in {@code currency}.
 */
public record ProductTerms(
        UUID productId,
        String productCode,
        LineOfBusiness lineOfBusiness,
        UUID versionId,
        int versionNumber,
        String currency,
        int termMonths,
        int gracePeriodDays,
        BigDecimal minimumPremium,
        List<Coverage> coverages,
        List<Exclusion> exclusions,
        List<Eligibility> eligibility,
        List<Factor> factors) {

    /**
     * {@code limitAmount} is null when the limit is the insured item's sum insured. Exactly one of
     * {@code basePremium} and {@code ratePercent} (of the sum insured) is set.
     */
    public record Coverage(UUID id, String code, String name, boolean mandatory, List<String> incidentTypes,
            boolean limitIsSumInsured, BigDecimal limitAmount, boolean aggregateLimit, boolean percentageDeductible,
            BigDecimal deductibleValue, BigDecimal deductibleMinimum, int waitingPeriodDays, BigDecimal basePremium,
            BigDecimal ratePercent) {
    }

    /** Applies to {@code coverageCodes}, or every coverage when empty; {@code rule} is null when a person must judge. */
    public record Exclusion(String code, String description, String clauseReference, List<String> coverageCodes,
            Condition rule) {
    }

    /** {@code refer} false means a failed rule declines the quote; true refers it to an underwriter. */
    public record Eligibility(String code, Condition rule, boolean refer, String message) {
    }

    /** One band: {@code {"from": 18, "to": 25}} or {@code {"in": [...]}}; applies to all coverages when {@code coverageCode} is null. */
    public record Factor(String attribute, String coverageCode, JsonNode band, BigDecimal multiplier) {

        /** Whether {@code fact} falls in this band; a missing or non-comparable fact matches no band. */
        public boolean matches(Object fact) {
            if (fact == null) {
                return false;
            }
            if (band.has("in")) {
                for (JsonNode value : band.get("in")) {
                    if (value.asString().equals(fact.toString())) {
                        return true;
                    }
                }
                return false;
            }
            if (!(fact instanceof BigDecimal number)) {
                return false;
            }
            return (!band.has("from") || number.compareTo(band.get("from").decimalValue()) >= 0)
                    && (!band.has("to") || number.compareTo(band.get("to").decimalValue()) <= 0);
        }

        /** {@code 18–24}, {@code 25+} or {@code COMMERCIAL}, for explaining a premium. */
        public String describeBand() {
            if (band.has("in")) {
                return band.get("in").valueStream().map(JsonNode::asString).reduce((a, b) -> a + ", " + b).orElse("");
            }
            String from = band.has("from") ? band.get("from").asString() : null;
            String to = band.has("to") ? band.get("to").asString() : null;
            return from == null ? "up to " + to : to == null ? from + "+" : from + "–" + to;
        }
    }
}
