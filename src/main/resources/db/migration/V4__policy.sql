-- A policy is born as a quote (QUOTED, REFERRED or DECLINED), accepted into PENDING_PAYMENT and
-- issued (ACTIVE) when its first installment is paid. Its terms are frozen at quote time: product
-- version, coverages with their limits, deductibles and premiums.

CREATE SCHEMA policy;
GRANT USAGE ON SCHEMA policy TO insure_app;

CREATE SEQUENCE policy.policy_number_seq;
GRANT USAGE ON SEQUENCE policy.policy_number_seq TO insure_app;

CREATE TABLE policy.policy (
    id                     uuid          PRIMARY KEY,
    tenant_code            varchar(16)   NOT NULL,
    policy_number          varchar(24)   NOT NULL,
    customer_id            uuid          NOT NULL,
    product_id             uuid          NOT NULL,
    product_version_id     uuid          NOT NULL,
    product_code           varchar(32)   NOT NULL,
    product_version_number int           NOT NULL,
    status                 varchar(20)   NOT NULL CHECK (status IN ('QUOTED', 'REFERRED', 'DECLINED', 'QUOTE_EXPIRED',
                               'PENDING_PAYMENT', 'ACTIVE', 'LAPSED', 'CANCELLED', 'EXPIRED')),
    effective_date         date          NOT NULL,
    expiry_date            date          NOT NULL,
    total_premium          decimal(19,4) NOT NULL CHECK (total_premium >= 0),
    total_sum_insured      decimal(19,4) CHECK (total_sum_insured >= 0),
    currency               varchar(3)    NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    payment_frequency      varchar(16)   NOT NULL CHECK (payment_frequency IN ('ANNUAL', 'SEMI_ANNUAL', 'QUARTERLY', 'MONTHLY')),
    unit_code              varchar(64)   NOT NULL,
    renewed_from_policy_id uuid,
    quote_expires_at       timestamptz,
    accepted_at            timestamptz,
    issued_at              timestamptz,
    cancelled_at           timestamptz,
    cancellation_reason    varchar(32),
    flow_process_id        varchar(64),
    eligibility            jsonb         NOT NULL,
    rating                 jsonb         NOT NULL,
    version                bigint        NOT NULL,
    created_at             timestamptz   NOT NULL,
    updated_at             timestamptz   NOT NULL,
    created_by             uuid          NOT NULL,
    updated_by             uuid          NOT NULL,
    CONSTRAINT policy_tenant_id UNIQUE (tenant_code, id),
    CONSTRAINT policy_number_unique UNIQUE (tenant_code, policy_number),
    CONSTRAINT policy_dates CHECK (expiry_date >= effective_date),
    CONSTRAINT policy_customer FOREIGN KEY (tenant_code, customer_id) REFERENCES customer.customer (tenant_code, id),
    CONSTRAINT policy_product_version FOREIGN KEY (tenant_code, product_version_id)
        REFERENCES product.product_version (tenant_code, id),
    CONSTRAINT policy_renewed_from FOREIGN KEY (tenant_code, renewed_from_policy_id) REFERENCES policy.policy (tenant_code, id)
);

CREATE INDEX policy_unit_status ON policy.policy (tenant_code, unit_code, status);
CREATE INDEX policy_customer ON policy.policy (tenant_code, customer_id);
CREATE INDEX policy_status_expiry ON policy.policy (tenant_code, status, expiry_date);

CREATE TABLE policy.insured_item (
    id          uuid          PRIMARY KEY,
    tenant_code varchar(16)   NOT NULL,
    policy_id   uuid          NOT NULL,
    position    int           NOT NULL,
    item_type   varchar(16)   NOT NULL CHECK (item_type IN ('VEHICLE', 'PROPERTY', 'PERSON', 'TRIP')),
    description varchar(255)  NOT NULL,
    sum_insured decimal(19,4) CHECK (sum_insured >= 0),
    attributes  jsonb         NOT NULL,
    CONSTRAINT insured_item_policy FOREIGN KEY (tenant_code, policy_id) REFERENCES policy.policy (tenant_code, id)
);

CREATE TABLE policy.policy_coverage (
    id                     uuid          PRIMARY KEY,
    tenant_code            varchar(16)   NOT NULL,
    policy_id              uuid          NOT NULL,
    coverage_definition_id uuid          NOT NULL,
    position               int           NOT NULL,
    code                   varchar(32)   NOT NULL,
    name                   varchar(255)  NOT NULL,
    limit_amount           decimal(19,4) NOT NULL CHECK (limit_amount >= 0),
    limit_basis            varchar(16)   NOT NULL CHECK (limit_basis IN ('PER_EVENT', 'AGGREGATE')),
    deductible_type        varchar(16)   NOT NULL CHECK (deductible_type IN ('FIXED', 'PERCENTAGE')),
    deductible_value       decimal(19,4) NOT NULL CHECK (deductible_value >= 0),
    deductible_minimum     decimal(19,4) CHECK (deductible_minimum >= 0),
    waiting_period_days    int           NOT NULL CHECK (waiting_period_days >= 0),
    premium_amount         decimal(19,4) NOT NULL CHECK (premium_amount >= 0),
    consumed_amount        decimal(19,4) NOT NULL DEFAULT 0 CHECK (consumed_amount >= 0),
    reserved_amount        decimal(19,4) NOT NULL DEFAULT 0 CHECK (reserved_amount >= 0),
    CONSTRAINT policy_coverage_code UNIQUE (policy_id, code),
    CONSTRAINT policy_coverage_policy FOREIGN KEY (tenant_code, policy_id) REFERENCES policy.policy (tenant_code, id),
    -- Published coverage definitions are never deleted; this keeps it that way for sold ones.
    CONSTRAINT policy_coverage_definition FOREIGN KEY (tenant_code, coverage_definition_id)
        REFERENCES product.coverage_definition (tenant_code, id),
    CONSTRAINT policy_coverage_aggregate CHECK (
        limit_basis <> 'AGGREGATE' OR consumed_amount + reserved_amount <= limit_amount)
);

CREATE INDEX insured_item_policy ON policy.insured_item (tenant_code, policy_id);
CREATE INDEX policy_coverage_policy ON policy.policy_coverage (tenant_code, policy_id);

GRANT SELECT, INSERT, UPDATE ON policy.policy, policy.insured_item, policy.policy_coverage TO insure_app;

DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['policy', 'insured_item', 'policy_coverage'] LOOP
        EXECUTE format('ALTER TABLE policy.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE policy.%I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format($p$CREATE POLICY tenant_isolation ON policy.%I
            USING      (tenant_code = current_setting('app.tenant_code', true))
            WITH CHECK (tenant_code = current_setting('app.tenant_code', true))$p$, t);
    END LOOP;
END
$$;
