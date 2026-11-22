# ADR-0003: Authentication and Authorization Through Alensi Identity

- **Status:** Accepted
- **Date:** 2026-11-22

## Context

Alensi Identity provides centralised identity on Keycloak (realm `alensi`) and an ACL model: applications register permissions, roles bundle permissions, and users are granted roles on **targets** arranged in a hierarchy, with grants inherited by descendant targets. Each insurer is a **tenant** with its own target subtree, and some permissions carry per-currency **approval limits**. It exposes a permission API for services:

```text
GET /api/users/{user}/applications/{application}/permissions/{permission}/targets/{target}
GET /api/users/{user}/applications/{application}/permissions/{permission}/targets
```

Insurance access is naturally scoped by organisation: an agent works for a team or branch, a claims manager approves for a region up to an amount. Insure needs "can this user do X?", "on which records?" and "up to how much?".

## Decision

- Insure stores **no** users, passwords, roles, or grants.
- Insure is an **OAuth2 resource server** validating Keycloak JWTs locally (issuer `…/realms/alensi`, audience `alensi-insure`).
- Insure is registered as application **`alensi-insure`** in Identity's catalog (`catalog/alensi-insure.json` in alensi-identity), which defines its permissions, default roles and limits.
- Every record carries `tenant_code` and `unit_code`, the Identity **target** code of the owning team or branch in the hierarchy `company → country → region → branch → team`.
- Insure uses `alensi-identity-spring-boot-starter`: record checks and list scopes come from the caller's permission map, cached in-process per user and tenant for 30 seconds; limited permissions are checked live with the amount.
- If Identity is unreachable, requests **fail closed** (`503`).
- The per-endpoint contract is [Authorization and Data Scoping](../api/authorization.md).
- Service-to-service calls use client credentials (`alensi-insure`, `alensi-flow` clients).
- Flow acts on behalf of users via `X-Acting-User`, accepted only from callers holding `workflows.act`; both identities are checked and audited.

## Consequences

- Role design and grant management happen once, in Identity, for the whole platform.
- Revoking a grant takes effect within the cache TTL (≤ 30 s). Approval limits take effect immediately.
- Insure adds latency for uncached permission checks; the cache and batch target lookups keep it low.
- `X-Acting-User` means Insure trusts Flow to assert who completed a task. This is mitigated by restricting the header to one service permission and by re-checking the acting user's own permissions, but it is weaker than a user-bound token.

## Follow-up

Replace `X-Acting-User` with **OAuth2 Token Exchange (RFC 8693)**: Flow exchanges its token for one representing the acting user with audience `alensi-insure`, and Insure stops accepting the header. This requires enabling token exchange in Keycloak and support in Flow.

## Alternatives Considered

- **Keycloak roles in the JWT only.** Rejected: cannot express branch-scoped grants without token bloat, and duplicates the ACL model Identity already provides.
- **Local permission tables in Insure.** Rejected: fragments access management across services.
- **Embedding Identity's permission evaluator as a library.** Possible later optimisation; the HTTP API keeps Identity the single source of truth for now.
