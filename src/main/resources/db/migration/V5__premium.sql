-- Premium schedules are created when a quote is accepted. Alensi Pay moves the money; Insure
-- tracks installments and payment references (ADR-0004).

CREATE SCHEMA premium;
GRANT USAGE ON SCHEMA premium TO insure_app;

CREATE TABLE premium.premium_schedule (
    id           uuid          PRIMARY KEY,
    tenant_code  varchar(16)   NOT NULL,
    policy_id    uuid          NOT NULL,
    unit_code    varchar(64)   NOT NULL,
    frequency    varchar(16)   NOT NULL CHECK (frequency IN ('ANNUAL', 'SEMI_ANNUAL', 'QUARTERLY', 'MONTHLY')),
    total_amount decimal(19,4) NOT NULL CHECK (total_amount >= 0),
    currency     varchar(3)    NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    version      bigint        NOT NULL,
    created_at   timestamptz   NOT NULL,
    updated_at   timestamptz   NOT NULL,
    created_by   uuid          NOT NULL,
    updated_by   uuid          NOT NULL,
    CONSTRAINT premium_schedule_tenant_id UNIQUE (tenant_code, id),
    CONSTRAINT premium_schedule_policy UNIQUE (tenant_code, policy_id),
    CONSTRAINT premium_schedule_policy_fk FOREIGN KEY (tenant_code, policy_id) REFERENCES policy.policy (tenant_code, id)
);

CREATE TABLE premium.installment (
    id              uuid          PRIMARY KEY,
    tenant_code     varchar(16)   NOT NULL,
    schedule_id     uuid          NOT NULL,
    sequence_number int           NOT NULL CHECK (sequence_number > 0),
    due_date        date          NOT NULL,
    amount          decimal(19,4) NOT NULL CHECK (amount >= 0),
    paid_amount     decimal(19,4) NOT NULL CHECK (paid_amount >= 0),
    status          varchar(20)   NOT NULL CHECK (status IN ('SCHEDULED', 'DUE', 'PAYMENT_PENDING', 'PAID', 'OVERDUE',
                        'WAIVED', 'CANCELLED')),
    paid_at         timestamptz,
    CONSTRAINT installment_tenant_id UNIQUE (tenant_code, id),
    CONSTRAINT installment_sequence UNIQUE (schedule_id, sequence_number),
    CONSTRAINT installment_schedule FOREIGN KEY (tenant_code, schedule_id)
        REFERENCES premium.premium_schedule (tenant_code, id)
);

CREATE INDEX installment_status_due ON premium.installment (tenant_code, status, due_date);

CREATE TABLE premium.payment_reference (
    id              uuid          PRIMARY KEY,
    tenant_code     varchar(16)   NOT NULL,
    direction       varchar(16)   NOT NULL CHECK (direction IN ('COLLECTION', 'DISBURSEMENT', 'REFUND')),
    installment_id  uuid,
    settlement_id   uuid,
    adjustment_id   uuid,
    pay_payment_id  varchar(64),
    idempotency_key varchar(80)   NOT NULL,
    amount          decimal(19,4) NOT NULL CHECK (amount > 0),
    currency        varchar(3)    NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    status          varchar(16)   NOT NULL CHECK (status IN ('INITIATED', 'PENDING', 'SUCCEEDED', 'FAILED', 'CANCELLED',
                        'REFUNDED', 'UNKNOWN')),
    failure_reason  varchar(255),
    last_event_at   timestamptz,
    CONSTRAINT payment_reference_pay_id UNIQUE (tenant_code, pay_payment_id),
    CONSTRAINT payment_reference_idempotency UNIQUE (tenant_code, idempotency_key),
    CONSTRAINT payment_reference_installment FOREIGN KEY (tenant_code, installment_id)
        REFERENCES premium.installment (tenant_code, id),
    CONSTRAINT payment_reference_one_source CHECK (
        (installment_id IS NOT NULL)::int + (settlement_id IS NOT NULL)::int + (adjustment_id IS NOT NULL)::int = 1)
);

GRANT SELECT, INSERT, UPDATE ON premium.premium_schedule, premium.installment, premium.payment_reference TO insure_app;

DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['premium_schedule', 'installment', 'payment_reference'] LOOP
        EXECUTE format('ALTER TABLE premium.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE premium.%I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format($p$CREATE POLICY tenant_isolation ON premium.%I
            USING      (tenant_code = current_setting('app.tenant_code', true))
            WITH CHECK (tenant_code = current_setting('app.tenant_code', true))$p$, t);
    END LOOP;
END
$$;
