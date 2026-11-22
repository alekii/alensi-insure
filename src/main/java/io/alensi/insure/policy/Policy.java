package io.alensi.insure.policy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import io.alensi.insure.platform.persistence.AuditedEntity;
import io.alensi.insure.premium.PaymentFrequency;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A policy from quote to expiry. Its terms (product version, coverages, premium) are frozen when
 * quoted; the state machine is in policy-lifecycle.md.
 */
@Entity
@Table(schema = "policy", name = "policy")
class Policy extends AuditedEntity {

    enum Status { QUOTED, REFERRED, DECLINED, QUOTE_EXPIRED, PENDING_PAYMENT, ACTIVE, LAPSED, CANCELLED, EXPIRED }

    @Id
    private UUID id;

    private String policyNumber;
    private UUID customerId;
    private UUID productId;
    private UUID productVersionId;
    private String productCode;
    private int productVersionNumber;

    @Enumerated(EnumType.STRING)
    private Status status;

    private LocalDate effectiveDate;
    private LocalDate expiryDate;
    private BigDecimal totalPremium;
    private BigDecimal totalSumInsured;
    private String currency;

    @Enumerated(EnumType.STRING)
    private PaymentFrequency paymentFrequency;

    private String unitCode;
    private Instant quoteExpiresAt;
    private Instant acceptedAt;
    private Instant issuedAt;

    /** Failed eligibility rules, as quoted. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String eligibility;

    /** How the premium was calculated, as quoted. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String rating;

    @OneToMany(mappedBy = "policy", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position")
    private List<InsuredItem> items = new ArrayList<>();

    @OneToMany(mappedBy = "policy", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position")
    private List<PolicyCoverage> coverages = new ArrayList<>();

    protected Policy() {
    }

    Policy(UUID id, String policyNumber, UUID customerId, UUID productId, UUID productVersionId, String productCode,
            int productVersionNumber, Status status, LocalDate effectiveDate, LocalDate expiryDate,
            BigDecimal totalPremium, BigDecimal totalSumInsured, String currency, PaymentFrequency paymentFrequency,
            String unitCode, Instant quoteExpiresAt, String eligibility, String rating) {
        this.id = id;
        this.policyNumber = policyNumber;
        this.customerId = customerId;
        this.productId = productId;
        this.productVersionId = productVersionId;
        this.productCode = productCode;
        this.productVersionNumber = productVersionNumber;
        this.status = status;
        this.effectiveDate = effectiveDate;
        this.expiryDate = expiryDate;
        this.totalPremium = totalPremium;
        this.totalSumInsured = totalSumInsured;
        this.currency = currency;
        this.paymentFrequency = paymentFrequency;
        this.unitCode = unitCode;
        this.quoteExpiresAt = quoteExpiresAt;
        this.eligibility = eligibility;
        this.rating = rating;
    }

    void add(InsuredItem item) {
        items.add(item.attachTo(this, items.size()));
    }

    void add(PolicyCoverage coverage) {
        coverages.add(coverage.attachTo(this, coverages.size()));
    }

    /** QUOTED → PENDING_PAYMENT. The caller checks expiry and permissions. */
    void accept(Instant now) {
        this.status = Status.PENDING_PAYMENT;
        this.acceptedAt = now;
        this.quoteExpiresAt = null;
    }

    /**
     * PENDING_PAYMENT → ACTIVE when the first installment is paid. Cover can't start before it was
     * paid for: a past effective date moves to the payment date, keeping the term.
     */
    boolean issue(LocalDate paidOn, Instant now) {
        boolean moved = effectiveDate.isBefore(paidOn);
        if (moved) {
            long termMonths = ChronoUnit.MONTHS.between(effectiveDate, expiryDate.plusDays(1));
            effectiveDate = paidOn;
            expiryDate = paidOn.plusMonths(termMonths).minusDays(1);
        }
        this.status = Status.ACTIVE;
        this.issuedAt = now;
        return moved;
    }

    boolean quoteExpired(Instant now) {
        return quoteExpiresAt != null && !now.isBefore(quoteExpiresAt);
    }

    UUID getId() {
        return id;
    }

    String getPolicyNumber() {
        return policyNumber;
    }

    UUID getCustomerId() {
        return customerId;
    }

    UUID getProductId() {
        return productId;
    }

    String getProductCode() {
        return productCode;
    }

    int getProductVersionNumber() {
        return productVersionNumber;
    }

    Status getStatus() {
        return status;
    }

    LocalDate getEffectiveDate() {
        return effectiveDate;
    }

    LocalDate getExpiryDate() {
        return expiryDate;
    }

    BigDecimal getTotalPremium() {
        return totalPremium;
    }

    BigDecimal getTotalSumInsured() {
        return totalSumInsured;
    }

    String getCurrency() {
        return currency;
    }

    PaymentFrequency getPaymentFrequency() {
        return paymentFrequency;
    }

    String getUnitCode() {
        return unitCode;
    }

    Instant getQuoteExpiresAt() {
        return quoteExpiresAt;
    }

    Instant getAcceptedAt() {
        return acceptedAt;
    }

    Instant getIssuedAt() {
        return issuedAt;
    }

    String getEligibility() {
        return eligibility;
    }

    String getRating() {
        return rating;
    }

    List<InsuredItem> getItems() {
        return items;
    }

    List<PolicyCoverage> getCoverages() {
        return coverages;
    }
}
