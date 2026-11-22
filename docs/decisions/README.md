# Architecture Decision Records

Each ADR records one significant decision: the context, what was decided, and the consequences. ADRs are immutable once accepted — to change a decision, write a new ADR that supersedes the old one and update the old one's status.

| ADR | Title | Status |
|---|---|---|
| [0001](0001-modular-monolith.md) | Modular monolith with a separate worker profile | Accepted |
| [0002](0002-workflow-orchestration-in-alensi-flow.md) | Workflows and approvals are orchestrated by Alensi Flow | Accepted |
| [0003](0003-auth-via-alensi-identity.md) | Authentication and authorization through Alensi Identity | Accepted |
| [0004](0004-payments-via-alensi-pay.md) | Payments executed by Alensi Pay; Insure tracks status | Accepted |
| [0005](0005-pgvector-vector-store.md) | PostgreSQL with pgvector as the vector store | Accepted |
| [0006](0006-ai-advisory-only.md) | AI outputs are advisory, structured, and recorded | Accepted |
| [0007](0007-transactional-outbox.md) | Domain events published through a transactional outbox | Accepted |
| [0008](0008-document-object-storage.md) | Documents in S3-compatible object storage | Accepted |
| [0009](0009-tenant-isolation-row-level-security.md) | Tenant isolation enforced in the application and by PostgreSQL row-level security | Accepted |

## Template

```markdown
# ADR-NNNN: Title

- **Status:** Proposed | Accepted | Superseded by ADR-NNNN
- **Date:** YYYY-MM-DD

## Context
What forces are at play and why a decision is needed.

## Decision
What we will do.

## Consequences
What becomes easier, what becomes harder, and what we must now do.

## Alternatives Considered
Other options and why they were not chosen.
```
