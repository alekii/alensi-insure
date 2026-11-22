package io.alensi.insure.product;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(schema = "product", name = "product")
class Product {

    enum Status { ACTIVE, RETIRED }

    @Id
    private UUID id;

    private String tenantCode;
    private String code;
    private String name;

    @Enumerated(EnumType.STRING)
    private LineOfBusiness lineOfBusiness;

    private String currency;

    @Enumerated(EnumType.STRING)
    private Status status;

    @Version
    private Long version;

    private Instant createdAt;
    private Instant updatedAt;
    private UUID createdBy;
    private UUID updatedBy;

    protected Product() {
    }

    Product(UUID id, String tenantCode, String code, String name, LineOfBusiness lineOfBusiness, String currency,
            UUID actor, Instant now) {
        this.id = id;
        this.tenantCode = tenantCode;
        this.code = code;
        this.name = name;
        this.lineOfBusiness = lineOfBusiness;
        this.currency = currency;
        this.status = Status.ACTIVE;
        this.createdAt = now;
        this.updatedAt = now;
        this.createdBy = actor;
        this.updatedBy = actor;
    }

    UUID getId() {
        return id;
    }

    String getTenantCode() {
        return tenantCode;
    }

    String getCode() {
        return code;
    }

    String getName() {
        return name;
    }

    LineOfBusiness getLineOfBusiness() {
        return lineOfBusiness;
    }

    String getCurrency() {
        return currency;
    }

    Status getStatus() {
        return status;
    }

    long getVersion() {
        return version;
    }
}
