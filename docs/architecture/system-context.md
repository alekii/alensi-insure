# System Context

This is the C4 level-1 view: Alensi Insure as a single box, the people who use it, and the systems it depends on.

```mermaid
flowchart TB
    subgraph people [People]
        direction LR
        agent["Customer Service Agent<br/><i>Registers customers, quotes and issues policies, lodges claims</i>"]
        uw["Underwriter<br/><i>Reviews referred applications</i>"]
        adj["Claims Handler / Adjuster<br/><i>Assesses claims, requests information</i>"]
        mgr["Claims Manager<br/><i>Approves or rejects claims</i>"]
        inv["Fraud Investigator<br/><i>Investigates anomaly flags</i>"]
        pm["Product Manager<br/><i>Configures products and policy wordings</i>"]
        aud["Auditor<br/><i>Reviews decisions and AI usage</i>"]
    end

    insure["<b>Alensi Insure</b><br/>Policy & claims management with<br/>AI-assisted decision support"]

    subgraph alensi [Alensi Platform]
        direction LR
        identity["<b>Alensi Identity</b><br/>Keycloak-backed authentication,<br/>roles, permissions, targets"]
        flow["<b>Alensi Flow</b><br/>Workflows, approvals,<br/>review queues, escalations"]
        pay["<b>Alensi Pay</b><br/>Premium collection,<br/>claim payouts, refunds"]
        recon["<b>Alensi Recon</b><br/>Reconciles Pay transactions"]
        observe["<b>Alensi Observe</b><br/>Metrics, logs, traces, alerts"]
    end

    llm["<b>AI Model Provider</b><br/>Chat + embedding models<br/>via Spring AI"]

    people -- "HTTPS / JSON" --> insure
    people -. "complete review & approval tasks" .-> flow

    insure -- "validates JWTs, checks permissions" --> identity
    insure -- "publishes domain events" --> flow
    flow -- "calls commands: request info,<br/>record decisions, start settlement" --> insure
    insure -- "requests collections & payouts" --> pay
    pay -- "payment status events" --> insure
    pay -- "transactions" --> recon
    insure -- "pseudonymised prompts,<br/>document text, embeddings" --> llm
    insure -- "OpenTelemetry" --> observe
```

## Users

Insure is a **staff-facing** system in its first version. Every user is an Alensi Identity user with roles granted on one or more branch targets.

| Actor | Main activities | Typical permissions |
|---|---|---|
| Customer service agent | Register customers, quote, issue and cancel policies, lodge claims, upload documents | `insure.customers.write`, `insure.policies.write`, `insure.claims.submit` |
| Underwriter | Review referred applications, accept or decline risk | `insure.underwriting.decide` |
| Claims handler / adjuster | Assess claims, record assessed amounts, request information, read AI outputs | `insure.claims.assess`, `insure.ai.read` |
| Claims manager | Approve, partially approve, or reject claims | `insure.claims.decide` |
| Finance | Premium adjustments, settlement oversight | `insure.premiums.adjust`, `insure.claims.settle` |
| Fraud investigator | Investigate and resolve anomaly flags | `insure.anomalies.investigate` |
| Product manager | Configure products, coverages, rating, and policy wordings for RAG | `insure.products.manage`, `insure.knowledge.manage` |
| Auditor | Read-only access to decisions, AI assessments, and audit trail | `insure.audit.read` |

Human tasks — "review this referred application", "approve this claim" — are presented and tracked by **Alensi Flow**. The user completes the task in Flow; Flow calls Insure to record the outcome. Insure never maintains its own review queues. See [ADR-0002](../decisions/0002-workflow-orchestration-in-alensi-flow.md).

A customer self-service portal is out of scope for the first version. When one is added it will be a separate client application using the same API with customer-scoped permissions.

## External Systems

| System | Direction | Interaction | Protocol |
|---|---|---|---|
| **Alensi Identity** | Insure → Identity | Insure is an OAuth2 resource server validating Keycloak-issued JWTs (realm `alensi`, audience `alensi-insure`). Fine-grained permission checks are delegated to Identity's permission API. | OIDC / JWT, REST |
| **Alensi Flow** | Both | Insure publishes domain events that start or advance Flow processes. Flow calls Insure's command endpoints as a service account, naming the acting user. | RabbitMQ, REST |
| **Alensi Pay** | Both | Insure requests premium collections and claim payouts with idempotency keys. Pay publishes payment status events that Insure consumes to update installments and settlements. | REST, RabbitMQ |
| **Alensi Recon** | None directly | Recon reconciles Pay's transactions against provider statements and ledgers. Insure's payment references carry Pay's payment IDs so a reconciliation exception can be traced back to a policy or claim. | — |
| **Alensi Observe** | Insure → Observe | Traces, metrics, and logs via OpenTelemetry; AI latency, token, and failure metrics. | OTLP |
| **AI model provider** | Insure → provider | Chat completion (structured output, tool calling) and embedding models through Spring AI. Only pseudonymised, minimised data is sent. | HTTPS |

## Boundaries

What Insure **does**:

- Holds the authoritative record of customers, products, policies, coverage, premiums, claims, decisions, and settlements.
- Enforces insurance business rules deterministically: eligibility, premium calculation, coverage checks, claim state transitions, limit consumption.
- Produces advisory intelligence — triage, risk scores, anomaly flags, document extractions, summaries, policy-grounded answers — and records how each was produced.

What Insure **does not do**:

- Authenticate users or store passwords, roles, or grants (Identity).
- Decide *who* reviews something, *when* it escalates, or *how many* approvals it needs (Flow).
- Move money, talk to payment providers, or reconcile (Pay, Recon).
- Let an AI output become a business decision without a human (see [AI Decisioning](../ai/ai-decisioning.md)).
