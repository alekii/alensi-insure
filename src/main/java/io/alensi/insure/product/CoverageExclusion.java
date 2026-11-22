package io.alensi.insure.product;

import java.util.List;
import java.util.UUID;

import io.alensi.insure.platform.persistence.TenantOwned;
import io.alensi.insure.platform.web.Ids;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Applies to {@code coverageCodes}, or every coverage of the version when empty. With a
 * {@code machineRule} the coverage check evaluates it; without one it needs a person to judge.
 */
@Entity
@Table(schema = "product", name = "coverage_exclusion")
class CoverageExclusion extends TenantOwned {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_version_id")
    private ProductVersion productVersion;

    private int position;
    private String code;
    private String description;
    private String clauseReference;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(columnDefinition = "text[]")
    private List<String> coverageCodes;

    /** A {@link Condition} as JSON, or null. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String machineRule;

    protected CoverageExclusion() {
    }

    CoverageExclusion(int position, String code, String description, String clauseReference,
            List<String> coverageCodes, String machineRule) {
        this.id = Ids.newId();
        this.position = position;
        this.code = code;
        this.description = description;
        this.clauseReference = clauseReference;
        this.coverageCodes = List.copyOf(coverageCodes);
        this.machineRule = machineRule;
    }

    CoverageExclusion attachTo(ProductVersion version) {
        this.productVersion = version;
        return this;
    }

    CoverageExclusion copy() {
        return new CoverageExclusion(position, code, description, clauseReference, coverageCodes, machineRule);
    }

    String getCode() {
        return code;
    }

    String getDescription() {
        return description;
    }

    String getClauseReference() {
        return clauseReference;
    }

    List<String> getCoverageCodes() {
        return coverageCodes;
    }

    String getMachineRule() {
        return machineRule;
    }
}
