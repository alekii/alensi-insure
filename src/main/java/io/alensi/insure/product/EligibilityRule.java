package io.alensi.insure.product;

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

/** The condition must hold for a quote; when it fails the outcome applies (DECLINE or REFER). */
@Entity
@Table(schema = "product", name = "eligibility_rule")
class EligibilityRule {

    enum Outcome { DECLINE, REFER }

    @Id
    private UUID id;

    private String tenantCode;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_version_id")
    private ProductVersion productVersion;

    private int position;
    private String code;
    private String attribute;

    @Enumerated(EnumType.STRING)
    private Condition.Operator operator;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String value;

    @Enumerated(EnumType.STRING)
    private Outcome outcome;

    private String message;

    protected EligibilityRule() {
    }

    EligibilityRule(int position, String code, String attribute, Condition.Operator operator, String value,
            Outcome outcome, String message) {
        this.id = Ids.newId();
        this.position = position;
        this.code = code;
        this.attribute = attribute;
        this.operator = operator;
        this.value = value;
        this.outcome = outcome;
        this.message = message;
    }

    EligibilityRule attachTo(ProductVersion version) {
        this.productVersion = version;
        this.tenantCode = version.getTenantCode();
        return this;
    }

    EligibilityRule copy() {
        return new EligibilityRule(position, code, attribute, operator, value, outcome, message);
    }

    String getCode() {
        return code;
    }

    String getAttribute() {
        return attribute;
    }

    Condition.Operator getOperator() {
        return operator;
    }

    String getValue() {
        return value;
    }

    Outcome getOutcome() {
        return outcome;
    }

    String getMessage() {
        return message;
    }
}
