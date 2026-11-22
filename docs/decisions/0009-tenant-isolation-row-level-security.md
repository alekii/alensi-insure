# ADR-0009: Tenant Isolation Enforced Twice, in the Application and by PostgreSQL Row-Level Security

- **Status:** Accepted
- **Date:** 2026-11-22

## Context

Alensi Insure is shared by several insurers (tenants), such as Jubilee and Madison, in one database. Every request acts in exactly one tenant, resolved by Alensi Identity ([ADR-0003](0003-auth-via-alensi-identity.md)). Every tenant table has a `tenant_code`.

If isolation depends only on each query remembering `WHERE tenant_code = ?`, a single forgotten filter leaks another insurer's customers, policies, or claims, including medical and identity documents. Code review and tests reduce that risk but can't remove it, especially in native queries, reporting queries, and vector search.

## Decision

Tenant isolation is enforced by **two independent barriers**:

1. **Application.** Every query filters on the request's tenant, and Identity grants only targets inside the tenant's subtree.
2. **Database.** PostgreSQL row-level security on every table with a `tenant_code` column:

   ```sql
   ALTER TABLE claims.claim ENABLE ROW LEVEL SECURITY;
   ALTER TABLE claims.claim FORCE ROW LEVEL SECURITY;
   CREATE POLICY tenant_isolation ON claims.claim
     USING      (tenant_code = current_setting('app.tenant_code', true))
     WITH CHECK (tenant_code = current_setting('app.tenant_code', true));
   ```

Supporting rules:

- **Per transaction:** a transaction-begin hook in `platform.security` runs `SELECT set_config('app.tenant_code', :tenant, true)` with the tenant from `alensi-identity-spring-boot-starter`. The setting is transaction-local, so it can't leak across pooled connections. It's also bound as a parameter, never concatenated into SQL.
- **Fail closed:** without a tenant, `current_setting(..., true)` is `NULL`. The transaction then sees no rows and can write none.
- **Roles:** the runtime role `insure_app` doesn't own the tables and has no `BYPASSRLS`. Only the migration role `insure_owner` and the audited break-glass role `insure_admin` bypass RLS.
- **Paths without a request:** event-driven jobs take the tenant from the event's `tenantCode`, and Pay events from `metadata.tenantCode`, which Pay echoes back. Schedulers process one tenant per transaction.
- **Exception:** `messaging.outbox_event` and `messaging.processed_message` have no RLS, so the relay can publish for all tenants. They hold no PII.
- **Composite foreign keys** (`tenant_code`, `id`) between tenant tables.
- **CI:** tests fail if a tenant table lacks `relrowsecurity` or `relforcerowsecurity`, or if a repository running as one tenant can read another tenant's seeded rows.

The full contract is in [Authorization and Data Scoping](../api/authorization.md#row-level-security).

## Consequences

- A missed application filter returns the current tenant's rows, not another insurer's. Cross-tenant inserts and updates are rejected by the database.
- Every transaction must set the tenant. Code that forgets sees an empty database, which tests catch quickly, rather than silently seeing everything.
- `tenant_code` leads every index, so the policy predicate costs little.
- pgvector HNSW search applies the policy after the index scan and may return fewer than `k` results. Retrieval enables `hnsw.iterative_scan`.
- Migrations, admin fixes, and cross-tenant reporting need an explicit bypass role. Cross-tenant operations become deliberate and visible.
- Alensi Pay must echo `metadata.tenantCode` on its events ([API Specification](../api/api-spec.md#alensi-pay-contract)).
- This fits Insure because every request acts in one tenant. Alensi Identity is cross-tenant by design, so whether and where it uses RLS is its own decision.

## Alternatives Considered

- **Application filtering only.** Rejected: one mistake leaks regulated data, and nothing would detect it.
- **Database or schema per tenant.** Strongest isolation, but migrations, connection pools, and pgvector indexes multiply with each insurer. It stays available for a tenant whose regulator demands it, just as Identity keeps realm-per-tenant open.
- **Hibernate filters or a tenant discriminator.** Still enforced in the application, and doesn't cover native SQL or vector queries. It could be added as a convenience, but it isn't a second barrier.
- **`SET LOCAL app.tenant_code = '…'`.** Same effect, but it can't take a bind parameter. `set_config(..., true)` is used instead.
