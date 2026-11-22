package io.alensi.insure.platform.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;

/**
 * A row that belongs to one tenant. {@code tenantCode} is never set by hand: it is stamped from the
 * current tenant when the row is first persisted (before the transaction commits), can't be
 * changed afterwards, and a write under another tenant is refused (ADR-0009; row-level security
 * checks it again in the database).
 */
@MappedSuperclass
@EntityListeners(TenantAuditListener.class)
public abstract class TenantOwned {

    @Column(nullable = false, updatable = false)
    private String tenantCode;

    public String getTenantCode() {
        return tenantCode;
    }

    void stampTenant(String tenant) {
        this.tenantCode = tenant;
    }
}
