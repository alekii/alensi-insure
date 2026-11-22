-- Products are tenant-wide (sold by every branch). A version is DRAFT while it is configured,
-- immutable once PUBLISHED, and WITHDRAWN to stop new sales. Policies reference the exact version.

-- Trusted extension: the owner role may create it. Needed for the non-overlap constraint below.
CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE SCHEMA product;
GRANT USAGE ON SCHEMA product TO insure_app;

CREATE TABLE product.product (
    id               uuid         PRIMARY KEY,
    tenant_code      varchar(16)  NOT NULL,
    code             varchar(32)  NOT NULL,
    name             varchar(255) NOT NULL,
    line_of_business varchar(16)  NOT NULL CHECK (line_of_business IN ('MOTOR', 'HEALTH', 'PROPERTY', 'LIFE', 'TRAVEL')),
    currency         varchar(3)   NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    status           varchar(16)  NOT NULL CHECK (status IN ('ACTIVE', 'RETIRED')),
    version          bigint       NOT NULL,
    created_at       timestamptz  NOT NULL,
    updated_at       timestamptz  NOT NULL,
    created_by       uuid         NOT NULL,
    updated_by       uuid         NOT NULL,
    CONSTRAINT product_tenant_id UNIQUE (tenant_code, id),
    CONSTRAINT product_code_unique UNIQUE (tenant_code, code)
);

CREATE TABLE product.product_version (
    id                  uuid          PRIMARY KEY,
    tenant_code         varchar(16)   NOT NULL,
    product_id          uuid          NOT NULL,
    version_number      int           NOT NULL CHECK (version_number > 0),
    status              varchar(16)   NOT NULL CHECK (status IN ('DRAFT', 'PUBLISHED', 'WITHDRAWN')),
    currency            varchar(3)    NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    term_months         int           NOT NULL CHECK (term_months BETWEEN 1 AND 60),
    grace_period_days   int           NOT NULL CHECK (grace_period_days BETWEEN 0 AND 365),
    minimum_premium     decimal(19,4) NOT NULL CHECK (minimum_premium >= 0),
    effective_from      date,
    effective_to        date,
    wording_document_id uuid,
    published_at        timestamptz,
    withdrawn_at        timestamptz,
    version             bigint        NOT NULL,
    created_at          timestamptz   NOT NULL,
    updated_at          timestamptz   NOT NULL,
    created_by          uuid          NOT NULL,
    updated_by          uuid          NOT NULL,
    CONSTRAINT product_version_tenant_id UNIQUE (tenant_code, id),
    CONSTRAINT product_version_number UNIQUE (tenant_code, product_id, version_number),
    CONSTRAINT product_version_product FOREIGN KEY (tenant_code, product_id) REFERENCES product.product (tenant_code, id),
    CONSTRAINT product_version_dates CHECK (effective_to IS NULL OR effective_to >= effective_from),
    CONSTRAINT product_version_published_dated CHECK (status = 'DRAFT' OR effective_from IS NOT NULL),
    -- At most one published version of a product covers any date. Deferred, so publishing a new
    -- version and closing the previous one can happen in one transaction in any order.
    CONSTRAINT product_version_no_overlap EXCLUDE USING gist (
        tenant_code WITH =, product_id WITH =, daterange(effective_from, effective_to, '[]') WITH &&)
        WHERE (status = 'PUBLISHED') DEFERRABLE INITIALLY DEFERRED
);

-- One draft at a time per product.
CREATE UNIQUE INDEX product_version_one_draft ON product.product_version (tenant_code, product_id) WHERE status = 'DRAFT';

CREATE TABLE product.coverage_definition (
    id                  uuid          PRIMARY KEY,
    tenant_code         varchar(16)   NOT NULL,
    product_version_id  uuid          NOT NULL,
    position            int           NOT NULL,
    code                varchar(32)   NOT NULL,
    name                varchar(255)  NOT NULL,
    description         text,
    mandatory           boolean       NOT NULL,
    incident_types      text[]        NOT NULL,
    limit_type          varchar(16)   NOT NULL CHECK (limit_type IN ('FIXED', 'SUM_INSURED')),
    limit_amount        decimal(19,4) CHECK (limit_amount > 0),
    limit_basis         varchar(16)   NOT NULL CHECK (limit_basis IN ('PER_EVENT', 'AGGREGATE')),
    deductible_type     varchar(16)   NOT NULL CHECK (deductible_type IN ('FIXED', 'PERCENTAGE')),
    deductible_value    decimal(19,4) NOT NULL CHECK (deductible_value >= 0),
    deductible_minimum  decimal(19,4) CHECK (deductible_minimum >= 0),
    waiting_period_days int           NOT NULL CHECK (waiting_period_days >= 0),
    CONSTRAINT coverage_definition_tenant_id UNIQUE (tenant_code, id),
    CONSTRAINT coverage_definition_code UNIQUE (product_version_id, code) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT coverage_definition_version FOREIGN KEY (tenant_code, product_version_id)
        REFERENCES product.product_version (tenant_code, id) ON DELETE CASCADE,
    CONSTRAINT coverage_definition_fixed_limit CHECK (limit_type <> 'FIXED' OR limit_amount IS NOT NULL),
    CONSTRAINT coverage_definition_percentage CHECK (deductible_type <> 'PERCENTAGE' OR deductible_value <= 100)
);

-- Applies to the listed coverages, or to all of the version's coverages when none are listed.
CREATE TABLE product.coverage_exclusion (
    id                 uuid         PRIMARY KEY,
    tenant_code        varchar(16)  NOT NULL,
    product_version_id uuid         NOT NULL,
    position           int          NOT NULL,
    code               varchar(32)  NOT NULL,
    description        text         NOT NULL,
    clause_reference   varchar(64),
    coverage_codes     text[]       NOT NULL,
    machine_rule       jsonb,
    CONSTRAINT coverage_exclusion_code UNIQUE (product_version_id, code) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT coverage_exclusion_version FOREIGN KEY (tenant_code, product_version_id)
        REFERENCES product.product_version (tenant_code, id) ON DELETE CASCADE
);

CREATE TABLE product.eligibility_rule (
    id                 uuid         PRIMARY KEY,
    tenant_code        varchar(16)  NOT NULL,
    product_version_id uuid         NOT NULL,
    position           int          NOT NULL,
    code               varchar(32)  NOT NULL,
    attribute          varchar(64)  NOT NULL,
    operator           varchar(16)  NOT NULL CHECK (operator IN ('EQ', 'NE', 'GT', 'GTE', 'LT', 'LTE', 'IN', 'NOT_IN', 'BETWEEN')),
    value              jsonb        NOT NULL,
    outcome            varchar(16)  NOT NULL CHECK (outcome IN ('DECLINE', 'REFER')),
    message            varchar(255) NOT NULL,
    CONSTRAINT eligibility_rule_code UNIQUE (product_version_id, code) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT eligibility_rule_version FOREIGN KEY (tenant_code, product_version_id)
        REFERENCES product.product_version (tenant_code, id) ON DELETE CASCADE
);

-- A coverage's annual premium before rating factors: a fixed amount, or a percentage of the sum insured.
CREATE TABLE product.base_rate (
    id                 uuid          PRIMARY KEY,
    tenant_code        varchar(16)   NOT NULL,
    product_version_id uuid          NOT NULL,
    coverage_code      varchar(32)   NOT NULL,
    base_premium       decimal(19,4) CHECK (base_premium >= 0),
    rate_percent       decimal(9,6)  CHECK (rate_percent > 0 AND rate_percent <= 100),
    CONSTRAINT base_rate_coverage UNIQUE (product_version_id, coverage_code) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT base_rate_version FOREIGN KEY (tenant_code, product_version_id)
        REFERENCES product.product_version (tenant_code, id) ON DELETE CASCADE,
    CONSTRAINT base_rate_one_basis CHECK ((base_premium IS NULL) <> (rate_percent IS NULL))
);

-- One row per band; a factor's matching band multiplies the base premium. Factors multiply together.
CREATE TABLE product.rating_factor (
    id                 uuid         PRIMARY KEY,
    tenant_code        varchar(16)  NOT NULL,
    product_version_id uuid         NOT NULL,
    position           int          NOT NULL,
    coverage_code      varchar(32),
    attribute          varchar(64)  NOT NULL,
    band               jsonb        NOT NULL,
    multiplier         decimal(8,4) NOT NULL CHECK (multiplier > 0),
    CONSTRAINT rating_factor_version FOREIGN KEY (tenant_code, product_version_id)
        REFERENCES product.product_version (tenant_code, id) ON DELETE CASCADE
);

CREATE INDEX product_version_product_status ON product.product_version (tenant_code, product_id, status);
CREATE INDEX coverage_definition_version ON product.coverage_definition (tenant_code, product_version_id);
CREATE INDEX coverage_exclusion_version ON product.coverage_exclusion (tenant_code, product_version_id);
CREATE INDEX eligibility_rule_version ON product.eligibility_rule (tenant_code, product_version_id);
CREATE INDEX base_rate_version ON product.base_rate (tenant_code, product_version_id);
CREATE INDEX rating_factor_version ON product.rating_factor (tenant_code, product_version_id);

GRANT SELECT, INSERT, UPDATE ON product.product, product.product_version TO insure_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON product.coverage_definition, product.coverage_exclusion,
    product.eligibility_rule, product.base_rate, product.rating_factor TO insure_app;

DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['product', 'product_version', 'coverage_definition', 'coverage_exclusion',
                             'eligibility_rule', 'base_rate', 'rating_factor'] LOOP
        EXECUTE format('ALTER TABLE product.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE product.%I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format($p$CREATE POLICY tenant_isolation ON product.%I
            USING      (tenant_code = current_setting('app.tenant_code', true))
            WITH CHECK (tenant_code = current_setting('app.tenant_code', true))$p$, t);
    END LOOP;
END
$$;
