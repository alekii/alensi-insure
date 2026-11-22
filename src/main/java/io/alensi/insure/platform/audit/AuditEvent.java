package io.alensi.insure.platform.audit;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Append-only; the runtime role can't update or delete these rows. */
@Entity
@Table(schema = "audit", name = "audit_event")
class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String tenantCode;
    private Instant occurredAt;
    private String actorType;
    private String actorId;
    private String onBehalfOf;
    private String flowTaskId;
    private String action;
    private String subjectType;
    private String subjectId;
    private String unitCode;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String details;

    private String correlationId;

    protected AuditEvent() {
    }

    AuditEvent(String tenantCode, Instant occurredAt, String actorType, String actorId, String action,
            String subjectType, String subjectId, String unitCode, String details, String correlationId) {
        this.tenantCode = tenantCode;
        this.occurredAt = occurredAt;
        this.actorType = actorType;
        this.actorId = actorId;
        this.action = action;
        this.subjectType = subjectType;
        this.subjectId = subjectId;
        this.unitCode = unitCode;
        this.details = details;
        this.correlationId = correlationId;
    }

    Long getId() {
        return id;
    }

    Instant getOccurredAt() {
        return occurredAt;
    }

    String getActorType() {
        return actorType;
    }

    String getActorId() {
        return actorId;
    }

    String getOnBehalfOf() {
        return onBehalfOf;
    }

    String getFlowTaskId() {
        return flowTaskId;
    }

    String getAction() {
        return action;
    }

    String getSubjectType() {
        return subjectType;
    }

    String getSubjectId() {
        return subjectId;
    }

    String getUnitCode() {
        return unitCode;
    }

    String getDetails() {
        return details;
    }

    String getCorrelationId() {
        return correlationId;
    }
}
