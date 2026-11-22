package io.alensi.insure.platform.audit;

import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.repository.Repository;

interface AuditEventRepository extends Repository<AuditEvent, Long>, JpaSpecificationExecutor<AuditEvent> {

    AuditEvent save(AuditEvent event);
}
