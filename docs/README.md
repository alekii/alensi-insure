# Alensi Insure Documentation

Architecture and engineering documentation for Alensi Insure.

The implementation has not started yet, so these documents are the **design baseline**: they describe what the first development stages (domain model, policy & coverage, claims) build against. When the implementation and a document disagree, fix one of them in the same pull request — and if the change reverses a recorded decision, add a new ADR rather than editing the old one.

All diagrams are [Mermaid](https://mermaid.js.org/) and render directly on GitHub.

## Contents

| Area | Document | What it covers |
|---|---|---|
| Architecture | [System Context](architecture/system-context.md) | Insure, its users, and the Alensi services around it |
| | [Container Diagram](architecture/container-diagram.md) | Deployable units, data stores, and how they communicate |
| | [Component Diagram](architecture/component-diagram.md) | Modules inside the Insure application and their dependencies |
| Database | [ERD](database/erd.md) | Entity relationships, grouped by module |
| | [Data Dictionary](database/data-dictionary.md) | Every table and column, enums, and conventions |
| API | [API Specification](api/api-spec.md) | REST endpoints, conventions, events published and consumed |
| AI | [Intelligence Architecture](ai/intelligence-architecture.md) | How the intelligence layer is structured and isolated |
| | [RAG Architecture](ai/rag-architecture.md) | Policy knowledge ingestion, pgvector, retrieval |
| | [Tool Calling](ai/tool-calling.md) | Tools exposed to models and how they are secured |
| | [AI Decisioning](ai/ai-decisioning.md) | What AI may and may not decide, validation, audit, evaluation |
| Flows | [Policy Lifecycle](flows/policy-lifecycle.md) | Quote → underwriting → issue → premium → renewal / cancellation |
| | [Claim Lifecycle](flows/claim-lifecycle.md) | Submission → assessment → decision → settlement → closure |
| | [Claim Assessment](flows/claim-assessment.md) | Coverage check, triage, risk, anomalies, and the review package |
| Decisions | [Architecture Decision Records](decisions/README.md) | Why the architecture looks the way it does |

## Responsibilities at a Glance

Alensi Insure is one service in the Alensi platform. It owns the **insurance domain** and nothing else:

| Concern | Owner | Insure's role |
|---|---|---|
| Customers, products, policies, coverage, premiums, claims | **Insure** | System of record |
| Coverage rules, premium calculation, claim state invariants | **Insure** | Deterministic, authoritative |
| AI triage, risk, anomaly detection, document extraction, RAG | **Insure** (intelligence layer) | Advisory only |
| Authentication, users, roles, permissions | [Alensi Identity](https://github.com/alekii/alensi-identity) | Validates tokens, asks Identity for permission checks |
| Workflows, approvals, review queues, escalations, human tasks | [Alensi Flow](https://github.com/alekii/alensi-flow) | Publishes events, exposes commands Flow calls |
| Premium collection, claim payouts, refunds | [Alensi Pay](https://github.com/alekii/alensi-pay) | Requests payments, tracks their status |
| Reconciliation of money movements | [Alensi Recon](https://github.com/alekii/alensi-recon) | None directly — Recon reconciles Pay's records |
| Deployment, telemetry, dashboards | [Alensi Observe](https://github.com/alekii/alensi-observe) | Emits OpenTelemetry signals |

See [ADR-0002](decisions/0002-workflow-orchestration-in-alensi-flow.md), [ADR-0003](decisions/0003-auth-via-alensi-identity.md) and [ADR-0004](decisions/0004-payments-via-alensi-pay.md) for the reasoning.

## Glossary

| Term | Meaning |
|---|---|
| **Product** | A versioned insurance offering (e.g. comprehensive motor cover) defining coverages, eligibility, and rating. |
| **Coverage definition** | A benefit within a product: limit, deductible, waiting period, exclusions, and which incident types it responds to. |
| **Policy** | A contract between the insurer and a customer for a specific product version, period, and set of coverages. |
| **Insured item** | The thing insured under a policy — a vehicle, property, person, or trip. |
| **Premium schedule / installment** | How and when the policy premium is paid. |
| **Claim** | A request for benefits under a policy after an incident. |
| **Claim item** | One claimed loss within a claim, mapped to one policy coverage. |
| **Coverage check** | The deterministic evaluation of whether a claim item is covered, with a rule trace. |
| **AI assessment** | An advisory output of the intelligence layer (triage, risk, anomaly explanation, summary, coverage answer), stored with its evidence. |
| **Claim decision** | The human, authoritative approve / partially approve / reject decision. |
| **Settlement** | The payout for an approved claim, executed through Alensi Pay. |
| **Target** | An Alensi Identity scope (country → region → branch) on which a user is granted roles. Insure records are owned by a branch. |
| **Flow process** | A running Alensi Flow workflow instance (e.g. claim review) that coordinates human tasks around an Insure record. |
