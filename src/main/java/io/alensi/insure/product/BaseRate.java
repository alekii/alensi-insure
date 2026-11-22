package io.alensi.insure.product;

import java.math.BigDecimal;
import java.util.UUID;

import io.alensi.insure.platform.persistence.TenantOwned;
import io.alensi.insure.platform.web.Ids;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** A coverage's annual premium before factors: {@code basePremium}, or {@code ratePercent} of the sum insured. */
@Entity
@Table(schema = "product", name = "base_rate")
class BaseRate extends TenantOwned {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_version_id")
    private ProductVersion productVersion;

    private String coverageCode;
    private BigDecimal basePremium;
    private BigDecimal ratePercent;

    protected BaseRate() {
    }

    BaseRate(String coverageCode, BigDecimal basePremium, BigDecimal ratePercent) {
        this.id = Ids.newId();
        this.coverageCode = coverageCode;
        this.basePremium = basePremium;
        this.ratePercent = ratePercent;
    }

    BaseRate attachTo(ProductVersion version) {
        this.productVersion = version;
        return this;
    }

    BaseRate copy() {
        return new BaseRate(coverageCode, basePremium, ratePercent);
    }

    String getCoverageCode() {
        return coverageCode;
    }

    BigDecimal getBasePremium() {
        return basePremium;
    }

    BigDecimal getRatePercent() {
        return ratePercent;
    }
}
