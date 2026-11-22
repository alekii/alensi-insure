package io.alensi.insure.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.alensi.insure.customer.CustomerDirectory.CustomerSummary;
import io.alensi.insure.platform.web.ApiProblem;
import io.alensi.insure.policy.QuoteEngine.Outcome;
import io.alensi.insure.premium.PaymentFrequency;
import io.alensi.insure.product.Condition;
import io.alensi.insure.product.LineOfBusiness;
import io.alensi.insure.product.ProductTerms;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Rating and eligibility arithmetic, with a motor product like the ones insurers sell in Kenya. */
class QuoteEngineTest {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final LocalDate START = LocalDate.of(2026, 12, 1);

    static final ProductTerms MOTOR = new ProductTerms(UUID.randomUUID(), "MOTOR-COMP", LineOfBusiness.MOTOR,
            UUID.randomUUID(), 1, "KES", 12, 30, new BigDecimal("37500"),
            List.of(
                    coverage("OWN_DAMAGE", true, true, null, null, "4.5"),
                    coverage("THIRD_PARTY_PROPERTY", true, false, "3000000", "7500", null),
                    coverage("WINDSCREEN", false, false, "50000", "3000", null)),
            List.of(),
            List.of(
                    new ProductTerms.Eligibility("VEHICLE_AGE", condition("item.vehicle.age", "LTE", "15"), false,
                            "Vehicles older than 15 years can't be insured"),
                    new ProductTerms.Eligibility("DRIVER_AGE", condition("customer.age", "GTE", "21"), true,
                            "Drivers under 21 need underwriter review")),
            List.of(
                    factor("customer.age", null, "{\"from\": 18, \"to\": 24}", "1.25"),
                    factor("customer.age", null, "{\"from\": 25}", "1"),
                    factor("item.vehicle.use", "OWN_DAMAGE", "{\"in\": [\"COMMERCIAL\"]}", "1.3")));

    @Test
    void premiumIsBaseTimesMatchingFactorsAcrossCoverages() {
        var quote = QuoteEngine.quote(request(person(1990), vehicle(2017, "PRIVATE", "1450000"),
                List.of("WINDSCREEN")));
        assertThat(quote.outcome()).isEqualTo(Outcome.QUOTED);
        assertThat(quote.coverages()).extracting(c -> c.definition().code())
                .containsExactly("OWN_DAMAGE", "THIRD_PARTY_PROPERTY", "WINDSCREEN");
        // 4.5% of 1,450,000 = 65,250; age 36 matches the 25+ band (×1); private use matches nothing.
        assertThat(quote.coverages().get(0).premium()).isEqualByComparingTo("65250.00");
        assertThat(quote.coverages().get(0).limit()).isEqualByComparingTo("1450000.00");
        assertThat(quote.coverages().get(1).premium()).isEqualByComparingTo("7500.00");
        assertThat(quote.coverages().get(1).limit()).isEqualByComparingTo("3000000");
        assertThat(quote.total()).isEqualByComparingTo("75750.00");
        assertThat(quote.rating().coverages().get(0).basis()).isEqualTo("4.5% of sum insured 1450000.00");
        assertThat(quote.rating().minimumApplied()).isFalse();
    }

    @Test
    void factorsCompoundAndOnlyApplyToTheirCoverage() {
        var quote = QuoteEngine.quote(request(person(START.getYear() - 22), vehicle(2020, "COMMERCIAL", "1000000"),
                List.of()));
        // Own damage: 45,000 × 1.25 (age 22) × 1.3 (commercial) = 73,125; TPP: 7,500 × 1.25 = 9,375.
        assertThat(quote.coverages().get(0).premium()).isEqualByComparingTo("73125.00");
        assertThat(quote.coverages().get(1).premium()).isEqualByComparingTo("9375.00");
        assertThat(quote.rating().coverages().get(0).factors())
                .extracting(QuoteEngine.AppliedFactor::band).containsExactly("18–24", "COMMERCIAL");
        assertThat(quote.rating().coverages().get(1).factors()).hasSize(1);
    }

    @Test
    void theMinimumPremiumIsAFloor() {
        var quote = QuoteEngine.quote(request(person(1980), vehicle(2022, "PRIVATE", "400000"), List.of()));
        // 18,000 + 7,500 = 25,500 < 37,500.
        assertThat(quote.rating().subtotal()).isEqualTo("25500.00");
        assertThat(quote.rating().minimumApplied()).isTrue();
        assertThat(quote.total()).isEqualByComparingTo("37500.00");
    }

    @Test
    void aDeclineRuleBeatsAReferRule() {
        var young = QuoteEngine.quote(request(person(START.getYear() - 19), vehicle(2018, "PRIVATE", "800000"),
                List.of()));
        assertThat(young.outcome()).isEqualTo(Outcome.REFERRED);
        assertThat(young.eligibility()).extracting(QuoteEngine.EligibilityResult::ruleCode).containsExactly("DRIVER_AGE");

        var youngAndOld = QuoteEngine.quote(request(person(START.getYear() - 19), vehicle(2005, "PRIVATE", "300000"),
                List.of()));
        assertThat(youngAndOld.outcome()).isEqualTo(Outcome.DECLINED);
        assertThat(youngAndOld.eligibility()).hasSize(2);
    }

    @Test
    void aMissingFactFailsItsRuleInsteadOfPassing() {
        // An organisation has no age, so DRIVER_AGE can't be decided: it is referred, not waved through.
        CustomerSummary company = new CustomerSummary(UUID.randomUUID(), "CUS-1", "Acme Ltd", "U", true, false, null);
        var quote = QuoteEngine.quote(request(company, vehicle(2019, "PRIVATE", "900000"), List.of()));
        assertThat(quote.outcome()).isEqualTo(Outcome.REFERRED);
        assertThat(quote.eligibility().getFirst().message()).contains("no usable value for customer.age");
    }

    @Test
    void unknownCoveragesAndAMissingSumInsuredAreRefused() {
        assertThatThrownBy(() -> QuoteEngine.quote(request(person(1990), vehicle(2017, "PRIVATE", null),
                List.of("TELEPORTATION"))))
                .isInstanceOf(ApiProblem.class)
                .hasMessageContaining("can't price");
    }

    private static QuoteEngine.Request request(CustomerSummary customer, QuoteEngine.Item item, List<String> optional) {
        return new QuoteEngine.Request(MOTOR, customer, item, optional, START, PaymentFrequency.MONTHLY);
    }

    private static CustomerSummary person(int birthYear) {
        return new CustomerSummary(UUID.randomUUID(), "CUS-1", "Wanjiru Kamau", "U", true, true,
                LocalDate.of(birthYear, 1, 15));
    }

    private static QuoteEngine.Item vehicle(int year, String use, String sumInsured) {
        return new QuoteEngine.Item(ItemType.VEHICLE, "Toyota Axio", sumInsured == null ? null : new BigDecimal(sumInsured),
                Map.of("yearOfManufacture", year, "use", use));
    }

    private static ProductTerms.Coverage coverage(String code, boolean mandatory, boolean sumInsured, String limit,
            String basePremium, String ratePercent) {
        return new ProductTerms.Coverage(UUID.randomUUID(), code, code, mandatory, List.of("COLLISION"), sumInsured,
                limit == null ? null : new BigDecimal(limit), false, false, BigDecimal.ZERO, null, 0,
                basePremium == null ? null : new BigDecimal(basePremium),
                ratePercent == null ? null : new BigDecimal(ratePercent));
    }

    private static Condition condition(String attribute, String operator, String value) {
        return new Condition(attribute, Condition.Operator.valueOf(operator), JSON.readTree(value));
    }

    private static ProductTerms.Factor factor(String attribute, String coverage, String band, String multiplier) {
        return new ProductTerms.Factor(attribute, coverage, JSON.readTree(band), new BigDecimal(multiplier));
    }
}
