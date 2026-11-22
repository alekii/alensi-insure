package io.alensi.insure.platform.audit;

import java.time.Clock;
import java.util.Map;

import io.alensi.insure.platform.security.CurrentUser;
import io.alensi.insure.platform.web.CorrelationId;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Records who did what to which record, in the caller's transaction, so an action and its audit
 * event commit or roll back together. Details must never contain PII in clear.
 */
@Component
public class AuditLog {

    private final AuditEventRepository events;
    private final JsonMapper json;
    private final Clock clock;

    AuditLog(AuditEventRepository events, JsonMapper json) {
        this.events = events;
        this.json = json;
        this.clock = Clock.systemUTC();
    }

    /**
     * @param unitCode the subject's unit, or the tenant code for tenant-wide subjects (products,
     *                 knowledge), so {@code audit.view} on the tenant root covers them
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String action, String subjectType, Object subjectId, String unitCode, Map<String, ?> details) {
        boolean user = CurrentUser.isSignedIn();
        events.save(new AuditEvent(clock.instant(), user ? "USER" : "SYSTEM",
                user ? CurrentUser.id().toString() : "system", action, subjectType, String.valueOf(subjectId), unitCode,
                details == null || details.isEmpty() ? null : json.writeValueAsString(details),
                CorrelationId.current()));
    }
}
