# ADR-0002: Workflows and Approvals Are Orchestrated by Alensi Flow

- **Status:** Accepted
- **Date:** 2026-11-22

## Context

The original Insure README lists "configurable claim workflows, role-based approval, manual review queues, escalation" as Insure capabilities. Alensi Flow is a configurable orchestration platform providing exactly these — state machines, maker-checker, approval chains, task management, notifications, timers, and audit — and its README states: *"The insurance domain remains in Alensi Insure. Flow owns the process orchestration."*

Building approval chains and queues in Insure would duplicate Flow and make insurance processes unconfigurable without code changes.

## Decision

**Alensi Flow owns all human workflow**: review queues, task assignment, approval chains and thresholds, escalations, SLA timers, reminders, and notifications — for underwriting referrals, claim assessment and approval, settlement release, refund approval, anomaly investigation, and extraction verification.

**Insure owns the domain**: record state machines (which transitions are legal), business rules, and invariants that must hold regardless of process configuration:

- The decider is authorised for the claim's `minimum_review_level`.
- The decider is not the assessor (four-eyes).
- Approved amounts never exceed payable amounts or remaining limits.

Integration:

- Insure publishes domain events (`insure.claim.submitted`, `insure.claim.assessed`, …) that start and advance Flow processes.
- Flow calls Insure's command endpoints with its service identity and the acting user (`X-Acting-User`), and Insure re-validates everything.
- Insure stores Flow's process and task IDs for traceability but never reads Flow's state to make decisions.

## Consequences

- Approval chains and thresholds change through Flow configuration, not Insure releases.
- Insure has no task or queue tables, and its API has no "my tasks" endpoints — the reviewer UI combines Flow's task list with Insure's record views.
- A misconfigured Flow process cannot produce an invalid insurance outcome, because Insure enforces invariants on every command.
- Claim processing depends on Flow being available for human steps. Insure itself remains consistent if Flow is down; work resumes from events when it returns.
- Routing signals from the intelligence layer (`minimumReviewLevel`) cross the boundary as event data; Flow may route higher but Insure enforces the floor.

## Alternatives Considered

- **Workflow inside Insure.** Rejected: duplicates Flow, couples process changes to Insure releases.
- **Flow owns claim state.** Rejected: the claim's state is a fact about the insurance domain needed for coverage, limits, and audit; it must live with the rules that govern it.
- **Flow calls Insure with the user's own token (token exchange).** Preferred long-term; deferred because it requires Keycloak token exchange configuration across the platform. Tracked in [ADR-0003](0003-auth-via-alensi-identity.md).
