package io.alensi.insure.policy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.Period;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import io.alensi.insure.customer.CustomerDirectory.CustomerSummary;
import io.alensi.insure.platform.web.Violations;
import io.alensi.insure.premium.PaymentFrequency;
import io.alensi.insure.product.LineOfBusiness;
import io.alensi.insure.product.ProductTerms;

/**
 * Eligibility and rating, deterministic and explained (policy-lifecycle.md, "Eligibility and
 * rating"). Pure: everything it needs is passed in, so the same inputs always price the same.
 *
 * <ol>
 *   <li>Facts from the customer, the insured item and the policy.</li>
 *   <li>Every eligibility rule: any DECLINE failure declines, otherwise any REFER failure refers.
 *       A rule whose fact is missing counts as failed, so missing data never passes silently.</li>
 *   <li>Each coverage: base premium (fixed, or rate percent of the sum insured) × the multiplier of
 *       the first matching band of every factor that applies to it.</li>
 *   <li>The total is at least the version's minimum premium.</li>
 * </ol>
 */
final class QuoteEngine {

    private static final Map<LineOfBusiness, ItemType> ITEM_TYPES = Map.of(LineOfBusiness.MOTOR, ItemType.VEHICLE,
            LineOfBusiness.PROPERTY, ItemType.PROPERTY, LineOfBusiness.HEALTH, ItemType.PERSON,
            LineOfBusiness.LIFE, ItemType.PERSON, LineOfBusiness.TRAVEL, ItemType.TRIP);
    private static final Pattern ISO_DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private QuoteEngine() {
    }

    enum Outcome { QUOTED, REFERRED, DECLINED }

    record Item(ItemType type, String description, BigDecimal sumInsured, Map<String, Object> attributes) {
    }

    record Request(ProductTerms terms, CustomerSummary customer, Item item, List<String> coverageCodes,
            LocalDate effectiveDate, PaymentFrequency frequency) {
    }

    record EligibilityResult(String ruleCode, String outcome, String message) {
    }

    record PricedCoverage(ProductTerms.Coverage definition, BigDecimal limit, BigDecimal premium) {
    }

    record AppliedFactor(String attribute, String band, String multiplier) {
    }

    record CoverageRating(String code, String name, String basis, String base, List<AppliedFactor> factors,
            String premium) {
    }

    record RatingBreakdown(List<CoverageRating> coverages, String subtotal, String minimumPremium,
            boolean minimumApplied, String total) {
    }

    record Quote(Outcome outcome, List<EligibilityResult> eligibility, List<PricedCoverage> coverages,
            RatingBreakdown rating, BigDecimal total) {
    }

    /** Fails with 422 {@code quote-not-possible} (every problem listed) when the request can't be priced. */
    static Quote quote(Request request) {
        ProductTerms terms = request.terms();
        Violations problems = new Violations();
        ItemType expected = ITEM_TYPES.get(terms.lineOfBusiness());
        if (request.item().type() != expected) {
            problems.add("insuredItems[0].itemType", "%s products insure a %s".formatted(terms.lineOfBusiness(), expected));
        }

        Map<String, ProductTerms.Coverage> byCode = new LinkedHashMap<>();
        terms.coverages().forEach(coverage -> byCode.put(coverage.code(), coverage));
        Set<String> selected = new LinkedHashSet<>();
        terms.coverages().stream().filter(ProductTerms.Coverage::mandatory).forEach(c -> selected.add(c.code()));
        for (String code : request.coverageCodes()) {
            if (byCode.containsKey(code)) {
                selected.add(code);
            } else {
                problems.add("coverages", "%s isn't a coverage of %s v%d; choose from %s"
                        .formatted(code, terms.productCode(), terms.versionNumber(), byCode.keySet()));
            }
        }
        BigDecimal sumInsured = request.item().sumInsured();
        for (String code : selected) {
            ProductTerms.Coverage coverage = byCode.get(code);
            if (sumInsured == null && (coverage.limitIsSumInsured() || coverage.ratePercent() != null)) {
                problems.add("insuredItems[0].sumInsured", "is needed: %s is limited or priced by the sum insured"
                        .formatted(code));
            }
        }
        problems.throwIfAny(org.springframework.http.HttpStatus.UNPROCESSABLE_CONTENT, "quote-not-possible",
                "Quote not possible", "The product can't price this request; see errors.");

        Map<String, Object> facts = facts(request);
        List<EligibilityResult> failures = new ArrayList<>();
        boolean decline = false;
        boolean refer = false;
        for (ProductTerms.Eligibility rule : terms.eligibility()) {
            Optional<Boolean> holds = rule.rule().test(facts);
            if (holds.orElse(false)) {
                continue;
            }
            String message = holds.isPresent() ? rule.message()
                    : rule.message() + " (no usable value for " + rule.rule().attribute() + ")";
            failures.add(new EligibilityResult(rule.code(), rule.refer() ? "REFER" : "DECLINE", message));
            decline |= !rule.refer();
            refer |= rule.refer();
        }

        List<PricedCoverage> priced = new ArrayList<>();
        List<CoverageRating> ratings = new ArrayList<>();
        BigDecimal subtotal = BigDecimal.ZERO;
        for (String code : selected) {
            ProductTerms.Coverage coverage = byCode.get(code);
            BigDecimal base;
            String basis;
            if (coverage.ratePercent() != null) {
                base = money(sumInsured.multiply(coverage.ratePercent()).divide(HUNDRED));
                basis = "%s%% of sum insured %s".formatted(coverage.ratePercent().stripTrailingZeros().toPlainString(),
                        money(sumInsured).toPlainString());
            } else {
                base = money(coverage.basePremium());
                basis = "fixed";
            }
            BigDecimal premium = base;
            List<AppliedFactor> applied = new ArrayList<>();
            Set<List<String>> factorsSeen = new LinkedHashSet<>();
            for (ProductTerms.Factor band : terms.factors()) {
                if (band.coverageCode() != null && !band.coverageCode().equals(code)) {
                    continue;
                }
                List<String> factor = java.util.Arrays.asList(band.attribute(), band.coverageCode());
                if (factorsSeen.contains(factor) || !band.matches(facts.get(band.attribute()))) {
                    continue;
                }
                factorsSeen.add(factor);
                premium = premium.multiply(band.multiplier());
                applied.add(new AppliedFactor(band.attribute(), band.describeBand(),
                        band.multiplier().stripTrailingZeros().toPlainString()));
            }
            premium = money(premium);
            BigDecimal limit = coverage.limitIsSumInsured() ? money(sumInsured) : coverage.limitAmount();
            priced.add(new PricedCoverage(coverage, limit, premium));
            ratings.add(new CoverageRating(code, coverage.name(), basis, base.toPlainString(), applied,
                    premium.toPlainString()));
            subtotal = subtotal.add(premium);
        }
        boolean minimumApplied = subtotal.compareTo(terms.minimumPremium()) < 0;
        BigDecimal total = money(minimumApplied ? terms.minimumPremium() : subtotal);
        RatingBreakdown breakdown = new RatingBreakdown(ratings, subtotal.toPlainString(),
                money(terms.minimumPremium()).toPlainString(), minimumApplied, total.toPlainString());
        Outcome outcome = decline ? Outcome.DECLINED : refer ? Outcome.REFERRED : Outcome.QUOTED;
        return new Quote(outcome, failures, priced, breakdown, total);
    }

    /** Fact paths as rules and factors name them, e.g. {@code customer.age}, {@code item.vehicle.use}. */
    static Map<String, Object> facts(Request request) {
        Map<String, Object> facts = new HashMap<>();
        CustomerSummary customer = request.customer();
        facts.put("customer.type", customer.individual() ? "INDIVIDUAL" : "ORGANIZATION");
        if (customer.dateOfBirth() != null) {
            facts.put("customer.age", BigDecimal.valueOf(
                    Period.between(customer.dateOfBirth(), request.effectiveDate()).getYears()));
        }
        Item item = request.item();
        String prefix = "item." + item.type().name().toLowerCase();
        facts.put("item.type", item.type().name());
        if (item.sumInsured() != null) {
            facts.put("item.sumInsured", item.sumInsured());
        }
        item.attributes().forEach((name, value) -> {
            Object fact = fact(value);
            if (fact != null) {
                facts.put(prefix + "." + name, fact);
            }
        });
        if (item.type() == ItemType.VEHICLE && facts.get("item.vehicle.yearOfManufacture") instanceof BigDecimal year) {
            facts.put("item.vehicle.age",
                    BigDecimal.valueOf(request.effectiveDate().getYear()).subtract(year));
        }
        facts.put("policy.paymentFrequency", request.frequency().name());
        facts.put("policy.termMonths", BigDecimal.valueOf(request.terms().termMonths()));
        facts.put("policy.effectiveDate", request.effectiveDate());
        return facts;
    }

    private static Object fact(Object value) {
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        if (value instanceof Boolean) {
            return value;
        }
        if (value instanceof String text) {
            if (ISO_DATE.matcher(text).matches()) {
                try {
                    return LocalDate.parse(text);
                } catch (DateTimeParseException ex) {
                    return text;
                }
            }
            return text;
        }
        return null;
    }

    private static BigDecimal money(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP);
    }
}
