package io.alensi.insure.product;

import java.math.BigDecimal;
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
 * One band of a factor: when {@code attribute} falls in {@code band} ({@code {"from": 18, "to": 25}}
 * or {@code {"in": ["NAIROBI"]}}), the base premium is multiplied. Applies to one coverage, or all
 * when {@code coverageCode} is null.
 */
@Entity
@Table(schema = "product", name = "rating_factor")
class RatingFactor extends TenantOwned {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_version_id")
    private ProductVersion productVersion;

    private int position;
    private String coverageCode;
    private String attribute;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String band;

    private BigDecimal multiplier;

    protected RatingFactor() {
    }

    RatingFactor(int position, String coverageCode, String attribute, String band, BigDecimal multiplier) {
        this.id = Ids.newId();
        this.position = position;
        this.coverageCode = coverageCode;
        this.attribute = attribute;
        this.band = band;
        this.multiplier = multiplier;
    }

    RatingFactor attachTo(ProductVersion version) {
        this.productVersion = version;
        return this;
    }

    RatingFactor copy() {
        return new RatingFactor(position, coverageCode, attribute, band, multiplier);
    }

    String getCoverageCode() {
        return coverageCode;
    }

    String getAttribute() {
        return attribute;
    }

    String getBand() {
        return band;
    }

    BigDecimal getMultiplier() {
        return multiplier;
    }
}
