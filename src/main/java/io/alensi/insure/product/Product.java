package io.alensi.insure.product;

import java.util.UUID;

import io.alensi.insure.platform.persistence.AuditedEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(schema = "product", name = "product")
class Product extends AuditedEntity {

    enum Status { ACTIVE, RETIRED }

    @Id
    private UUID id;

    private String code;
    private String name;

    @Enumerated(EnumType.STRING)
    private LineOfBusiness lineOfBusiness;

    private String currency;

    @Enumerated(EnumType.STRING)
    private Status status;

    protected Product() {
    }

    Product(UUID id, String code, String name, LineOfBusiness lineOfBusiness, String currency) {
        this.id = id;
        this.code = code;
        this.name = name;
        this.lineOfBusiness = lineOfBusiness;
        this.currency = currency;
        this.status = Status.ACTIVE;
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

    LineOfBusiness getLineOfBusiness() {
        return lineOfBusiness;
    }

    String getCurrency() {
        return currency;
    }

    Status getStatus() {
        return status;
    }
}
