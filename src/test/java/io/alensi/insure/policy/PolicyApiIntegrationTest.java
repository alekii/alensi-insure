package io.alensi.insure.policy;

import static org.assertj.core.api.Assertions.assertThat;
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

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import io.alensi.insure.ApiIntegrationTest;
import io.alensi.insure.platform.security.CurrentTenant;
import io.alensi.insure.premium.PaymentEvents;
import io.alensi.insure.premium.PaymentEvents.CollectionSucceeded;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

/** Quote → accept → issue (policy-lifecycle.md), with the default identity roles. */
class PolicyApiIntegrationTest extends ApiIntegrationTest {

    static final String T1 = "JUB-KE-NRB-NWS-T1";
    static final String WLD = "JUB-KE-NRB-WLD";
    static final Set<String> SELLING = Set.of("customers.view", "customers.create", "products.view", "policies.view",
            "policies.quote", "premiums.view");
    static final Set<String> CONFIGURE = Set.of("products.view", "products.manage", "coverage.manage");

    static final Staff AGENT = new Staff("policy.agent", "JUB", Map.of("team", Map.of(T1, SELLING)));
    static final Staff TEAM_LEAD = new Staff("policy.lead", "JUB", Map.of("team", Map.of(T1, plus(SELLING,
            "policies.issue"))));
    static final Staff PRODUCT_MANAGER = new Staff("policy.pm", "JUB", Map.of("company", Map.of("JUB", CONFIGURE)));
    static final Staff WESTLANDS = new Staff("policy.wld", "JUB", Map.of("branch", Map.of(WLD, SELLING)));
    /** Sees policies but not customers: the embedded customer is restricted. */
    static final Staff UNDERWRITER = new Staff("policy.uw", "JUB", Map.of("team", Map.of(T1, Set.of("policies.view"))));
    static final Staff MADISON = new Staff("policy.mad", "MAD", Map.of("team", Map.of("MAD-KE-NRB-T1", SELLING)));

    static final ZoneId NAIROBI = ZoneId.of("Africa/Nairobi");
    static String productCode;

    @Autowired
    PaymentEvents payments;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate tx;

    @BeforeEach
    void setUp() throws Exception {
        register(AGENT, TEAM_LEAD, PRODUCT_MANAGER, WESTLANDS, UNDERWRITER, MADISON);
        if (productCode == null) {
            productCode = publishMotorProduct();
        }
    }

    @Test
    void aQuoteIsPricedAndExplained() throws Exception {
        String customer = customer("1990-04-12");
        quote(AGENT, customer, today(), "PRIVATE", 2017, "[\"WINDSCREEN\"]")
                .andExpect(status().isCreated())
                .andExpect(header().string("ETag", "\"0\""))
                .andExpect(jsonPath("$.status").value("QUOTED"))
                .andExpect(jsonPath("$.policyNumber").value(org.hamcrest.Matchers.startsWith("POL-")))
                .andExpect(jsonPath("$.unitCode").value(T1))
                .andExpect(jsonPath("$.productVersion").value(1))
                .andExpect(jsonPath("$.expiryDate").value(today().plusYears(1).minusDays(1).toString()))
                .andExpect(jsonPath("$.totalPremium.amount").value("75750.00"))
                .andExpect(jsonPath("$.coverages[*].code", contains("OWN_DAMAGE", "THIRD_PARTY_PROPERTY", "WINDSCREEN")))
                .andExpect(jsonPath("$.coverages[0].limit.amount").value("1450000.00"))
                .andExpect(jsonPath("$.coverages[0].remaining.amount").value("1450000.00"))
                .andExpect(jsonPath("$.rating.coverages[0].basis").value("4.5% of sum insured 1450000.00"))
                .andExpect(jsonPath("$.quoteExpiresAt").exists())
                .andExpect(jsonPath("$.customer.customerNumber").exists())
                .andExpect(jsonPath("$.eligibility").isEmpty());
    }

    @Test
    void eligibilityRefersYoungDriversAndDeclinesOldVehicles() throws Exception {
        String young = customer(today().minusYears(19).toString());
        quote(AGENT, young, today(), "PRIVATE", 2018, "[]")
                .andExpect(jsonPath("$.status").value("REFERRED"))
                .andExpect(jsonPath("$.quoteExpiresAt").doesNotExist())
                .andExpect(jsonPath("$.eligibility[0].ruleCode").value("DRIVER_AGE"))
                .andExpect(jsonPath("$.rating.coverages[0].factors[0].multiplier").value("1.25"));
        quote(AGENT, customer("1985-01-01"), today(), "PRIVATE", 2001, "[]")
                .andExpect(jsonPath("$.status").value("DECLINED"))
                .andExpect(jsonPath("$.eligibility[0].outcome").value("DECLINE"));
    }

    @Test
    void acceptingCreatesTheScheduleAndTheFirstPaymentIssuesThePolicy() throws Exception {
        String policyId = createdId(quote(AGENT, customer("1990-04-12"), today(), "PRIVATE", 2017, "[\"WINDSCREEN\"]"));
        String path = "/api/v1/policies/" + policyId;

        // Agents quote; issuing needs policies.issue (a team lead).
        call(AGENT, post(path + "/accept").header("If-Match", "\"0\"")).andExpect(status().isForbidden());
        call(TEAM_LEAD, post(path + "/accept").header("If-Match", "\"0\""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.acceptedAt").exists())
                .andExpect(jsonPath("$.quoteExpiresAt").doesNotExist());
        call(TEAM_LEAD, post(path + "/accept").header("If-Match", "\"1\"")).andExpect(status().isConflict());

        String schedule = call(AGENT, get(path + "/premium-schedule"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.frequency").value("MONTHLY"))
                .andExpect(jsonPath("$.installments.length()").value(12))
                .andExpect(jsonPath("$.installments[0].dueDate").value(today().toString()))
                .andExpect(jsonPath("$.installments[0].status").value("DUE"))
                .andExpect(jsonPath("$.installments[0].amount.amount").value("6312.50"))
                .andExpect(jsonPath("$.installments[1].status").value("SCHEDULED"))
                .andReturn().getResponse().getContentAsString();
        UUID first = UUID.fromString(JsonPath.read(schedule, "$.installments[0].id"));
        BigDecimal installmentTotal = JsonPath.<java.util.List<String>>read(schedule, "$.installments[*].amount.amount")
                .stream().map(BigDecimal::new).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(installmentTotal).isEqualByComparingTo("75750.00");

        // A partial payment doesn't issue; the rest does. Pay redelivering an event changes nothing.
        String payment1 = "pay-" + UUID.randomUUID();
        payments.collectionSucceeded(new CollectionSucceeded("JUB", first, payment1, new BigDecimal("4000.00"), "KES",
                Instant.now()));
        call(AGENT, get(path)).andExpect(jsonPath("$.status").value("PENDING_PAYMENT"));
        String payment2 = "pay-" + UUID.randomUUID();
        CollectionSucceeded rest = new CollectionSucceeded("JUB", first, payment2, new BigDecimal("2312.50"), "KES",
                Instant.now());
        payments.collectionSucceeded(rest);
        payments.collectionSucceeded(rest);

        call(AGENT, get(path))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.issuedAt").exists())
                .andExpect(jsonPath("$.effectiveDate").value(today().toString()));
        call(AGENT, get(path + "/premium-schedule"))
                .andExpect(jsonPath("$.installments[0].status").value("PAID"))
                .andExpect(jsonPath("$.installments[0].paidAmount.amount").value("6312.50"));

        Staff auditor = new Staff("policy.auditor", "JUB", Map.of("team", Map.of(T1, Set.of("audit.view"))));
        register(auditor);
        call(auditor, get("/api/v1/audit-events").param("subjectId", policyId).param("size", "50"))
                .andExpect(jsonPath("$.content[*].action", hasItems("POLICY_QUOTED", "POLICY_ACCEPTED",
                        "PREMIUM_SCHEDULE_CREATED", "PREMIUM_PAYMENT_RECEIVED", "POLICY_ISSUED")))
                .andExpect(jsonPath("$.content[?(@.action == 'POLICY_ISSUED')].actorType", contains("SYSTEM")));
        Integer references = CurrentTenant.call("JUB", () -> tx.execute(status -> jdbc.queryForObject(
                "select count(*) from premium.payment_reference where installment_id = ?", Integer.class, first)));
        assertThat(references).isEqualTo(2);
    }

    @Test
    void coverCantStartBeforeItIsPaidFor() throws Exception {
        String policyId = createdId(quote(AGENT, customer("1990-04-12"), today(), "PRIVATE", 2017, "[]"));
        call(TEAM_LEAD, post("/api/v1/policies/" + policyId + "/accept").header("If-Match", "\"0\""))
                .andExpect(status().isOk());
        UUID first = UUID.fromString(field(call(AGENT, get("/api/v1/policies/" + policyId + "/premium-schedule")),
                "$.installments[0].id"));
        BigDecimal amount = new BigDecimal(field(call(AGENT, get("/api/v1/policies/" + policyId + "/premium-schedule")),
                "$.installments[0].amount.amount"));

        // Paid three days after the quoted start: cover starts on the payment date, for a full term.
        payments.collectionSucceeded(new CollectionSucceeded("JUB", first, "pay-" + UUID.randomUUID(), amount, "KES",
                Instant.now().plus(Duration.ofDays(3))));
        LocalDate paidOn = today().plusDays(3);
        call(AGENT, get("/api/v1/policies/" + policyId))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.effectiveDate").value(paidOn.toString()))
                .andExpect(jsonPath("$.expiryDate").value(paidOn.plusYears(1).minusDays(1).toString()));
    }

    @Test
    void anExpiredQuoteCantBeAccepted() throws Exception {
        String policyId = createdId(quote(AGENT, customer("1990-04-12"), today(), "PRIVATE", 2017, "[]"));
        CurrentTenant.run("JUB", () -> tx.executeWithoutResult(status -> jdbc.update(
                "update policy.policy set quote_expires_at = now() - interval '1 minute' where id = ?",
                UUID.fromString(policyId))));
        call(TEAM_LEAD, post("/api/v1/policies/" + policyId + "/accept").header("If-Match", "\"0\""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://alensi.io/problems/quote-expired"));
    }

    @Test
    void policiesAreScopedToUnitsAndTenants() throws Exception {
        String customer = customer("1990-04-12");
        String policyId = createdId(quote(AGENT, customer, today(), "PRIVATE", 2017, "[]"));

        call(WESTLANDS, get("/api/v1/policies/" + policyId)).andExpect(status().isNotFound());
        call(WESTLANDS, get("/api/v1/policies/" + policyId + "/premium-schedule")).andExpect(status().isNotFound());
        call(WESTLANDS, get("/api/v1/policies").param("size", "100"))
                .andExpect(jsonPath("$.content[*].id", not(hasItem(policyId))));
        call(MADISON, get("/api/v1/policies/" + policyId)).andExpect(status().isNotFound());
        call(AGENT, get("/api/v1/policies").param("customerId", customer))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].totalPremium.amount").value("72750.00"));

        // Can see the policy but not the customer: the customer is restricted, not leaked.
        call(UNDERWRITER, get("/api/v1/policies/" + policyId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customer.id").value(customer))
                .andExpect(jsonPath("$.customer.restricted").value(true))
                .andExpect(jsonPath("$.customer.displayName").doesNotExist());

        // Quoting needs to see the customer and to quote in the selling unit.
        quote(WESTLANDS, customer, today(), "PRIVATE", 2017, "[]").andExpect(status().isNotFound());
        send(AGENT, post("/api/v1/policies/quotes"), quoteBody(customer, today(), "PRIVATE", 2017, "[]")
                .replace("\"customerId\"", "\"unitCode\": \"" + WLD + "\", \"customerId\""))
                .andExpect(status().isForbidden());
    }

    @Test
    void unquotableRequestsListEveryProblem() throws Exception {
        String customer = customer("1990-04-12");
        send(AGENT, post("/api/v1/policies/quotes"), quoteBody(customer, today().minusDays(1), "PRIVATE", 2017,
                "[\"TELEPORTATION\"]").replace("\"use\": \"PRIVATE\"", "\"use\": {\"nested\": true}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("https://alensi.io/problems/quote-not-possible"))
                .andExpect(jsonPath("$.errors[*].field", hasItems("effectiveDate", "insuredItems[0].attributes.use")));
        quote(AGENT, customer, today(), "PRIVATE", 2017, "[\"TELEPORTATION\"]")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("coverages"));
        send(AGENT, post("/api/v1/policies/quotes"), quoteBody(customer, today(), "PRIVATE", 2017, "[]")
                .replace("\"productCode\": \"" + productCode + "\"", "\"productCode\": \"NOPE\""))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("productCode"));
    }

    // ------------------------------------------------------------------ fixtures

    private ResultActions quote(Staff staff, String customerId, LocalDate start, String use, int year, String optional)
            throws Exception {
        return send(staff, post("/api/v1/policies/quotes"), quoteBody(customerId, start, use, year, optional));
    }

    private static String quoteBody(String customerId, LocalDate start, String use, int year, String optional) {
        return """
                {"customerId": "%s", "productCode": "%s", "effectiveDate": "%s", "paymentFrequency": "MONTHLY",
                 "insuredItems": [{"itemType": "VEHICLE", "description": "Toyota Axio KDA 123A",
                   "sumInsured": {"amount": "1450000.00", "currency": "KES"},
                   "attributes": {"registration": "KDA 123A", "yearOfManufacture": %d, "use": "%s"}}],
                 "coverages": %s}""".formatted(customerId, productCode, start, year, use, optional);
    }

    private String customer(String dateOfBirth) throws Exception {
        return createdId(send(AGENT, post("/api/v1/customers"), """
                {"customerType": "INDIVIDUAL", "unitCode": "%s", "firstName": "Achieng", "lastName": "Otieno",
                 "dateOfBirth": "%s"}""".formatted(T1, dateOfBirth)));
    }

    private String publishMotorProduct() throws Exception {
        String code = "POLICY-MOTOR-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        String productId = createdId(send(PRODUCT_MANAGER, post("/api/v1/products"),
                "{\"code\": \"%s\", \"name\": \"Motor comprehensive\", \"lineOfBusiness\": \"MOTOR\"}".formatted(code)));
        String etag = etag(send(PRODUCT_MANAGER, post("/api/v1/products/{id}/versions", productId), "{}"));
        etag = etag(send(PRODUCT_MANAGER, put("/api/v1/products/{id}/versions/1/coverages", productId)
                .header("If-Match", etag), """
                {"coverages": [
                   {"code": "OWN_DAMAGE", "name": "Own damage", "mandatory": true, "incidentTypes": ["COLLISION", "THEFT"],
                    "limit": {"type": "SUM_INSURED", "basis": "PER_EVENT"},
                    "deductible": {"type": "PERCENTAGE", "value": "2.5", "minimum": {"amount": "15000.00", "currency": "KES"}}},
                   {"code": "THIRD_PARTY_PROPERTY", "name": "Third party property", "mandatory": true,
                    "incidentTypes": ["THIRD_PARTY_PROPERTY"],
                    "limit": {"type": "FIXED", "amount": {"amount": "3000000.00", "currency": "KES"}, "basis": "PER_EVENT"}},
                   {"code": "WINDSCREEN", "name": "Windscreen", "incidentTypes": ["WINDSCREEN"],
                    "limit": {"type": "FIXED", "amount": {"amount": "50000.00", "currency": "KES"}, "basis": "AGGREGATE"}}]}
                """).andExpect(status().isOk()));
        etag = etag(send(PRODUCT_MANAGER, put("/api/v1/products/{id}/versions/1/eligibility", productId)
                .header("If-Match", etag), """
                [{"code": "VEHICLE_AGE", "rule": {"attribute": "item.vehicle.age", "operator": "LTE", "value": 15},
                  "outcome": "DECLINE", "message": "Vehicles older than 15 years can't be insured"},
                 {"code": "DRIVER_AGE", "rule": {"attribute": "customer.age", "operator": "GTE", "value": 21},
                  "outcome": "REFER", "message": "Drivers under 21 need underwriter review"}]
                """).andExpect(status().isOk()));
        etag = etag(send(PRODUCT_MANAGER, put("/api/v1/products/{id}/versions/1/rating", productId)
                .header("If-Match", etag), """
                {"minimumPremium": {"amount": "37500.00", "currency": "KES"},
                 "baseRates": [{"coverageCode": "OWN_DAMAGE", "ratePercent": "4.5"},
                               {"coverageCode": "THIRD_PARTY_PROPERTY", "basePremium": {"amount": "7500.00", "currency": "KES"}},
                               {"coverageCode": "WINDSCREEN", "basePremium": {"amount": "3000.00", "currency": "KES"}}],
                 "factors": [{"attribute": "customer.age", "bands": [{"from": 18, "to": 24, "multiplier": "1.25"}]},
                             {"attribute": "item.vehicle.use", "coverageCode": "OWN_DAMAGE",
                              "bands": [{"in": ["COMMERCIAL"], "multiplier": "1.3"}]}]}
                """).andExpect(status().isOk()));
        send(PRODUCT_MANAGER, post("/api/v1/products/{id}/versions/1/publish", productId).header("If-Match", etag), "{}")
                .andExpect(status().isOk());
        return code;
    }

    private static LocalDate today() {
        return LocalDate.now(NAIROBI);
    }

    private static Set<String> plus(Set<String> permissions, String extra) {
        Set<String> all = new HashSet<>(permissions);
        all.add(extra);
        return all;
    }
}
