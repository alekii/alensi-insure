package io.alensi.insure.product;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import io.alensi.identity.client.AlensiAccess;
import io.alensi.insure.platform.audit.AuditLog;
import io.alensi.insure.platform.security.CurrentTenant;
import io.alensi.insure.platform.security.CurrentUser;
import io.alensi.insure.platform.security.InsurePermissions;
import io.alensi.insure.platform.web.ApiProblem;
import io.alensi.insure.platform.web.BusinessCalendar;
import io.alensi.insure.platform.web.Decimals;
import io.alensi.insure.platform.web.Ids;
import io.alensi.insure.platform.web.Money;
import io.alensi.insure.platform.web.Versions;
import io.alensi.insure.platform.web.Violations;
import io.alensi.insure.product.ProductViews.BandSpec;
import io.alensi.insure.product.ProductViews.BaseRateSpec;
import io.alensi.insure.product.ProductViews.CoverageConfiguration;
import io.alensi.insure.product.ProductViews.CoverageSpec;
import io.alensi.insure.product.ProductViews.DeductibleSpec;
import io.alensi.insure.product.ProductViews.EligibilitySpec;
import io.alensi.insure.product.ProductViews.ExclusionSpec;
import io.alensi.insure.product.ProductViews.FactorSpec;
import io.alensi.insure.product.ProductViews.LimitSpec;
import io.alensi.insure.product.ProductViews.ProductCreate;
import io.alensi.insure.product.ProductViews.ProductView;
import io.alensi.insure.product.ProductViews.Publish;
import io.alensi.insure.product.ProductViews.Rating;
import io.alensi.insure.product.ProductViews.Terms;
import io.alensi.insure.product.ProductViews.VersionSummary;
import io.alensi.insure.product.ProductViews.VersionView;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Products are tenant-wide: configuring them needs {@code products.manage} or
 * {@code coverage.manage} on the tenant root, while {@code products.view} anywhere in the tenant is
 * enough to see them (agents hold it on their team and quote every product). Drafts are visible
 * only to those who can configure them.
 */
@Service
class ProductService {

    static final String SUBJECT = "PRODUCT";
    private static final Pattern PRODUCT_CODE = Pattern.compile("^[A-Z][A-Z0-9-]{1,31}$");
    private static final Pattern CODE = Pattern.compile("^[A-Z][A-Z0-9_]{1,31}$");
    private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");

    private final ProductRepository products;
    private final ProductVersionRepository versions;
    private final AlensiAccess access;
    private final AuditLog audit;
    private final BusinessCalendar calendar;
    private final JsonMapper json;

    ProductService(ProductRepository products, ProductVersionRepository versions, AlensiAccess access, AuditLog audit,
            BusinessCalendar calendar, JsonMapper json) {
        this.products = products;
        this.versions = versions;
        this.access = access;
        this.audit = audit;
        this.calendar = calendar;
        this.json = json;
    }

    // ------------------------------------------------------------------ products

    @Transactional
    ProductView create(ProductCreate request) {
        requireAtRoot(InsurePermissions.PRODUCTS_MANAGE);
        String tenant = CurrentTenant.require();
        String currency = request.currency() == null ? "KES" : request.currency();
        Violations violations = new Violations();
        if (!PRODUCT_CODE.matcher(request.code()).matches()) {
            violations.add("code", "must be upper case letters, digits and dashes, e.g. MOTOR-COMP");
        }
        if (!CURRENCY.matcher(currency).matches()) {
            violations.add("currency", "must be an ISO 4217 code, e.g. KES");
        }
        violations.throwIfAny();
        if (products.existsByTenantCodeAndCode(tenant, request.code())) {
            throw ApiProblem.conflict("duplicate-product", "Duplicate product",
                    "A product with code %s already exists.".formatted(request.code()));
        }
        Product product = products.saveAndFlush(new Product(Ids.newId(), tenant, request.code(), request.name(),
                request.lineOfBusiness(), currency, CurrentUser.id(), calendar.now()));
        audit.record("PRODUCT_CREATED", SUBJECT, product.getId(), tenant,
                Map.of("code", product.getCode(), "lineOfBusiness", product.getLineOfBusiness().name()));
        return view(product, List.of());
    }

    @Transactional(readOnly = true)
    List<ProductView> list(Product.Status status) {
        requireView();
        String tenant = CurrentTenant.require();
        List<Product> found = status == null ? products.findByTenantCodeOrderByCode(tenant)
                : products.findByTenantCodeAndStatusOrderByCode(tenant, status);
        Map<UUID, List<ProductVersion>> byProduct = versions
                .findByTenantCodeAndProductIdInOrderByVersionNumber(tenant, found.stream().map(Product::getId).toList())
                .stream().collect(Collectors.groupingBy(ProductVersion::getProductId));
        return found.stream().map(product -> view(product, byProduct.getOrDefault(product.getId(), List.of()))).toList();
    }

    @Transactional(readOnly = true)
    ProductView get(UUID productId) {
        requireView();
        Product product = product(productId);
        return view(product, versions.findByTenantCodeAndProductIdInOrderByVersionNumber(product.getTenantCode(),
                List.of(productId)));
    }

    // ------------------------------------------------------------------ versions

    /** A new DRAFT, empty or copied from an existing version. One draft at a time per product. */
    @Transactional
    VersionView createVersion(UUID productId, Integer copyFrom) {
        requireAtRoot(InsurePermissions.PRODUCTS_MANAGE);
        Product product = product(productId);
        if (product.getStatus() != Product.Status.ACTIVE) {
            throw ApiProblem.conflict("invalid-state", "Invalid state", "The product is retired.");
        }
        if (!versions.findByTenantCodeAndProductIdAndStatus(product.getTenantCode(), productId,
                ProductVersion.Status.DRAFT).isEmpty()) {
            throw ApiProblem.conflict("draft-exists", "Draft exists",
                    "The product already has a draft version; change or publish it first.");
        }
        int number = versions.lastVersionNumber(product.getTenantCode(), productId) + 1;
        ProductVersion draft;
        if (copyFrom == null) {
            draft = new ProductVersion(Ids.newId(), product, number, product.getCurrency(), 12, 30, BigDecimal.ZERO,
                    CurrentUser.id(), calendar.now());
        } else {
            ProductVersion source = versions.findByTenantCodeAndProductIdAndVersionNumber(product.getTenantCode(),
                    productId, copyFrom).orElseThrow(() -> ApiProblem.notFound("Product version " + copyFrom));
            draft = new ProductVersion(Ids.newId(), product, number, source.getCurrency(), source.getTermMonths(),
                    source.getGracePeriodDays(), source.getMinimumPremium(), CurrentUser.id(), calendar.now());
            draft.replaceCoverages(source.getCoverages().stream().map(CoverageDefinition::copy).toList(),
                    source.getExclusions().stream().map(CoverageExclusion::copy).toList());
            draft.replaceEligibility(source.getEligibility().stream().map(EligibilityRule::copy).toList());
            draft.replaceRating(source.getMinimumPremium(), source.getBaseRates().stream().map(BaseRate::copy).toList(),
                    source.getRatingFactors().stream().map(RatingFactor::copy).toList());
        }
        draft = versions.saveAndFlush(draft);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("versionNumber", number);
        if (copyFrom != null) {
            details.put("copiedFrom", copyFrom);
        }
        audit.record("PRODUCT_VERSION_CREATED", SUBJECT, productId, product.getTenantCode(), details);
        return view(draft);
    }

    @Transactional(readOnly = true)
    VersionView getVersion(UUID productId, int versionNumber) {
        requireView();
        return view(version(productId, versionNumber));
    }

    @Transactional
    VersionView replaceTerms(UUID productId, int versionNumber, String ifMatch, Terms terms) {
        requireAtRoot(InsurePermissions.PRODUCTS_MANAGE);
        ProductVersion draft = draft(productId, versionNumber, ifMatch);
        String currency = terms.currency() == null ? draft.getCurrency() : terms.currency();
        int termMonths = terms.termMonths() == null ? draft.getTermMonths() : terms.termMonths();
        int graceDays = terms.gracePeriodDays() == null ? draft.getGracePeriodDays() : terms.gracePeriodDays();
        Violations violations = new Violations();
        if (!CURRENCY.matcher(currency).matches()) {
            violations.add("currency", "must be an ISO 4217 code, e.g. KES");
        }
        if (termMonths < 1 || termMonths > 60) {
            violations.add("termMonths", "must be between 1 and 60");
        }
        if (graceDays < 0 || graceDays > 365) {
            violations.add("gracePeriodDays", "must be between 0 and 365");
        }
        violations.throwIfAny();
        if (!currency.equals(draft.getCurrency())
                && (!draft.getCoverages().isEmpty() || !draft.getBaseRates().isEmpty())) {
            throw ApiProblem.conflict("currency-locked", "Currency locked",
                    "Coverages and rates are priced in %s; remove them before changing the currency."
                            .formatted(draft.getCurrency()));
        }
        draft.terms(currency, termMonths, graceDays);
        return changed(draft, "PRODUCT_VERSION_TERMS_REPLACED");
    }

    @Transactional
    VersionView replaceCoverages(UUID productId, int versionNumber, String ifMatch, CoverageConfiguration request) {
        requireAtRoot(InsurePermissions.COVERAGE_MANAGE);
        ProductVersion draft = draft(productId, versionNumber, ifMatch);
        LineOfBusiness line = product(productId).getLineOfBusiness();
        Violations violations = new Violations();
        List<CoverageDefinition> coverages = new ArrayList<>();
        Set<String> codes = new HashSet<>();
        List<CoverageSpec> specs = request.coverages() == null ? List.of() : request.coverages();
        for (int i = 0; i < specs.size(); i++) {
            CoverageDefinition coverage = coverage(specs.get(i), "coverages[" + i + "]", i, line, draft.getCurrency(),
                    codes, violations);
            if (coverage != null) {
                coverages.add(coverage);
            }
        }
        List<CoverageExclusion> exclusions = new ArrayList<>();
        Set<String> exclusionCodes = new HashSet<>();
        List<ExclusionSpec> exclusionSpecs = request.exclusions() == null ? List.of() : request.exclusions();
        for (int i = 0; i < exclusionSpecs.size(); i++) {
            ExclusionSpec spec = exclusionSpecs.get(i);
            String field = "exclusions[" + i + "]";
            code(spec.code(), field + ".code", exclusionCodes, violations);
            text(spec.text(), field + ".text", Integer.MAX_VALUE, violations);
            if (spec.clauseReference() != null && spec.clauseReference().length() > 64) {
                violations.add(field + ".clauseReference", "must be at most 64 characters");
            }
            List<String> applies = spec.coverageCodes() == null ? List.of() : spec.coverageCodes();
            for (String code : applies) {
                if (!codes.contains(code)) {
                    violations.add(field + ".coverageCodes", "'" + code + "' is not one of the coverages");
                }
            }
            if (spec.rule() != null) {
                spec.rule().validate(field + ".rule", Condition.CLAIM_FACTS, violations);
            }
            exclusions.add(new CoverageExclusion(i, spec.code(), spec.text(), spec.clauseReference(), applies,
                    spec.rule() == null ? null : json.writeValueAsString(spec.rule())));
        }
        violations.throwIfAny();
        draft.replaceCoverages(coverages, exclusions);
        return changed(draft, "PRODUCT_COVERAGES_REPLACED");
    }

    @Transactional
    VersionView replaceEligibility(UUID productId, int versionNumber, String ifMatch, List<EligibilitySpec> rules) {
        requireAtRoot(InsurePermissions.COVERAGE_MANAGE);
        ProductVersion draft = draft(productId, versionNumber, ifMatch);
        Violations violations = new Violations();
        Set<String> codes = new HashSet<>();
        List<EligibilityRule> entities = new ArrayList<>();
        List<EligibilitySpec> specs = rules == null ? List.of() : rules;
        for (int i = 0; i < specs.size(); i++) {
            EligibilitySpec spec = specs.get(i);
            String field = "[" + i + "]";
            code(spec.code(), field + ".code", codes, violations);
            text(spec.message(), field + ".message", 255, violations);
            if (spec.outcome() == null) {
                violations.add(field + ".outcome", "must be DECLINE or REFER");
            }
            if (spec.rule() == null) {
                violations.add(field + ".rule", "is required");
                continue;
            }
            spec.rule().validate(field + ".rule", Condition.QUOTE_FACTS, violations);
            entities.add(new EligibilityRule(i, spec.code(), spec.rule().attribute(), spec.rule().operator(),
                    spec.rule().value() == null ? null : json.writeValueAsString(spec.rule().value()), spec.outcome(),
                    spec.message()));
        }
        violations.throwIfAny();
        draft.replaceEligibility(entities);
        return changed(draft, "PRODUCT_ELIGIBILITY_REPLACED");
    }

    @Transactional
    VersionView replaceRating(UUID productId, int versionNumber, String ifMatch, Rating rating) {
        requireAtRoot(InsurePermissions.PRODUCTS_MANAGE);
        ProductVersion draft = draft(productId, versionNumber, ifMatch);
        Set<String> coverageCodes = draft.getCoverages().stream().map(CoverageDefinition::getCode)
                .collect(Collectors.toSet());
        Violations violations = new Violations();
        BigDecimal minimumPremium = rating.minimumPremium() == null ? BigDecimal.ZERO
                : rating.minimumPremium().amountIn(draft.getCurrency(), "minimumPremium", violations);

        List<BaseRate> rates = new ArrayList<>();
        Set<String> rated = new HashSet<>();
        List<BaseRateSpec> rateSpecs = rating.baseRates() == null ? List.of() : rating.baseRates();
        for (int i = 0; i < rateSpecs.size(); i++) {
            BaseRateSpec spec = rateSpecs.get(i);
            String field = "baseRates[" + i + "]";
            if (!coverageCodes.contains(spec.coverageCode())) {
                violations.add(field + ".coverageCode", "must be one of the version's coverages " + coverageCodes);
            } else if (!rated.add(spec.coverageCode())) {
                violations.add(field + ".coverageCode", "is rated twice");
            }
            if ((spec.basePremium() == null) == (spec.ratePercent() == null)) {
                violations.add(field, "needs exactly one of basePremium and ratePercent");
                continue;
            }
            BigDecimal premium = spec.basePremium() == null ? null
                    : spec.basePremium().amountIn(draft.getCurrency(), field + ".basePremium", violations);
            BigDecimal percent = spec.ratePercent() == null ? null
                    : Decimals.parse(spec.ratePercent(), field + ".ratePercent", 6, violations);
            if (percent != null && (percent.signum() == 0 || percent.compareTo(BigDecimal.valueOf(100)) > 0)) {
                violations.add(field + ".ratePercent", "must be more than 0 and at most 100");
            }
            rates.add(new BaseRate(spec.coverageCode(), premium, percent));
        }

        List<RatingFactor> factors = new ArrayList<>();
        List<FactorSpec> factorSpecs = rating.factors() == null ? List.of() : rating.factors();
        for (int i = 0; i < factorSpecs.size(); i++) {
            FactorSpec spec = factorSpecs.get(i);
            String field = "factors[" + i + "]";
            Condition.validatePath(spec.attribute(), field + ".attribute", Condition.QUOTE_FACTS, violations);
            if (spec.coverageCode() != null && !coverageCodes.contains(spec.coverageCode())) {
                violations.add(field + ".coverageCode", "must be one of the version's coverages " + coverageCodes);
            }
            if (spec.bands() == null || spec.bands().isEmpty()) {
                violations.add(field + ".bands", "needs at least one band");
                continue;
            }
            for (int b = 0; b < spec.bands().size(); b++) {
                BandSpec band = spec.bands().get(b);
                String bandField = field + ".bands[" + b + "]";
                String bandJson = band(band, bandField, violations);
                BigDecimal multiplier = Decimals.parse(band.multiplier(), bandField + ".multiplier", 4, violations);
                if (multiplier != null && multiplier.signum() == 0) {
                    violations.add(bandField + ".multiplier", "must be more than 0");
                }
                factors.add(new RatingFactor(factors.size(), spec.coverageCode(), spec.attribute(), bandJson,
                        multiplier));
            }
        }
        violations.throwIfAny();
        draft.replaceRating(minimumPremium, rates, factors);
        return changed(draft, "PRODUCT_RATING_REPLACED");
    }

    /**
     * Publishes a complete draft from {@code effectiveFrom} (default today, never in the past). The
     * version it replaces stops selling the day before, so exactly one version is sellable per date.
     */
    @Transactional
    VersionView publish(UUID productId, int versionNumber, String ifMatch, Publish request) {
        requireAtRoot(InsurePermissions.PRODUCTS_MANAGE);
        Product product = product(productId);
        ProductVersion draft = draft(productId, versionNumber, ifMatch);
        LocalDate today = calendar.today();
        LocalDate from = request == null || request.effectiveFrom() == null ? today : request.effectiveFrom();

        Violations problems = new Violations();
        if (product.getStatus() != Product.Status.ACTIVE) {
            problems.add("product", "is retired");
        }
        if (from.isBefore(today)) {
            problems.add("effectiveFrom", "must not be in the past");
        }
        completeness(draft, problems);
        List<ProductVersion> superseded = new ArrayList<>();
        for (ProductVersion published : versions.findByTenantCodeAndProductIdAndStatus(product.getTenantCode(),
                productId, ProductVersion.Status.PUBLISHED)) {
            if (published.getEffectiveTo() != null && published.getEffectiveTo().isBefore(from)) {
                continue;
            }
            if (!published.getEffectiveFrom().isBefore(from)) {
                problems.add("effectiveFrom", "must be after version %d's start (%s); withdraw it to replace it"
                        .formatted(published.getVersionNumber(), published.getEffectiveFrom()));
            } else {
                superseded.add(published);
            }
        }
        problems.throwIfAny(HttpStatus.UNPROCESSABLE_CONTENT, "product-not-publishable", "Not publishable",
                "The version can't be published yet; see errors.");

        superseded.forEach(previous -> {
            previous.closeOn(from.minusDays(1));
            previous.touch(CurrentUser.id(), calendar.now());
        });
        draft.publish(from, request == null ? null : request.wordingDocumentId(), calendar.now());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("versionNumber", versionNumber);
        details.put("effectiveFrom", from.toString());
        details.put("superseded", superseded.stream().map(ProductVersion::getVersionNumber).toList());
        return changed(draft, "PRODUCT_VERSION_PUBLISHED", details);
    }

    /** Stops new sales on a published version. Policies already sold under it are unaffected. */
    @Transactional
    VersionView withdraw(UUID productId, int versionNumber, String ifMatch) {
        requireAtRoot(InsurePermissions.PRODUCTS_MANAGE);
        ProductVersion version = version(productId, versionNumber);
        Versions.requireMatch(ifMatch, version.getVersion());
        if (version.getStatus() != ProductVersion.Status.PUBLISHED) {
            throw ApiProblem.conflict("invalid-state", "Invalid state", "Only a published version can be withdrawn.");
        }
        version.withdraw(calendar.now());
        return changed(version, "PRODUCT_VERSION_WITHDRAWN");
    }

    // ------------------------------------------------------------------ rules

    private void completeness(ProductVersion draft, Violations problems) {
        List<CoverageDefinition> coverages = draft.getCoverages();
        if (coverages.isEmpty()) {
            problems.add("coverages", "at least one coverage is required");
        } else if (coverages.stream().noneMatch(CoverageDefinition::isMandatory)) {
            problems.add("coverages", "at least one coverage must be mandatory, so every policy covers something");
        }
        Set<String> codes = coverages.stream().map(CoverageDefinition::getCode).collect(Collectors.toSet());
        Set<String> rated = draft.getBaseRates().stream().map(BaseRate::getCoverageCode).collect(Collectors.toSet());
        for (String code : codes) {
            if (!rated.contains(code)) {
                problems.add("rating.baseRates", "coverage " + code + " has no base rate");
            }
        }
        // Coverages can be replaced after rating and exclusions were set; nothing may point at a removed one.
        for (String code : rated) {
            if (!codes.contains(code)) {
                problems.add("rating.baseRates", "rates coverage " + code + ", which no longer exists");
            }
        }
        for (RatingFactor factor : draft.getRatingFactors()) {
            if (factor.getCoverageCode() != null && !codes.contains(factor.getCoverageCode())) {
                problems.add("rating.factors", "a factor on " + factor.getAttribute() + " applies to coverage "
                        + factor.getCoverageCode() + ", which no longer exists");
            }
        }
    }

    private CoverageDefinition coverage(CoverageSpec spec, String field, int position, LineOfBusiness line,
            String currency, Set<String> codes, Violations violations) {
        code(spec.code(), field + ".code", codes, violations);
        text(spec.name(), field + ".name", 255, violations);
        List<String> incidents = spec.incidentTypes() == null ? List.of() : spec.incidentTypes();
        if (incidents.isEmpty()) {
            violations.add(field + ".incidentTypes", "needs at least one of " + line.incidentTypes());
        }
        for (String incident : incidents) {
            if (!line.incidentTypes().contains(incident)) {
                violations.add(field + ".incidentTypes", "'%s' is not a %s incident type; use %s"
                        .formatted(incident, line, line.incidentTypes()));
            }
        }
        int waiting = spec.waitingPeriodDays() == null ? 0 : spec.waitingPeriodDays();
        if (waiting < 0 || waiting > 3650) {
            violations.add(field + ".waitingPeriodDays", "must be between 0 and 3650");
        }

        LimitSpec limit = spec.limit();
        if (limit == null || limit.type() == null || limit.basis() == null) {
            violations.add(field + ".limit", "needs type (FIXED, SUM_INSURED) and basis (PER_EVENT, AGGREGATE)");
            return null;
        }
        BigDecimal limitAmount = null;
        if (limit.type() == CoverageDefinition.LimitType.FIXED) {
            if (limit.amount() == null) {
                violations.add(field + ".limit.amount", "is required for a FIXED limit");
            } else {
                limitAmount = limit.amount().amountIn(currency, field + ".limit.amount", violations);
                if (limitAmount != null && limitAmount.signum() == 0) {
                    violations.add(field + ".limit.amount", "must be more than 0");
                }
            }
        } else if (limit.amount() != null) {
            violations.add(field + ".limit.amount", "applies only to FIXED limits; SUM_INSURED uses the item's value");
        }

        DeductibleSpec deductible = spec.deductible() == null
                ? new DeductibleSpec(CoverageDefinition.DeductibleType.FIXED, "0", null)
                : spec.deductible();
        if (deductible.type() == null) {
            violations.add(field + ".deductible.type", "must be FIXED or PERCENTAGE");
            return null;
        }
        BigDecimal value = Decimals.parse(deductible.value(), field + ".deductible.value", 4, violations);
        BigDecimal minimum = null;
        if (deductible.type() == CoverageDefinition.DeductibleType.PERCENTAGE) {
            if (value != null && value.compareTo(BigDecimal.valueOf(100)) > 0) {
                violations.add(field + ".deductible.value", "is a percentage of the loss, at most 100");
            }
            if (deductible.minimum() != null) {
                minimum = deductible.minimum().amountIn(currency, field + ".deductible.minimum", violations);
            }
        } else if (deductible.minimum() != null) {
            violations.add(field + ".deductible.minimum", "applies only to PERCENTAGE deductibles");
        }
        return new CoverageDefinition(position, spec.code(), spec.name(), spec.description(),
                Boolean.TRUE.equals(spec.mandatory()), incidents, limit.type(), limitAmount, limit.basis(),
                deductible.type(), value, minimum, waiting);
    }

    private String band(BandSpec band, String field, Violations violations) {
        boolean range = band.from() != null || band.to() != null;
        boolean values = band.in() != null && !band.in().isEmpty();
        if (range == values) {
            violations.add(field, "needs either from/to or in");
            return null;
        }
        ObjectNode node = json.createObjectNode();
        if (range) {
            if (band.from() != null && band.to() != null && band.from().compareTo(band.to()) > 0) {
                violations.add(field, "from must not be above to");
            }
            if (band.from() != null) {
                node.put("from", band.from());
            }
            if (band.to() != null) {
                node.put("to", band.to());
            }
        } else {
            var in = node.putArray("in");
            band.in().forEach(in::add);
        }
        return json.writeValueAsString(node);
    }

    private static void code(String code, String field, Set<String> seen, Violations violations) {
        if (code == null || !CODE.matcher(code).matches()) {
            violations.add(field, "must be upper case letters, digits and underscores, e.g. OWN_DAMAGE");
        } else if (!seen.add(code)) {
            violations.add(field, "'" + code + "' is used twice");
        }
    }

    private static void text(String text, String field, int maxLength, Violations violations) {
        if (text == null || text.isBlank()) {
            violations.add(field, "is required");
        } else if (text.length() > maxLength) {
            violations.add(field, "must be at most " + maxLength + " characters");
        }
    }

    // ------------------------------------------------------------------ loading and access

    private void requireView() {
        if (!access.can(InsurePermissions.PRODUCTS_VIEW)) {
            throw ApiProblem.forbidden("forbidden", "Forbidden", "Missing 'products.view' in this tenant");
        }
    }

    private void requireAtRoot(String permission) {
        access.require(permission, CurrentTenant.require());
    }

    private boolean canConfigure() {
        String root = CurrentTenant.require();
        return access.can(InsurePermissions.PRODUCTS_MANAGE, root) || access.can(InsurePermissions.COVERAGE_MANAGE, root);
    }

    private Product product(UUID productId) {
        return products.findByTenantCodeAndId(CurrentTenant.require(), productId)
                .orElseThrow(() -> ApiProblem.notFound("Product"));
    }

    private ProductVersion version(UUID productId, int versionNumber) {
        product(productId);
        return versions.findByTenantCodeAndProductIdAndVersionNumber(CurrentTenant.require(), productId, versionNumber)
                .filter(version -> version.getStatus() != ProductVersion.Status.DRAFT || canConfigure())
                .orElseThrow(() -> ApiProblem.notFound("Product version"));
    }

    private ProductVersion draft(UUID productId, int versionNumber, String ifMatch) {
        ProductVersion version = version(productId, versionNumber);
        Versions.requireMatch(ifMatch, version.getVersion());
        if (version.getStatus() != ProductVersion.Status.DRAFT) {
            throw ApiProblem.conflict("draft-only", "Draft only",
                    "Version %d is %s and can't be changed; create a new version.".formatted(versionNumber,
                            version.getStatus()));
        }
        return version;
    }

    private VersionView changed(ProductVersion version, String action) {
        return changed(version, action, Map.of("versionNumber", version.getVersionNumber()));
    }

    private VersionView changed(ProductVersion version, String action, Map<String, ?> details) {
        version.touch(CurrentUser.id(), calendar.now());
        versions.flush();
        audit.record(action, SUBJECT, version.getProductId(), version.getTenantCode(), details);
        return view(version);
    }

    // ------------------------------------------------------------------ views

    private ProductView view(Product product, List<ProductVersion> productVersions) {
        LocalDate today = calendar.today();
        boolean drafts = canConfigure();
        List<ProductVersion> visible = productVersions.stream()
                .filter(version -> version.getStatus() != ProductVersion.Status.DRAFT || drafts).toList();
        Integer current = visible.stream().filter(version -> version.coversDate(today))
                .map(ProductVersion::getVersionNumber).findFirst().orElse(null);
        return new ProductView(product.getId(), product.getTenantCode(), product.getCode(), product.getName(),
                product.getLineOfBusiness(), product.getCurrency(), product.getStatus(), current,
                visible.stream().map(version -> new VersionSummary(version.getVersionNumber(), version.getStatus(),
                        version.getEffectiveFrom(), version.getEffectiveTo())).toList(),
                product.getVersion());
    }

    private VersionView view(ProductVersion version) {
        String currency = version.getCurrency();
        List<CoverageSpec> coverages = version.getCoverages().stream().map(coverage -> new CoverageSpec(
                coverage.getCode(), coverage.getName(), coverage.getDescription(), coverage.isMandatory(),
                coverage.getIncidentTypes(),
                new LimitSpec(coverage.getLimitType(), Money.of(coverage.getLimitAmount(), currency),
                        coverage.getLimitBasis()),
                new DeductibleSpec(coverage.getDeductibleType(), Decimals.format(coverage.getDeductibleValue()),
                        Money.of(coverage.getDeductibleMinimum(), currency)),
                coverage.getWaitingPeriodDays())).toList();
        List<ExclusionSpec> exclusions = version.getExclusions().stream().map(exclusion -> new ExclusionSpec(
                exclusion.getCode(), exclusion.getDescription(), exclusion.getClauseReference(),
                exclusion.getCoverageCodes(),
                exclusion.getMachineRule() == null ? null : json.readValue(exclusion.getMachineRule(), Condition.class)))
                .toList();
        List<EligibilitySpec> eligibility = version.getEligibility().stream().map(rule -> new EligibilitySpec(
                rule.getCode(), new Condition(rule.getAttribute(), rule.getOperator(), json.readTree(rule.getValue())),
                rule.getOutcome(), rule.getMessage())).toList();
        List<BaseRateSpec> rates = version.getBaseRates().stream().map(rate -> new BaseRateSpec(rate.getCoverageCode(),
                Money.of(rate.getBasePremium(), currency), Decimals.format(rate.getRatePercent()))).toList();
        return new VersionView(version.getProductId(), version.getVersionNumber(), version.getStatus(), currency,
                version.getTermMonths(), version.getGracePeriodDays(), version.getEffectiveFrom(),
                version.getEffectiveTo(), version.getPublishedAt(), version.getWordingDocumentId(),
                new CoverageConfiguration(coverages, exclusions), eligibility,
                new Rating(Money.of(version.getMinimumPremium(), currency), rates, factors(version.getRatingFactors())),
                version.getVersion());
    }

    /** Rows are one band each; the API groups them back into factors by attribute and coverage. */
    private List<FactorSpec> factors(List<RatingFactor> rows) {
        Map<List<String>, List<BandSpec>> grouped = new LinkedHashMap<>();
        Function<RatingFactor, List<String>> key = row -> java.util.Arrays.asList(row.getAttribute(),
                row.getCoverageCode());
        for (RatingFactor row : rows) {
            JsonNode band = json.readTree(row.getBand());
            List<String> in = band.has("in") ? band.get("in").valueStream().map(JsonNode::asString).toList() : null;
            grouped.computeIfAbsent(key.apply(row), k -> new ArrayList<>()).add(new BandSpec(
                    band.has("from") ? band.get("from").decimalValue() : null,
                    band.has("to") ? band.get("to").decimalValue() : null, in, Decimals.format(row.getMultiplier())));
        }
        return grouped.entrySet().stream()
                .map(entry -> new FactorSpec(entry.getKey().get(0), entry.getKey().get(1), entry.getValue()))
                .toList();
    }
}
