package io.alensi.insure.policy;

import java.math.BigDecimal;
import java.util.UUID;

import io.alensi.insure.platform.persistence.TenantOwned;
import io.alensi.insure.platform.web.Ids;
import io.alensi.insure.product.ProductTerms;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * A coverage as sold on this policy: the product's terms copied at quote time, so later product
 * versions never change what was sold. Claims consume and reserve against the limit.
 */
@Entity
@Table(schema = "policy", name = "policy_coverage")
class PolicyCoverage extends TenantOwned {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "policy_id")
    private Policy policy;

    private UUID coverageDefinitionId;
    private int position;
    private String code;
    private String name;
    private BigDecimal limitAmount;
    private String limitBasis;
    private String deductibleType;
    private BigDecimal deductibleValue;
    private BigDecimal deductibleMinimum;
    private int waitingPeriodDays;
    private BigDecimal premiumAmount;
    private BigDecimal consumedAmount;
    private BigDecimal reservedAmount;

    protected PolicyCoverage() {
    }

    PolicyCoverage(ProductTerms.Coverage definition, BigDecimal limit, BigDecimal premium) {
        this.id = Ids.newId();
        this.coverageDefinitionId = definition.id();
        this.code = definition.code();
        this.name = definition.name();
        this.limitAmount = limit;
        this.limitBasis = definition.aggregateLimit() ? "AGGREGATE" : "PER_EVENT";
        this.deductibleType = definition.percentageDeductible() ? "PERCENTAGE" : "FIXED";
        this.deductibleValue = definition.deductibleValue();
        this.deductibleMinimum = definition.deductibleMinimum();
        this.waitingPeriodDays = definition.waitingPeriodDays();
        this.premiumAmount = premium;
        this.consumedAmount = BigDecimal.ZERO;
        this.reservedAmount = BigDecimal.ZERO;
    }

    PolicyCoverage attachTo(Policy policy, int position) {
        this.policy = policy;
        this.position = position;
        return this;
    }

    String getCode() {
        return code;
    }

    String getName() {
        return name;
    }

    BigDecimal getLimitAmount() {
        return limitAmount;
    }

    String getLimitBasis() {
        return limitBasis;
    }

    String getDeductibleType() {
        return deductibleType;
    }

    BigDecimal getDeductibleValue() {
        return deductibleValue;
    }

    BigDecimal getDeductibleMinimum() {
        return deductibleMinimum;
    }

    int getWaitingPeriodDays() {
        return waitingPeriodDays;
    }

    BigDecimal getPremiumAmount() {
        return premiumAmount;
    }

    BigDecimal getConsumedAmount() {
        return consumedAmount;
    }

    BigDecimal getReservedAmount() {
        return reservedAmount;
    }
}
