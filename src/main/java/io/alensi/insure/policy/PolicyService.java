package io.alensi.insure.policy;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.alensi.identity.client.AlensiAccess;
import io.alensi.identity.client.TargetScope;
import io.alensi.insure.customer.CustomerDirectory;
import io.alensi.insure.customer.CustomerDirectory.CustomerSummary;
import io.alensi.insure.platform.audit.AuditLog;
import io.alensi.insure.platform.security.CurrentTenant;
import io.alensi.insure.platform.security.InsurePermissions;
import io.alensi.insure.platform.web.ApiProblem;
import io.alensi.insure.platform.web.BusinessCalendar;
import io.alensi.insure.platform.web.Decimals;
import io.alensi.insure.platform.web.Ids;
import io.alensi.insure.platform.web.Money;
import io.alensi.insure.platform.web.Versions;
import io.alensi.insure.platform.web.Violations;
import io.alensi.insure.policy.PolicyViews.CoverageView;
import io.alensi.insure.policy.PolicyViews.CustomerRef;
import io.alensi.insure.policy.PolicyViews.Deductible;
import io.alensi.insure.policy.PolicyViews.ItemSpec;
import io.alensi.insure.policy.PolicyViews.ItemView;
import io.alensi.insure.policy.PolicyViews.PolicySummary;
import io.alensi.insure.policy.PolicyViews.PolicyView;
import io.alensi.insure.policy.PolicyViews.QuoteRequest;
import io.alensi.insure.policy.QuoteEngine.EligibilityResult;
import io.alensi.insure.policy.QuoteEngine.RatingBreakdown;
import io.alensi.insure.premium.InstallmentPaid;
import io.alensi.insure.premium.PremiumSchedules;
import io.alensi.insure.product.ProductCatalog;
import io.alensi.insure.product.ProductTerms;
import jakarta.persistence.criteria.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Quote → accept → issue (policy-lifecycle.md). A policy is owned by its selling unit: viewing
 * needs {@code policies.view} there (404 otherwise), quoting {@code policies.quote} and accepting
 * {@code policies.issue}.
 */
@Service
class PolicyService {

    static final String SUBJECT = "POLICY";
    private static final Logger log = LoggerFactory.getLogger(PolicyService.class);

    private final PolicyRepository policies;
    private final CustomerDirectory customers;
    private final ProductCatalog catalog;
    private final PremiumSchedules schedules;
    private final AlensiAccess access;
    private final AuditLog audit;
    private final BusinessCalendar calendar;
    private final JsonMapper json;
    private final Duration quoteValidity;

    PolicyService(PolicyRepository policies, CustomerDirectory customers, ProductCatalog catalog,
            PremiumSchedules schedules, AlensiAccess access, AuditLog audit, BusinessCalendar calendar, JsonMapper json,
            @Value("${insure.quote-validity:P7D}") Duration quoteValidity) {
        this.policies = policies;
        this.customers = customers;
        this.catalog = catalog;
        this.schedules = schedules;
        this.access = access;
        this.audit = audit;
        this.calendar = calendar;
        this.json = json;
        this.quoteValidity = quoteValidity;
    }

    @Transactional
    PolicyView quote(QuoteRequest request) {
        CustomerSummary customer = customers.find(request.customerId())
                .filter(found -> access.can(InsurePermissions.CUSTOMERS_VIEW, found.unitCode()))
                .orElseThrow(() -> ApiProblem.notFound("Customer"));
        String unit = request.unitCode() == null ? customer.unitCode() : request.unitCode();
        access.require(InsurePermissions.POLICIES_QUOTE, unit);
        if (!access.can(InsurePermissions.PRODUCTS_VIEW)) {
            throw ApiProblem.forbidden("forbidden", "Forbidden", "Missing 'products.view' in this tenant");
        }

        Violations problems = new Violations();
        if (!customer.active()) {
            problems.add("customerId", "the customer is inactive");
        }
        LocalDate today = calendar.today();
        if (request.effectiveDate().isBefore(today)) {
            problems.add("effectiveDate", "must not be in the past");
        }
        ProductTerms terms = catalog.sellable(request.productCode(), request.effectiveDate()).orElse(null);
        if (terms == null) {
            problems.add("productCode", "%s has no version on sale for %s".formatted(request.productCode(),
                    request.effectiveDate()));
        }
        ItemSpec itemSpec = request.insuredItems().getFirst();
        Map<String, Object> attributes = itemSpec.attributes() == null ? Map.of() : itemSpec.attributes();
        attributes.forEach((name, value) -> {
            if (value != null && !(value instanceof String || value instanceof Number || value instanceof Boolean)) {
                problems.add("insuredItems[0].attributes." + name, "must be text, a number or true/false");
            }
        });
        BigDecimal sumInsured = itemSpec.sumInsured() == null || terms == null ? null
                : itemSpec.sumInsured().amountIn(terms.currency(), "insuredItems[0].sumInsured", problems);
        problems.throwIfAny(HttpStatus.UNPROCESSABLE_CONTENT, "quote-not-possible", "Quote not possible",
                "The request can't be quoted; see errors.");

        QuoteEngine.Quote quote = QuoteEngine.quote(new QuoteEngine.Request(terms, customer,
                new QuoteEngine.Item(itemSpec.itemType(), itemSpec.description(), sumInsured, attributes),
                request.coverages() == null ? List.of() : request.coverages(), request.effectiveDate(),
                request.paymentFrequency()));

        Policy.Status status = Policy.Status.valueOf(quote.outcome().name());
        Instant now = calendar.now();
        String number = "POL-%d-%06d".formatted(today.getYear(), policies.nextPolicyNumber());
        Policy policy = new Policy(Ids.newId(), number, customer.id(), terms.productId(), terms.versionId(),
                terms.productCode(), terms.versionNumber(), status, request.effectiveDate(),
                request.effectiveDate().plusMonths(terms.termMonths()).minusDays(1), quote.total(), sumInsured,
                terms.currency(), request.paymentFrequency(), unit,
                status == Policy.Status.QUOTED ? now.plus(quoteValidity) : null,
                json.writeValueAsString(quote.eligibility()), json.writeValueAsString(quote.rating()));
        policy.add(new InsuredItem(itemSpec.itemType(), itemSpec.description(), sumInsured,
                json.writeValueAsString(attributes)));
        quote.coverages().forEach(priced -> policy.add(new PolicyCoverage(priced.definition(), priced.limit(),
                priced.premium())));
        Policy saved = policies.saveAndFlush(policy);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("policyNumber", number);
        details.put("product", terms.productCode() + " v" + terms.versionNumber());
        details.put("totalPremium", quote.total().toPlainString());
        if (!quote.eligibility().isEmpty()) {
            details.put("failedRules", quote.eligibility().stream().map(EligibilityResult::ruleCode).toList());
        }
        audit.record("POLICY_" + status.name(), SUBJECT, saved.getId(), unit, details);
        return view(saved);
    }

    @Transactional(readOnly = true)
    PolicyView get(UUID policyId) {
        return view(visible(policyId));
    }

    @Transactional(readOnly = true)
    Page<PolicySummary> search(PolicyQuery query, Pageable pageable) {
        TargetScope scope = access.scope(InsurePermissions.POLICIES_VIEW);
        if (scope.isEmpty() || (query.unitCode() != null && !scope.includes(query.unitCode()))) {
            return Page.empty(pageable);
        }
        String tenant = CurrentTenant.require();
        Specification<Policy> filter = (root, q, cb) -> {
            List<Predicate> where = new ArrayList<>();
            where.add(cb.equal(root.get("tenantCode"), tenant));
            if (!scope.all()) {
                where.add(root.get("unitCode").in(scope.targets()));
            }
            if (query.unitCode() != null) {
                where.add(cb.equal(root.get("unitCode"), query.unitCode()));
            }
            if (query.customerId() != null) {
                where.add(cb.equal(root.get("customerId"), query.customerId()));
            }
            if (query.policyNumber() != null) {
                where.add(cb.equal(root.get("policyNumber"), query.policyNumber()));
            }
            if (query.status() != null) {
                where.add(cb.equal(root.get("status"), query.status()));
            }
            if (query.productCode() != null) {
                where.add(cb.equal(root.get("productCode"), query.productCode()));
            }
            if (query.expiringBefore() != null) {
                where.add(cb.lessThan(root.get("expiryDate"), query.expiringBefore()));
            }
            return cb.and(where.toArray(Predicate[]::new));
        };
        return policies.findAll(filter, pageable).map(policy -> new PolicySummary(policy.getId(),
                policy.getTenantCode(), policy.getUnitCode(), policy.getPolicyNumber(), policy.getProductCode(),
                policy.getStatus(), policy.getEffectiveDate(), policy.getExpiryDate(),
                Money.of(policy.getTotalPremium(), policy.getCurrency())));
    }

    /** The customer accepts a QUOTED policy: PENDING_PAYMENT, with its premium schedule. */
    @Transactional
    PolicyView accept(UUID policyId, String ifMatch) {
        Policy policy = visible(policyId);
        access.require(InsurePermissions.POLICIES_ISSUE, policy.getUnitCode());
        Versions.requireMatch(ifMatch, policy.getVersion());
        if (policy.getStatus() != Policy.Status.QUOTED) {
            throw ApiProblem.conflict("invalid-state", "Invalid state",
                    "Only a QUOTED policy can be accepted; this one is %s.".formatted(policy.getStatus()));
        }
        Instant now = calendar.now();
        if (policy.quoteExpired(now)) {
            throw ApiProblem.conflict("quote-expired", "Quote expired",
                    "The quote expired at %s; create a new quote.".formatted(policy.getQuoteExpiresAt()));
        }
        policy.accept(now);
        policies.flush();
        int termMonths = catalog.version(policy.getProductId(), policy.getProductVersionNumber())
                .orElseThrow(() -> new IllegalStateException("Product version of " + policy.getPolicyNumber() + " is gone"))
                .termMonths();
        schedules.create(new PremiumSchedules.NewSchedule(policy.getId(), policy.getUnitCode(),
                policy.getPaymentFrequency(), policy.getTotalPremium(), policy.getCurrency(), calendar.today(),
                policy.getEffectiveDate(), termMonths));
        audit.record("POLICY_ACCEPTED", SUBJECT, policy.getId(), policy.getUnitCode(),
                Map.of("policyNumber", policy.getPolicyNumber()));
        return view(policy);
    }

    /**
     * Issues the policy when its first installment is paid, in the transaction that applied the
     * payment. A payment for a policy that is no longer waiting (e.g. its quote expired) issues
     * nothing; refunding it is Flow's job.
     */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    void onInstallmentPaid(InstallmentPaid event) {
        if (event.sequenceNumber() != 1) {
            return;
        }
        Policy policy = policies.findByTenantCodeAndId(CurrentTenant.require(), event.policyId())
                .orElseThrow(() -> new IllegalStateException("No policy " + event.policyId()));
        if (policy.getStatus() != Policy.Status.PENDING_PAYMENT) {
            log.warn("First installment of {} paid while it is {}; not issuing", policy.getPolicyNumber(),
                    policy.getStatus());
            return;
        }
        LocalDate quotedFrom = policy.getEffectiveDate();
        boolean moved = policy.issue(event.paidOn(), calendar.now());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("policyNumber", policy.getPolicyNumber());
        details.put("effectiveDate", policy.getEffectiveDate().toString());
        if (moved) {
            details.put("movedFrom", quotedFrom.toString());
        }
        audit.record("POLICY_ISSUED", SUBJECT, policy.getId(), policy.getUnitCode(), details);
    }

    private Policy visible(UUID policyId) {
        return policies.findByTenantCodeAndId(CurrentTenant.require(), policyId)
                .filter(policy -> access.can(InsurePermissions.POLICIES_VIEW, policy.getUnitCode()))
                .orElseThrow(() -> ApiProblem.notFound("Policy"));
    }

    private PolicyView view(Policy policy) {
        String currency = policy.getCurrency();
        CustomerRef customer = customers.find(policy.getCustomerId())
                .filter(found -> access.can(InsurePermissions.CUSTOMERS_VIEW, found.unitCode()))
                .map(found -> new CustomerRef(found.id(), found.customerNumber(), found.displayName(), null))
                .orElse(new CustomerRef(policy.getCustomerId(), null, null, true));
        List<ItemView> items = policy.getItems().stream().map(item -> new ItemView(item.getId(), item.getItemType(),
                item.getDescription(), Money.of(item.getSumInsured(), currency),
                json.readValue(item.getAttributes(), new TypeReference<Map<String, Object>>() { }))).toList();
        List<CoverageView> coverages = policy.getCoverages().stream().map(coverage -> new CoverageView(
                coverage.getCode(), coverage.getName(), Money.of(coverage.getLimitAmount(), currency),
                coverage.getLimitBasis(),
                new Deductible(coverage.getDeductibleType(), Decimals.format(coverage.getDeductibleValue()),
                        Money.of(coverage.getDeductibleMinimum(), currency)),
                coverage.getWaitingPeriodDays(), Money.of(coverage.getPremiumAmount(), currency),
                Money.of(coverage.getConsumedAmount(), currency), Money.of(coverage.getReservedAmount(), currency),
                Money.of(coverage.getLimitAmount().subtract(coverage.getConsumedAmount())
                        .subtract(coverage.getReservedAmount()), currency))).toList();
        return new PolicyView(policy.getId(), policy.getTenantCode(), policy.getUnitCode(), policy.getPolicyNumber(),
                policy.getProductCode(), policy.getProductVersionNumber(), policy.getStatus(),
                policy.getEffectiveDate(), policy.getExpiryDate(), Money.of(policy.getTotalPremium(), currency),
                Money.of(policy.getTotalSumInsured(), currency), policy.getPaymentFrequency(), customer,
                policy.getQuoteExpiresAt(), policy.getAcceptedAt(), policy.getIssuedAt(), items, coverages,
                json.readValue(policy.getRating(), RatingBreakdown.class),
                json.readValue(policy.getEligibility(), new TypeReference<List<EligibilityResult>>() { }),
                policy.getVersion());
    }

    record PolicyQuery(UUID customerId, String policyNumber, Policy.Status status, String productCode,
            LocalDate expiringBefore, String unitCode) {
    }
}
