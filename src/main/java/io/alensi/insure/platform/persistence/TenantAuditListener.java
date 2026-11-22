package io.alensi.insure.platform.persistence;

import java.time.Instant;

import io.alensi.insure.platform.security.CurrentTenant;
import io.alensi.insure.platform.security.CurrentUser;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;

/** Stamps tenant and audit columns; see {@link TenantOwned} and {@link AuditedEntity}. */
class TenantAuditListener {

    @PrePersist
    void beforeInsert(TenantOwned entity) {
        String tenant = CurrentTenant.require();
        if (entity.getTenantCode() != null && !entity.getTenantCode().equals(tenant)) {
            throw crossTenant(entity, tenant);
        }
        entity.stampTenant(tenant);
        if (entity instanceof AuditedEntity audited) {
            audited.stampCreated(CurrentUser.actorId(), Instant.now());
        }
    }

    @PreUpdate
    void beforeUpdate(TenantOwned entity) {
        String tenant = CurrentTenant.require();
        if (!tenant.equals(entity.getTenantCode())) {
            throw crossTenant(entity, tenant);
        }
        if (entity instanceof AuditedEntity audited) {
            audited.stampUpdated(CurrentUser.actorId(), Instant.now());
        }
    }

    private static IllegalStateException crossTenant(TenantOwned entity, String tenant) {
        return new IllegalStateException("%s of tenant %s can't be written while acting in tenant %s"
                .formatted(entity.getClass().getSimpleName(), entity.getTenantCode(), tenant));
    }
}
