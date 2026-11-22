-- Conventions for every tenant table (ADR-0009, data dictionary):
--   tenant_code leads every index; RLS enabled and forced with the tenant_isolation policy;
--   insure_app gets only the privileges it needs.

CREATE SCHEMA audit;
GRANT USAGE ON SCHEMA audit TO insure_app;

-- Append-only: insure_app may insert and read, never update or delete.
CREATE TABLE audit.audit_event (
    id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_code    varchar(16)  NOT NULL,
    occurred_at    timestamptz  NOT NULL,
    actor_type     varchar(16)  NOT NULL CHECK (actor_type IN ('USER', 'SERVICE', 'SYSTEM', 'AI')),
    actor_id       varchar(128) NOT NULL,
    on_behalf_of   varchar(128),
    flow_task_id   varchar(64),
    action         varchar(64)  NOT NULL,
    subject_type   varchar(32)  NOT NULL,
    subject_id     varchar(64)  NOT NULL,
    unit_code      varchar(64),
    details        jsonb,
    correlation_id varchar(64)  NOT NULL
);

CREATE INDEX audit_event_subject ON audit.audit_event (tenant_code, subject_type, subject_id, occurred_at);
CREATE INDEX audit_event_unit ON audit.audit_event (tenant_code, unit_code, occurred_at);

GRANT SELECT, INSERT ON audit.audit_event TO insure_app;

ALTER TABLE audit.audit_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit.audit_event FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON audit.audit_event
    USING      (tenant_code = current_setting('app.tenant_code', true))
    WITH CHECK (tenant_code = current_setting('app.tenant_code', true));
