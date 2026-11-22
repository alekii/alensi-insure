package io.alensi.insure.platform.persistence;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;

/**
 * A mutable aggregate root (data dictionary, "Audit columns"): who created and last changed it and
 * when, stamped on persist and update, and an optimistic-lock {@code version} that is also the
 * API's ETag.
 */
@MappedSuperclass
public abstract class AuditedEntity extends TenantOwned {

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false, updatable = false)
    private UUID createdBy;

    @Column(nullable = false)
    private Instant updatedAt;

    @Column(nullable = false)
    private UUID updatedBy;

    @Version
    private Long version;

    public Instant getCreatedAt() {
        return createdAt;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public UUID getUpdatedBy() {
        return updatedBy;
    }

    /** Null until first persisted; Spring Data reads it to tell new entities from existing ones. */
    public Long getVersion() {
        return version;
    }

    /**
     * Marks the root as changed when only its child collections changed, so the update (and the
     * new version) happens anyway: replacing a draft's coverages must change its ETag.
     */
    protected void markModified() {
        this.updatedAt = Instant.now();
    }

    void stampCreated(UUID actor, Instant now) {
        this.createdAt = now;
        this.createdBy = actor;
        stampUpdated(actor, now);
    }

    void stampUpdated(UUID actor, Instant now) {
        this.updatedAt = now;
        this.updatedBy = actor;
    }
}
