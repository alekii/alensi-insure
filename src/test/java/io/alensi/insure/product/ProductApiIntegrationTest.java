package io.alensi.insure.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import io.alensi.insure.ApiIntegrationTest;
import io.alensi.insure.platform.security.CurrentTenant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

/** Products, versions, coverages and publishing (openapi.yaml: Products; authorization.md). */
class ProductApiIntegrationTest extends ApiIntegrationTest {

    static final String T1 = "JUB-KE-NRB-NWS-T1";
    static final Set<String> CONFIGURE = Set.of("products.view", "products.manage", "coverage.manage");

    /** product-manager, appointed by the company admin on the tenant root. */
    static final Staff PRODUCT_MANAGER = new Staff("pm", "JUB", Map.of("company", Map.of("JUB", CONFIGURE),
            "branch", Map.of("JUB-KE-NRB-NWS", CONFIGURE), "team", Map.of(T1, CONFIGURE)));
    /** Holds products.manage, but only on a branch: products are tenant-wide, so that isn't enough. */
    static final Staff BRANCH_CONFIGURER = new Staff("branch.pm", "JUB",
            Map.of("branch", Map.of("JUB-KE-NRB-NWS", CONFIGURE)));
    /** Coverage design only: may change coverages and eligibility, not prices. */
    static final Staff COVERAGE_DESIGNER = new Staff("designer", "JUB",
            Map.of("company", Map.of("JUB", Set.of("products.view", "coverage.manage"))));
    static final Staff AGENT = new Staff("product.agent", "JUB", Map.of("team", Map.of(T1, Set.of("products.view"))));
    static final Staff MADISON_MANAGER = new Staff("mad.pm", "MAD", Map.of("company", Map.of("MAD", CONFIGURE)));

    static final ZoneId NAIROBI = ZoneId.of("Africa/Nairobi");

    @Autowired
    ProductCatalog catalog;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate tx;

    @BeforeEach
    void identity() {
        register(PRODUCT_MANAGER, BRANCH_CONFIGURER, COVERAGE_DESIGNER, AGENT, MADISON_MANAGER);
    }

    @Test
    void aProductIsConfiguredPublishedAndSellable() throws Exception {
        String productId = product("MOTOR");
        ResultActions draft = send(PRODUCT_MANAGER, post("/api/v1/products/{id}/versions", productId), "{}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.versionNumber").value(1))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.currency").value("KES"))
                .andExpect(jsonPath("$.termMonths").value(12));
        String etag = etag(draft);

        etag = etag(send(PRODUCT_MANAGER, put("/api/v1/products/{id}/versions/1/coverages", productId)
                .header("If-Match", etag), MOTOR_COVERAGES)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.coverages.coverages[*].code", contains("OWN_DAMAGE", "THIRD_PARTY_PROPERTY", "WINDSCREEN")))
                .andExpect(jsonPath("$.coverages.coverages[0].limit.type").value("SUM_INSURED"))
                .andExpect(jsonPath("$.coverages.coverages[0].deductible.value").value("2.5"))
                .andExpect(jsonPath("$.coverages.coverages[0].deductible.minimum.amount").value("15000.00"))
                .andExpect(jsonPath("$.coverages.coverages[1].limit.amount.amount").value("3000000.00"))
                .andExpect(jsonPath("$.coverages.exclusions[0].rule.operator").value("EQ")));
        etag = etag(send(PRODUCT_MANAGER, put("/api/v1/products/{id}/versions/1/eligibility", productId)
                .header("If-Match", etag), MOTOR_ELIGIBILITY).andExpect(status().isOk()));
        etag = etag(send(PRODUCT_MANAGER, put("/api/v1/products/{id}/versions/1/rating", productId)
                .header("If-Match", etag), MOTOR_RATING)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rating.minimumPremium.amount").value("37500.00"))
                .andExpect(jsonPath("$.rating.factors[0].attribute").value("customer.age"))
                .andExpect(jsonPath("$.rating.factors[0].bands[0].multiplier").value("1.25"))
                .andExpect(jsonPath("$.rating.factors[1].bands[0].in[0]").value("COMMERCIAL")));

        LocalDate today = LocalDate.now(NAIROBI);
        send(PRODUCT_MANAGER, post("/api/v1/products/{id}/versions/1/publish", productId).header("If-Match", etag), "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.effectiveFrom").value(today.toString()));

        call(AGENT, get("/api/v1/products/{id}", productId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentVersion").value(1));
        call(AGENT, get("/api/v1/products/{id}/versions/1", productId)).andExpect(status().isOk());

        ProductTerms terms = CurrentTenant.call("JUB", () -> catalog.sellable(UUID.fromString(productId), today))
                .orElseThrow();
        assertThat(terms.versionNumber()).isEqualTo(1);
        assertThat(terms.minimumPremium()).isEqualByComparingTo("37500");
        assertThat(terms.coverages()).extracting(ProductTerms.Coverage::code)
                .containsExactly("OWN_DAMAGE", "THIRD_PARTY_PROPERTY", "WINDSCREEN");
        ProductTerms.Coverage ownDamage = terms.coverages().getFirst();
        assertThat(ownDamage.limitIsSumInsured()).isTrue();
        assertThat(ownDamage.ratePercent()).isEqualByComparingTo("4.5");
        assertThat(ownDamage.percentageDeductible()).isTrue();
        assertThat(terms.coverages().get(1).basePremium()).isEqualByComparingTo("7500");
        assertThat(terms.eligibility()).extracting(ProductTerms.Eligibility::code)
                .containsExactly("VEHICLE_AGE", "DRIVER_AGE");
        assertThat(terms.eligibility().get(1).refer()).isTrue();
        assertThat(terms.exclusions().getFirst().rule().attribute()).isEqualTo("claim.driver.licensed");
        assertThat(terms.factors()).hasSize(3);
        // Not sellable before it takes effect, nor in another tenant.
        assertThat(CurrentTenant.call("JUB", () -> catalog.sellable(UUID.fromString(productId), today.minusDays(1))))
                .isEmpty();
        assertThat(CurrentTenant.call("MAD", () -> catalog.sellable(UUID.fromString(productId), today))).isEmpty();
    }

    @Test
    void aPublishedVersionIsImmutableAndANewVersionSupersedesIt() throws Exception {
        String productId = publishedMotorProduct();
        String v1 = etag(call(PRODUCT_MANAGER, get("/api/v1/products/{id}/versions/1", productId)));
        send(PRODUCT_MANAGER, put("/api/v1/products/{id}/versions/1/coverages", productId).header("If-Match", v1),
                MOTOR_COVERAGES)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://alensi.io/problems/draft-only"));

        String v2 = etag(send(PRODUCT_MANAGER, post("/api/v1/products/{id}/versions", productId),
                "{\"copyFromVersion\": 1}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.versionNumber").value(2))
                .andExpect(jsonPath("$.coverages.coverages.length()").value(3))
                .andExpect(jsonPath("$.rating.baseRates.length()").value(3)));
        send(PRODUCT_MANAGER, post("/api/v1/products/{id}/versions", productId), "{}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://alensi.io/problems/draft-exists"));

        // Drafts are for those who configure products.
        call(AGENT, get("/api/v1/products/{id}/versions/2", productId)).andExpect(status().isNotFound());
        call(AGENT, get("/api/v1/products/{id}", productId))
                .andExpect(jsonPath("$.versions[*].versionNumber", not(hasItem(2))));

        LocalDate today = LocalDate.now(NAIROBI);
        LocalDate tomorrow = today.plusDays(1);
        send(PRODUCT_MANAGER, post("/api/v1/products/{id}/versions/2/publish", productId).header("If-Match", v2),
                "{\"effectiveFrom\": \"%s\"}".formatted(tomorrow))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.effectiveFrom").value(tomorrow.toString()));

        call(AGENT, get("/api/v1/products/{id}", productId))
                .andExpect(jsonPath("$.currentVersion").value(1))
                .andExpect(jsonPath("$.versions[0].effectiveTo").value(today.toString()))
                .andExpect(jsonPath("$.versions[1].effectiveTo").doesNotExist());
        UUID id = UUID.fromString(productId);
        assertThat(CurrentTenant.call("JUB", () -> catalog.sellable(id, today)).orElseThrow().versionNumber())
                .isEqualTo(1);
        assertThat(CurrentTenant.call("JUB", () -> catalog.sellable(id, tomorrow)).orElseThrow().versionNumber())
                .isEqualTo(2);

        // The database itself refuses two published versions selling on the same day.
        assertThatThrownBy(() -> CurrentTenant.run("JUB", () -> tx.executeWithoutResult(status -> jdbc.update(
                "update product.product_version set effective_to = null where product_id = ? and version_number = 1",
                id))))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("product_version_no_overlap");
    }

    @Test
    void anIncompleteDraftIsNotPublishable() throws Exception {
        String productId = product("MOTOR");
        String etag = etag(send(PRODUCT_MANAGER, post("/api/v1/products/{id}/versions", productId), "{}"));
        send(PRODUCT_MANAGER, post("/api/v1/products/{id}/versions/1/publish", productId).header("If-Match", etag),
                "{\"effectiveFrom\": \"2020-01-01\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("https://alensi.io/problems/product-not-publishable"))
                .andExpect(jsonPath("$.errors[*].field", hasItems("coverages", "effectiveFrom")));

        etag = etag(send(PRODUCT_MANAGER, put("/api/v1/products/{id}/versions/1/coverages", productId)
                .header("If-Match", etag), """
                {"coverages": [{"code": "THIRD_PARTY_PROPERTY", "name": "Third party property", "mandatory": false,
                  "incidentTypes": ["THIRD_PARTY_PROPERTY"],
                  "limit": {"type": "FIXED", "amount": {"amount": "1000000.00", "currency": "KES"}, "basis": "PER_EVENT"}}]}
                """));
        send(PRODUCT_MANAGER, post("/api/v1/products/{id}/versions/1/publish", productId).header("If-Match", etag), "{}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[*].message", hasItems(
                        "at least one coverage must be mandatory, so every policy covers something",
                        "coverage THIRD_PARTY_PROPERTY has no base rate")));
    }

    @Test
    void invalidConfigurationIsRefusedWithEveryProblemListed() throws Exception {
        String productId = product("MOTOR");
        String etag = etag(send(PRODUCT_MANAGER, post("/api/v1/products/{id}/versions", productId), "{}"));
        send(PRODUCT_MANAGER, put("/api/v1/products/{id}/versions/1/coverages", productId).header("If-Match", etag), """
                {"coverages": [
                   {"code": "own damage", "name": "Own damage", "incidentTypes": ["INPATIENT"],
                    "limit": {"type": "FIXED", "basis": "PER_EVENT"}},
                   {"code": "TPP", "name": "TPP", "incidentTypes": ["COLLISION"],
                    "limit": {"type": "FIXED", "amount": {"amount": "100.00", "currency": "UGX"}, "basis": "PER_EVENT"},
                    "deductible": {"type": "PERCENTAGE", "value": "120"}}],
                 "exclusions": [
                   {"code": "X", "text": "x"},
                   {"code": "BAD_RULE", "text": "Bad", "coverageCodes": ["NOPE"],
                    "rule": {"attribute": "vehicle.age", "operator": "IN", "value": 3}}]}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", hasItems("coverages[0].code", "coverages[0].incidentTypes",
                        "coverages[0].limit.amount", "coverages[1].limit.amount.currency",
                        "coverages[1].deductible.value", "exclusions[0].code", "exclusions[1].coverageCodes",
                        "exclusions[1].rule.attribute", "exclusions[1].rule.value")));

        send(PRODUCT_MANAGER, put("/api/v1/products/{id}/versions/1/rating", productId).header("If-Match", etag), """
                {"baseRates": [{"coverageCode": "GHOST", "ratePercent": "4.5", "basePremium": {"amount": "1.00", "currency": "KES"}}],
                 "factors": [{"attribute": "customer.age", "bands": [{"from": 30, "to": 20, "multiplier": "1.1"},
                                                                     {"from": 1, "in": ["A"], "multiplier": "0"}]}]}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", hasItems("baseRates[0].coverageCode", "baseRates[0]",
                        "factors[0].bands[0]", "factors[0].bands[1]", "factors[0].bands[1].multiplier")));
    }

    @Test
    void configuringProductsNeedsTheTenantRoot() throws Exception {
        String create = "{\"code\": \"%s\", \"name\": \"Motor\", \"lineOfBusiness\": \"MOTOR\"}";
        send(AGENT, post("/api/v1/products"), create.formatted(code())).andExpect(status().isForbidden());
        send(BRANCH_CONFIGURER, post("/api/v1/products"), create.formatted(code())).andExpect(status().isForbidden());

        String productId = product("MOTOR");
        String etag = etag(send(PRODUCT_MANAGER, post("/api/v1/products/{id}/versions", productId), "{}"));
        etag = etag(send(COVERAGE_DESIGNER, put("/api/v1/products/{id}/versions/1/coverages", productId)
                .header("If-Match", etag), MOTOR_COVERAGES).andExpect(status().isOk()));
        send(COVERAGE_DESIGNER, put("/api/v1/products/{id}/versions/1/rating", productId).header("If-Match", etag),
                MOTOR_RATING).andExpect(status().isForbidden());
        send(PRODUCT_MANAGER, put("/api/v1/products/{id}/versions/1/rating", productId).header("If-Match", "\"0\""),
                MOTOR_RATING).andExpect(status().isPreconditionFailed());

        call(MADISON_MANAGER, get("/api/v1/products/{id}", productId)).andExpect(status().isNotFound());
        call(MADISON_MANAGER, get("/api/v1/products"))
                .andExpect(jsonPath("$[*].id", not(hasItem(productId))));
        send(PRODUCT_MANAGER, post("/api/v1/products"),
                "{\"code\": \"%s\", \"name\": \"Again\", \"lineOfBusiness\": \"MOTOR\"}".formatted(
                        field(call(PRODUCT_MANAGER, get("/api/v1/products/{id}", productId)), "$.code")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://alensi.io/problems/duplicate-product"));
    }

    @Test
    void withdrawingStopsNewSales() throws Exception {
        String productId = publishedMotorProduct();
        String etag = etag(call(PRODUCT_MANAGER, get("/api/v1/products/{id}/versions/1", productId)));
        call(PRODUCT_MANAGER, post("/api/v1/products/{id}/versions/1/withdraw", productId).header("If-Match", etag))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WITHDRAWN"));
        call(AGENT, get("/api/v1/products/{id}", productId)).andExpect(jsonPath("$.currentVersion").doesNotExist());
        assertThat(CurrentTenant.call("JUB",
                () -> catalog.sellable(UUID.fromString(productId), LocalDate.now(NAIROBI)))).isEmpty();

        String draft = etag(send(PRODUCT_MANAGER, post("/api/v1/products/{id}/versions", productId), "{}"));
        call(PRODUCT_MANAGER, post("/api/v1/products/{id}/versions/2/withdraw", productId).header("If-Match", draft))
                .andExpect(status().isConflict());
    }

    @Test
    void changesAreAuditedOnTheTenantRoot() throws Exception {
        String productId = publishedMotorProduct();
        Staff auditor = new Staff("product.auditor", "JUB", Map.of("company", Map.of("JUB", Set.of("audit.view"))));
        register(auditor);
        call(auditor, get("/api/v1/audit-events").param("subjectId", productId))
                .andExpect(jsonPath("$.content[*].action", hasItems("PRODUCT_CREATED", "PRODUCT_VERSION_CREATED",
                        "PRODUCT_COVERAGES_REPLACED", "PRODUCT_RATING_REPLACED", "PRODUCT_VERSION_PUBLISHED")))
                .andExpect(jsonPath("$.content[0].unitCode").value("JUB"));
        // A branch-level auditor doesn't see tenant-wide product events.
        Staff branchAuditor = new Staff("branch.auditor", "JUB",
                Map.of("branch", Map.of("JUB-KE-NRB-NWS", Set.of("audit.view"))));
        register(branchAuditor);
        call(branchAuditor, get("/api/v1/audit-events").param("subjectId", productId))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    // ------------------------------------------------------------------ fixtures

    private String product(String lineOfBusiness) throws Exception {
        return createdId(send(PRODUCT_MANAGER, post("/api/v1/products"),
                "{\"code\": \"%s\", \"name\": \"Motor comprehensive\", \"lineOfBusiness\": \"%s\"}"
                        .formatted(code(), lineOfBusiness))
                .andExpect(header().exists("ETag")));
    }

    private String publishedMotorProduct() throws Exception {
        String productId = product("MOTOR");
        String etag = etag(send(PRODUCT_MANAGER, post("/api/v1/products/{id}/versions", productId), "{}"));
        etag = etag(send(PRODUCT_MANAGER, put("/api/v1/products/{id}/versions/1/coverages", productId)
                .header("If-Match", etag), MOTOR_COVERAGES).andExpect(status().isOk()));
        etag = etag(send(PRODUCT_MANAGER, put("/api/v1/products/{id}/versions/1/rating", productId)
                .header("If-Match", etag), MOTOR_RATING).andExpect(status().isOk()));
        send(PRODUCT_MANAGER, post("/api/v1/products/{id}/versions/1/publish", productId).header("If-Match", etag), "{}")
                .andExpect(status().isOk());
        return productId;
    }

    private static String code() {
        return "MOTOR-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    static final String MOTOR_COVERAGES = """
            {"coverages": [
               {"code": "OWN_DAMAGE", "name": "Own damage", "mandatory": true,
                "incidentTypes": ["COLLISION", "FIRE", "THEFT", "FLOOD"],
                "limit": {"type": "SUM_INSURED", "basis": "PER_EVENT"},
                "deductible": {"type": "PERCENTAGE", "value": "2.5", "minimum": {"amount": "15000.00", "currency": "KES"}}},
               {"code": "THIRD_PARTY_PROPERTY", "name": "Third party property damage", "mandatory": true,
                "incidentTypes": ["THIRD_PARTY_PROPERTY"],
                "limit": {"type": "FIXED", "amount": {"amount": "3000000.00", "currency": "KES"}, "basis": "PER_EVENT"}},
               {"code": "WINDSCREEN", "name": "Windscreen", "mandatory": false, "incidentTypes": ["WINDSCREEN"],
                "limit": {"type": "FIXED", "amount": {"amount": "50000.00", "currency": "KES"}, "basis": "AGGREGATE"},
                "waitingPeriodDays": 30}],
             "exclusions": [
               {"code": "UNLICENSED_DRIVER", "text": "Driver without a valid licence", "clauseReference": "4.2(b)",
                "rule": {"attribute": "claim.driver.licensed", "operator": "EQ", "value": false}},
               {"code": "WEAR_AND_TEAR", "text": "Wear and tear, mechanical breakdown", "clauseReference": "4.2(f)",
                "coverageCodes": ["OWN_DAMAGE"]}]}
            """;

    static final String MOTOR_ELIGIBILITY = """
            [{"code": "VEHICLE_AGE", "rule": {"attribute": "item.vehicle.age", "operator": "LTE", "value": 15},
              "outcome": "DECLINE", "message": "Vehicles older than 15 years can't be insured comprehensively"},
             {"code": "DRIVER_AGE", "rule": {"attribute": "customer.age", "operator": "GTE", "value": 21},
              "outcome": "REFER", "message": "Drivers under 21 need underwriter review"}]
            """;

    static final String MOTOR_RATING = """
            {"minimumPremium": {"amount": "37500.00", "currency": "KES"},
             "baseRates": [{"coverageCode": "OWN_DAMAGE", "ratePercent": "4.5"},
                           {"coverageCode": "THIRD_PARTY_PROPERTY", "basePremium": {"amount": "7500.00", "currency": "KES"}},
                           {"coverageCode": "WINDSCREEN", "basePremium": {"amount": "3000.00", "currency": "KES"}}],
             "factors": [{"attribute": "customer.age", "bands": [{"from": 18, "to": 24, "multiplier": "1.25"},
                                                                 {"from": 25, "multiplier": "1"}]},
                         {"attribute": "item.vehicle.use", "coverageCode": "OWN_DAMAGE",
                          "bands": [{"in": ["COMMERCIAL"], "multiplier": "1.3"}]}]}
            """;
}
