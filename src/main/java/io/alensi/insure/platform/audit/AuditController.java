package io.alensi.insure.platform.audit;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.alensi.identity.client.AlensiAccess;
import io.alensi.identity.client.TargetScope;
import io.alensi.insure.platform.security.CurrentTenant;
import io.alensi.insure.platform.security.InsurePermissions;
import io.alensi.insure.platform.web.PageResponse;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/** {@code GET /audit-events}: filtered to the units where the caller holds {@code audit.view}. */
@RestController
class AuditController {

    private final AuditEventRepository events;
    private final AlensiAccess access;
    private final JsonMapper json;

    AuditController(AuditEventRepository events, AlensiAccess access, JsonMapper json) {
        this.events = events;
        this.access = access;
        this.json = json;
    }

    @GetMapping("/api/v1/audit-events")
    @Transactional(readOnly = true)
    PageResponse<AuditEventView> search(@RequestParam(required = false) String subjectType,
            @RequestParam(required = false) String subjectId, @RequestParam(required = false) String actorId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            Pageable pageable) {
        TargetScope scope = access.scope(InsurePermissions.AUDIT_VIEW);
        Pageable newestFirst = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                Sort.by(Sort.Direction.DESC, "occurredAt", "id"));
        if (scope.isEmpty()) {
            return PageResponse.of(Page.<AuditEvent>empty(newestFirst), this::view);
        }
        String tenant = CurrentTenant.require();
        Specification<AuditEvent> filter = (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            where.add(cb.equal(root.get("tenantCode"), tenant));
            if (!scope.all()) {
                where.add(root.get("unitCode").in(scope.targets()));
            }
            if (subjectType != null) {
                where.add(cb.equal(root.get("subjectType"), subjectType));
            }
            if (subjectId != null) {
                where.add(cb.equal(root.get("subjectId"), subjectId));
            }
            if (actorId != null) {
                where.add(cb.equal(root.get("actorId"), actorId));
            }
            if (action != null) {
                where.add(cb.equal(root.get("action"), action));
            }
            if (from != null) {
                where.add(cb.greaterThanOrEqualTo(root.get("occurredAt"), from));
            }
            if (to != null) {
                where.add(cb.lessThan(root.get("occurredAt"), to));
            }
            return cb.and(where.toArray(Predicate[]::new));
        };
        return PageResponse.of(events.findAll(filter, newestFirst), this::view);
    }

    @SuppressWarnings("unchecked")
    private AuditEventView view(AuditEvent event) {
        Map<String, Object> details = event.getDetails() == null ? null : json.readValue(event.getDetails(), Map.class);
        return new AuditEventView(event.getId(), event.getOccurredAt(), event.getActorType(), event.getActorId(),
                event.getOnBehalfOf(), event.getFlowTaskId(), event.getAction(), event.getSubjectType(),
                event.getSubjectId(), event.getUnitCode(), event.getCorrelationId(), details);
    }

    record AuditEventView(Long id, Instant occurredAt, String actorType, String actorId, String onBehalfOf,
            String flowTaskId, String action, String subjectType, String subjectId, String unitCode,
            String correlationId, Map<String, Object> details) {
    }
}
