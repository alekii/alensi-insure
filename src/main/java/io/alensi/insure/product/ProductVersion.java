package io.alensi.insure.product;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import io.alensi.insure.platform.persistence.AuditedEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

/**
 * A version of a product's terms. Only a {@code DRAFT} can change; {@code PUBLISHED} is immutable
 * (policies were sold under it) apart from closing its effective period or withdrawing it.
 */
@Entity
@Table(schema = "product", name = "product_version")
class ProductVersion extends AuditedEntity {

    enum Status { DRAFT, PUBLISHED, WITHDRAWN }

    @Id
    private UUID id;

    private UUID productId;
    private int versionNumber;

    @Enumerated(EnumType.STRING)
    private Status status;

    private String currency;
    private int termMonths;
    private int gracePeriodDays;
    private BigDecimal minimumPremium;
    private LocalDate effectiveFrom;
    private LocalDate effectiveTo;
    private UUID wordingDocumentId;
    private Instant publishedAt;
    private Instant withdrawnAt;

    @OneToMany(mappedBy = "productVersion", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position")
    private List<CoverageDefinition> coverages = new ArrayList<>();

    @OneToMany(mappedBy = "productVersion", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position")
    private List<CoverageExclusion> exclusions = new ArrayList<>();

    @OneToMany(mappedBy = "productVersion", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position")
    private List<EligibilityRule> eligibility = new ArrayList<>();

    @OneToMany(mappedBy = "productVersion", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("coverageCode")
    private List<BaseRate> baseRates = new ArrayList<>();

    @OneToMany(mappedBy = "productVersion", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position")
    private List<RatingFactor> ratingFactors = new ArrayList<>();

    protected ProductVersion() {
    }

    ProductVersion(UUID id, Product product, int versionNumber, String currency, int termMonths, int gracePeriodDays,
            BigDecimal minimumPremium) {
        this.id = id;
        this.productId = product.getId();
        this.versionNumber = versionNumber;
        this.status = Status.DRAFT;
        this.currency = currency;
        this.termMonths = termMonths;
        this.gracePeriodDays = gracePeriodDays;
        this.minimumPremium = minimumPremium;
    }

    void terms(String currency, int termMonths, int gracePeriodDays) {
        this.currency = currency;
        this.termMonths = termMonths;
        this.gracePeriodDays = gracePeriodDays;
        markModified();
    }

    void replaceCoverages(List<CoverageDefinition> newCoverages, List<CoverageExclusion> newExclusions) {
        coverages.clear();
        newCoverages.forEach(coverage -> coverages.add(coverage.attachTo(this)));
        exclusions.clear();
        newExclusions.forEach(exclusion -> exclusions.add(exclusion.attachTo(this)));
        markModified();
    }

    void replaceEligibility(List<EligibilityRule> rules) {
        eligibility.clear();
        rules.forEach(rule -> eligibility.add(rule.attachTo(this)));
        markModified();
    }

    void replaceRating(BigDecimal minimumPremium, List<BaseRate> rates, List<RatingFactor> factors) {
        this.minimumPremium = minimumPremium;
        baseRates.clear();
        rates.forEach(rate -> baseRates.add(rate.attachTo(this)));
        ratingFactors.clear();
        factors.forEach(factor -> ratingFactors.add(factor.attachTo(this)));
        markModified();
    }

    void publish(LocalDate effectiveFrom, UUID wordingDocumentId, Instant now) {
        this.status = Status.PUBLISHED;
        this.effectiveFrom = effectiveFrom;
        this.wordingDocumentId = wordingDocumentId;
        this.publishedAt = now;
    }

    /** Ends sales under this version the day before its successor takes over. */
    void closeOn(LocalDate lastDay) {
        this.effectiveTo = lastDay;
    }

    void withdraw(Instant now) {
        this.status = Status.WITHDRAWN;
        this.withdrawnAt = now;
    }

    boolean coversDate(LocalDate date) {
        return status == Status.PUBLISHED && !date.isBefore(effectiveFrom)
                && (effectiveTo == null || !date.isAfter(effectiveTo));
    }

    UUID getId() {
        return id;
    }

    UUID getProductId() {
        return productId;
    }

    int getVersionNumber() {
        return versionNumber;
    }

    Status getStatus() {
        return status;
    }

    String getCurrency() {
        return currency;
    }

    int getTermMonths() {
        return termMonths;
    }

    int getGracePeriodDays() {
        return gracePeriodDays;
    }

    BigDecimal getMinimumPremium() {
        return minimumPremium;
    }

    LocalDate getEffectiveFrom() {
        return effectiveFrom;
    }

    LocalDate getEffectiveTo() {
        return effectiveTo;
    }

    UUID getWordingDocumentId() {
        return wordingDocumentId;
    }

    Instant getPublishedAt() {
        return publishedAt;
    }

    List<CoverageDefinition> getCoverages() {
        return coverages;
    }

    List<CoverageExclusion> getExclusions() {
        return exclusions;
    }

    List<EligibilityRule> getEligibility() {
        return eligibility;
    }

    List<BaseRate> getBaseRates() {
        return baseRates;
    }

    List<RatingFactor> getRatingFactors() {
        return ratingFactors;
    }
}
