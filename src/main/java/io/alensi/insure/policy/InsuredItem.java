package io.alensi.insure.policy;

import java.math.BigDecimal;
import java.util.UUID;

import io.alensi.insure.platform.persistence.TenantOwned;
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
@Table(schema = "policy", name = "insured_item")
class InsuredItem extends TenantOwned {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "policy_id")
    private Policy policy;

    private int position;

    @Enumerated(EnumType.STRING)
    private ItemType itemType;

    private String description;
    private BigDecimal sumInsured;

    /** Type-specific facts (registration, make, year, use, …) as JSON. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String attributes;

    protected InsuredItem() {
    }

    InsuredItem(ItemType itemType, String description, BigDecimal sumInsured, String attributes) {
        this.id = Ids.newId();
        this.itemType = itemType;
        this.description = description;
        this.sumInsured = sumInsured;
        this.attributes = attributes;
    }

    InsuredItem attachTo(Policy policy, int position) {
        this.policy = policy;
        this.position = position;
        return this;
    }

    UUID getId() {
        return id;
    }

    ItemType getItemType() {
        return itemType;
    }

    String getDescription() {
        return description;
    }

    BigDecimal getSumInsured() {
        return sumInsured;
    }

    String getAttributes() {
        return attributes;
    }
}
