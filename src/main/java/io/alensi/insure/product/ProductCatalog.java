package io.alensi.insure.product;

import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import io.alensi.insure.platform.security.CurrentTenant;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * What other modules may know about products. Permission checks are the caller's: quoting checks
 * {@code products.view}, this only answers what can be sold.
 */
@Component
public class ProductCatalog {

    private final ProductRepository products;
    private final ProductVersionRepository versions;
    private final JsonMapper json;

    ProductCatalog(ProductRepository products, ProductVersionRepository versions, JsonMapper json) {
        this.products = products;
        this.versions = versions;
        this.json = json;
    }

    /**
     * The terms a policy starting on {@code startDate} is sold under: the product is active and one
     * of its published versions is effective that day. Empty otherwise (unknown, retired, not yet
     * effective, withdrawn).
     */
    @Transactional(readOnly = true)
    public Optional<ProductTerms> sellable(UUID productId, LocalDate startDate) {
        String tenant = CurrentTenant.require();
        return products.findByTenantCodeAndId(tenant, productId)
                .filter(product -> product.getStatus() == Product.Status.ACTIVE)
                .flatMap(product -> versions.findByTenantCodeAndProductIdAndStatus(tenant, productId,
                                ProductVersion.Status.PUBLISHED).stream()
                        .filter(version -> version.coversDate(startDate))
                        .findFirst()
                        .map(version -> terms(product, version)));
    }

    private ProductTerms terms(Product product, ProductVersion version) {
        Map<String, BaseRate> rates = version.getBaseRates().stream()
                .collect(Collectors.toMap(BaseRate::getCoverageCode, Function.identity()));
        return new ProductTerms(product.getId(), product.getCode(), product.getLineOfBusiness(), version.getId(),
                version.getVersionNumber(), version.getCurrency(), version.getTermMonths(),
                version.getGracePeriodDays(), version.getMinimumPremium(),
                version.getCoverages().stream().map(coverage -> {
                    BaseRate rate = rates.get(coverage.getCode());
                    return new ProductTerms.Coverage(coverage.getId(), coverage.getCode(), coverage.getName(),
                            coverage.isMandatory(), coverage.getIncidentTypes(),
                            coverage.getLimitType() == CoverageDefinition.LimitType.SUM_INSURED,
                            coverage.getLimitAmount(),
                            coverage.getLimitBasis() == CoverageDefinition.LimitBasis.AGGREGATE,
                            coverage.getDeductibleType() == CoverageDefinition.DeductibleType.PERCENTAGE,
                            coverage.getDeductibleValue(), coverage.getDeductibleMinimum(),
                            coverage.getWaitingPeriodDays(), rate.getBasePremium(), rate.getRatePercent());
                }).toList(),
                version.getExclusions().stream().map(exclusion -> new ProductTerms.Exclusion(exclusion.getCode(),
                        exclusion.getDescription(), exclusion.getClauseReference(), exclusion.getCoverageCodes(),
                        exclusion.getMachineRule() == null ? null
                                : json.readValue(exclusion.getMachineRule(), Condition.class))).toList(),
                version.getEligibility().stream().map(rule -> new ProductTerms.Eligibility(rule.getCode(),
                        new Condition(rule.getAttribute(), rule.getOperator(), json.readTree(rule.getValue())),
                        rule.getOutcome() == EligibilityRule.Outcome.REFER, rule.getMessage())).toList(),
                version.getRatingFactors().stream().map(factor -> new ProductTerms.Factor(factor.getAttribute(),
                        factor.getCoverageCode(), json.readTree(factor.getBand()), factor.getMultiplier())).toList());
    }
}
