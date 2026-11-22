-- Database roles (ADR-0009). Runs once, as the superuser, when the database is created.
-- Development passwords only; elsewhere the roles are created by infrastructure with real secrets.
--
--   insure_owner  owns schemas and tables; Flyway only. BYPASSRLS.
--   insure_app    API and worker at runtime. Not an owner, no BYPASSRLS: every policy applies.
--
-- insure_relay (outbox) and insure_admin (break-glass) arrive with the features that need them.
CREATE ROLE insure_owner LOGIN PASSWORD 'insure_owner' BYPASSRLS;
CREATE ROLE insure_app LOGIN PASSWORD 'insure_app' NOBYPASSRLS;

DO $$
BEGIN
    EXECUTE format('GRANT CONNECT, CREATE ON DATABASE %I TO insure_owner', current_database());
    EXECUTE format('GRANT CONNECT ON DATABASE %I TO insure_app', current_database());
END
$$;
