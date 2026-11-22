CREATE SCHEMA customer;
GRANT USAGE ON SCHEMA customer TO insure_app;

CREATE SEQUENCE customer.customer_number_seq;
GRANT USAGE ON SEQUENCE customer.customer_number_seq TO insure_app;

CREATE TABLE customer.customer (
    id                    uuid         PRIMARY KEY,
    tenant_code           varchar(16)  NOT NULL,
    customer_number       varchar(20)  NOT NULL,
    customer_type         varchar(16)  NOT NULL CHECK (customer_type IN ('INDIVIDUAL', 'ORGANIZATION')),
    first_name            varchar(100),
    last_name             varchar(100),
    organization_name     varchar(255),
    date_of_birth         date,
    national_id_encrypted bytea,
    national_id_hash      varchar(64),
    email                 varchar(320),
    phone                 varchar(20),
    address_line1         varchar(255),
    city                  varchar(100),
    country_code          varchar(2)   CHECK (country_code ~ '^[A-Z]{2}$'),
    unit_code             varchar(64)  NOT NULL,
    status                varchar(16)  NOT NULL CHECK (status IN ('ACTIVE', 'INACTIVE')),
    version               bigint       NOT NULL,
    created_at            timestamptz  NOT NULL,
    updated_at            timestamptz  NOT NULL,
    created_by            uuid         NOT NULL,
    updated_by            uuid         NOT NULL,
    -- Target of composite foreign keys (tenant_code, id) from other tenant tables.
    CONSTRAINT customer_tenant_id UNIQUE (tenant_code, id),
    CONSTRAINT customer_number_unique UNIQUE (tenant_code, customer_number),
    CONSTRAINT customer_names CHECK (
        (customer_type = 'INDIVIDUAL' AND first_name IS NOT NULL AND last_name IS NOT NULL)
        OR (customer_type = 'ORGANIZATION' AND organization_name IS NOT NULL))
);

CREATE UNIQUE INDEX customer_national_id ON customer.customer (tenant_code, national_id_hash)
    WHERE national_id_hash IS NOT NULL;
CREATE INDEX customer_unit_status ON customer.customer (tenant_code, unit_code, status);
CREATE INDEX customer_name ON customer.customer (tenant_code, lower(last_name), lower(first_name));

GRANT SELECT, INSERT, UPDATE ON customer.customer TO insure_app;

ALTER TABLE customer.customer ENABLE ROW LEVEL SECURITY;
ALTER TABLE customer.customer FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON customer.customer
    USING      (tenant_code = current_setting('app.tenant_code', true))
    WITH CHECK (tenant_code = current_setting('app.tenant_code', true));
