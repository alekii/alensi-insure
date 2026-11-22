package io.alensi.insure.product;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import io.alensi.insure.platform.web.Ids;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(schema = "product", name = "coverage_definition")
class CoverageDefinition {

    /** {@code FIXED}: {@code limitAmount}. {@code SUM_INSURED}: the insured item's sum insured. */
    enum LimitType { FIXED, SUM_INSURED }

    /** {@code PER_EVENT} resets each incident; {@code AGGREGATE} is shared across the policy term. */
    enum LimitBasis { PER_EVENT, AGGREGATE }

    /** {@code PERCENTAGE} is of the assessed loss, at least {@code deductibleMinimum} if set. */
    enum DeductibleType { FIXED, PERCENTAGE }

    @Id
    private UUID id;

    private String tenantCode;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_version_id")
    private ProductVersion productVersion;

    private int position;
    private String code;
    private String name;
    private String description;
    private boolean mandatory;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(columnDefinition = "text[]")
    private List<String> incidentTypes;

    @Enumerated(EnumType.STRING)
    private LimitType limitType;

    private BigDecimal limitAmount;

    @Enumerated(EnumType.STRING)
    private LimitBasis limitBasis;

    @Enumerated(EnumType.STRING)
    private DeductibleType deductibleType;

    private BigDecimal deductibleValue;
    private BigDecimal deductibleMinimum;
    private int waitingPeriodDays;

    protected CoverageDefinition() {
    }

    CoverageDefinition(int position, String code, String name, String description, boolean mandatory,
            List<String> incidentTypes, LimitType limitType, BigDecimal limitAmount, LimitBasis limitBasis,
            DeductibleType deductibleType, BigDecimal deductibleValue, BigDecimal deductibleMinimum,
            int waitingPeriodDays) {
        this.id = Ids.newId();
        this.position = position;
        this.code = code;
        this.name = name;
        this.description = description;
        this.mandatory = mandatory;
        this.incidentTypes = List.copyOf(incidentTypes);
        this.limitType = limitType;
        this.limitAmount = limitAmount;
        this.limitBasis = limitBasis;
        this.deductibleType = deductibleType;
        this.deductibleValue = deductibleValue;
        this.deductibleMinimum = deductibleMinimum;
        this.waitingPeriodDays = waitingPeriodDays;
    }

    CoverageDefinition attachTo(ProductVersion version) {
        this.productVersion = version;
        this.tenantCode = version.getTenantCode();
        return this;
    }

    CoverageDefinition copy() {
        return new CoverageDefinition(position, code, name, description, mandatory, incidentTypes, limitType,
                limitAmount, limitBasis, deductibleType, deductibleValue, deductibleMinimum, waitingPeriodDays);
    }

    UUID getId() {
        return id;
    }

    String getCode() {
        return code;
    }

    String getName() {
        return name;
    }

    String getDescription() {
        return description;
    }

    boolean isMandatory() {
        return mandatory;
    }

    List<String> getIncidentTypes() {
        return incidentTypes;
    }

    LimitType getLimitType() {
        return limitType;
    }

    BigDecimal getLimitAmount() {
        return limitAmount;
    }

    LimitBasis getLimitBasis() {
        return limitBasis;
    }

    DeductibleType getDeductibleType() {
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
}
