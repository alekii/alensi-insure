# API Specification

The REST API for Alensi Insure, the events it publishes and consumes, and the contracts it relies on with Alensi Identity, Flow, and Pay.

This document covers conventions, events, and integration contracts. The endpoint contract is [openapi.yaml](./openapi.yaml); who may call what is in [Authorization and Data Scoping](./authorization.md).

## Conventions

| Topic | Convention |
|---|---|
| Base path | `/api/v1` |
| Format | JSON, `camelCase` fields, UTF-8 |
| Money | `{"amount": "12500.00", "currency": "KES"}` — amounts are strings to avoid floating-point loss |
| Dates | ISO 8601: `2026-03-14` for dates, `2026-03-14T09:30:00Z` for timestamps |
| IDs | Resources are addressed by UUID. Business numbers (`claimNumber`) are searchable fields, not path IDs |
| Errors | RFC 9457 `application/problem+json` with `type`, `title`, `status`, `detail`, and `errors[]` for field validation. Same format as Alensi Identity |
| Pagination | `?page=0&size=20&sort=reportedAt,desc`; response has `content`, `page`, `size`, `totalElements` |
| Idempotency | All `POST` requests that create resources or trigger side effects accept an `Idempotency-Key` header. A replay with the same key and body returns the original response; with a different body returns `422` |
| Concurrency | Resources return an `ETag` (the `version`). State-changing requests on existing resources require `If-Match`; a stale version returns `412` |
| State changes | Lifecycle transitions are explicit command endpoints (`POST /claims/{id}/withdraw`), never `PATCH status` |
| Tracing | `traceparent` is honoured and propagated; the trace ID is returned as `X-Correlation-Id` |

### Common Status Codes

| Code | When |
|---|---|
| `200` / `201` / `202` | Success / created / accepted for asynchronous processing |
| `400` | Malformed request or field validation failure |
| `401` | Missing or invalid token |
| `403` | Can see the record but lacks the permission, exceeds an approval limit, or a domain rule refuses the caller ([details](./authorization.md#404-or-403)) |
| `404` | Not found, in another tenant, or not visible to the caller (no existence leak) |
| `409` | Illegal state transition, e.g. deciding a claim that is `SUBMITTED` |
| `412` | `If-Match` version mismatch |
| `422` | Business rule violation, e.g. approved amount exceeds payable amount; or idempotency key reused with a different body |
| `429` | Rate limit (AI endpoints) |
| `503` | A required dependency (Identity, Pay, model provider) is unavailable |

## Authentication and Authorization

Authentication and authorization are provided by **Alensi Identity** ([ADR-0003](../decisions/0003-auth-via-alensi-identity.md)). The full contract — tenants, record ownership, the permission each endpoint requires, approval limits, domain rules, and response-level scoping — is in **[Authorization and Data Scoping](./authorization.md)**.

### Tokens

Insure is an OAuth2 resource server. Every request carries `Authorization: Bearer <JWT>` issued by the Keycloak realm `alensi` with audience `alensi-insure`. Insure validates signature (JWKS), issuer, audience, and expiry locally.

| Caller | Grant | Keycloak client |
|---|---|---|
| Staff web app | Authorization code + PKCE, MFA enforced | `alensi-insure-web` |
| Alensi Flow | Client credentials, plus `X-Acting-User` | `alensi-flow` |
| Insure → Identity, Insure → Pay | Client credentials | `alensi-insure` |

### Tenant

Each insurer is a tenant. A request acts in the tenant named by the token's `organization` claim or the `X-Alensi-Tenant` header, and every record lookup is filtered by it.

### Permissions

Permission codes are registered by application `alensi-insure` in Identity's catalog (`catalog/alensi-insure.json`), e.g. `claims.view`, `claims.approve`. Permissions are granted through roles on targets (`company → country → region → branch → team`); each record is owned by a unit (team or branch) and checks are made on it. Some permissions (`claims.approve`, `claims.settle`, `policies.underwrite`, `premiums.adjust`) carry per-currency amount limits.

---

## Endpoints

The endpoint contract is **[openapi.yaml](./openapi.yaml)** (OpenAPI 3.1): paths, request and response schemas, examples, and an `x-authorization` block per operation. The [permission matrix](./authorization.md#permission-matrix) lists the requirements for every endpoint.

Once implemented, the OpenAPI document served at `/v3/api-docs` must match this contract; a contract test compares the two in CI.

| Resource | Base path | Module |
|---|---|---|
| Current user | `/me` | platform |
| Customers | `/customers` | customer |
| Products and versions | `/products` | product |
| Quotes, policies, underwriting, renewals, cancellation | `/policies` | policy |
| Premium schedules, adjustments, collections, payments | `/policies/{id}/premium-*`, `/installments`, `/policies/{id}/payments` | premium |
| Claims, assessment, decisions, settlements | `/claims` | claims |
| Documents and extractions | `/{owner}/{id}/documents`, `/documents`, `/extractions` | document |
| AI assessments, coverage questions, assistant, anomalies, knowledge base | `/ai-assessments`, `/policies/{id}/coverage-questions`, `/claims/{id}/assistant`, `/anomalies`, `/knowledge-documents` | intelligence |
| Audit trail | `/audit-events` | platform |

---

## Events

### Envelope

Published to the `insure.events` topic exchange with routing key equal to `type`. CloudEvents-compatible attributes:

```json
{
  "id": "0192f6a0-…",
  "type": "insure.claim.submitted",
  "source": "alensi-insure",
  "time": "2026-11-22T09:30:00Z",
  "subject": "claim/0192f4b2-…",
  "traceparent": "00-…",
  "data": {
    "claimId": "0192f4b2-…",
    "claimNumber": "CLM-2026-000917",
    "policyId": "0192f3b0-…",
    "tenantCode": "JUB",
    "unitCode": "JUB-KE-NRB-WLD",
    "incidentType": "COLLISION",
    "claimedAmount": { "amount": "240000.00", "currency": "KES" },
    "coverageResult": "COVERED",
    "priority": "NORMAL"
  }
}
```

Event payloads carry IDs, statuses, amounts, tenant and unit codes — **never PII**. Consumers fetch details through the API with their own authorization.

Delivery is at-least-once in commit order per aggregate. Consumers must de-duplicate by `id`.

### Published

| Type | When | Typical consumer |
|---|---|---|
| `insure.customer.registered` | Customer created | Customer activity |
| `insure.policy.quoted` | Quote created with `QUOTED` | — |
| `insure.policy.referred` | Quote requires underwriting | **Flow**: starts underwriting review |
| `insure.policy.underwritten` | Underwriting decision recorded | Flow: completes review |
| `insure.policy.issued` | First activation | Flow: welcome notifications |
| `insure.policy.lapsed` | Grace period exceeded | Flow: arrears follow-up |
| `insure.policy.reinstated` | Lapsed policy reinstated | |
| `insure.policy.cancelled` | Cancelled | Flow: refund approval if refund due |
| `insure.policy.expired` | Expiry date passed | |
| `insure.policy.renewal-due` | 45 days before expiry | **Flow**: renewal process |
| `insure.premium.installment-due` | Installment becomes due | Flow: payment reminder |
| `insure.premium.installment-overdue` | Past due date | Flow: dunning |
| `insure.premium.installment-paid` | Collection succeeded | |
| `insure.claim.submitted` | Claim created | **Flow**: starts claim process; **Insure worker**: triage, anomalies, risk |
| `insure.claim.information-requested` | Information requested | Flow: customer notification, reminder timer |
| `insure.claim.information-received` | All requests fulfilled | Flow: resume; worker: re-assess |
| `insure.claim.assessed` | Adjuster recorded assessment | **Flow**: route to approval chain |
| `insure.claim.escalated` | Review level raised by a person | Flow: re-route the approval task |
| `insure.claim.decided` | Decision recorded | Flow: notify, settlement step |
| `insure.claim.settlement-initiated` | Payout requested from Pay | |
| `insure.claim.settled` | Payout succeeded | Flow: close |
| `insure.claim.settlement-failed` | Payout failed | Flow: finance task |
| `insure.claim.withdrawn` / `insure.claim.closed` | | Flow: end process |
| `insure.ai.assessment-completed` | AI assessment stored | **Flow**: attach to review task, may raise review level |
| `insure.anomaly.flagged` | New anomaly flag | **Flow**: investigation task |
| `insure.document.extracted` | Extraction completed | Flow: verification task |

`insure.ai.assessment-completed` includes `assessmentType`, `classification`, `riskScore`, `requiresManualReview`, and `minimumReviewLevel` (see [AI Decisioning](../ai/ai-decisioning.md#routing-signals)).

### Consumed

| Source | Type | Effect in Insure |
|---|---|---|
| Alensi Pay | `pay.payment.pending` | Payment reference → `PENDING` |
| Alensi Pay | `pay.payment.succeeded` | Collection: installment paid, policy activated or reinstated if applicable. Disbursement: settlement `PAID`, claim `SETTLED`, limit moved from reserved to consumed |
| Alensi Pay | `pay.payment.failed` | Payment reference `FAILED`; settlement `FAILED`; publishes follow-up event |
| Alensi Pay | `pay.payment.refunded` | Collection reference → `REFUNDED` |
| Insure (self) | `insure.claim.*`, `insure.document.*`, `insure.policy.referred` | Worker queue `insure.jobs` triggers AI and extraction jobs |

Pay events are matched by `pay_payment_id`, falling back to the `idempotencyKey` echoed in event metadata. Events with a timestamp older than `last_event_at` are ignored.

## Alensi Pay Contract

Alensi Pay's API is not yet published. Insure depends on the following capabilities and will adapt the `pay-client` once Pay's spec exists:

```http
POST /api/v1/payments
Authorization: Bearer <alensi-insure client-credentials token>
Idempotency-Key: insure:COLLECTION:0192f3c9-…:1

{
  "direction": "COLLECTION",
  "amount": { "amount": "4833.33", "currency": "KES" },
  "paymentMethod": { "type": "MPESA_STK", "phone": "+2547…" },
  "reference": "POL-2026-000418/INST-3",
  "metadata": { "sourceSystem": "alensi-insure", "tenantCode": "JUB", "sourceType": "INSTALLMENT", "sourceId": "0192f3c9-…" }
}
```

| Requirement | Why |
|---|---|
| Idempotency keys | Insure retries on timeout without double-charging or double-paying |
| Payment ID in the synchronous response | Insure links its payment reference before any event arrives |
| Status events on RabbitMQ carrying `metadata` back | Insure correlates outcomes without polling, and sets the tenant from `metadata.tenantCode` before any database access ([Row-Level Security](./authorization.md#row-level-security)) |
| Beneficiary tokens for payouts | Insure never stores bank or mobile-money account details |
| `GET /payments/{id}` | Reconciling `UNKNOWN` references after an outage |

## Alensi Flow Contract

Flow subscribes to the events marked **Flow** above and drives these Insure commands as the acting user. Its service account needs `workflows.act`, it sends `X-Alensi-Tenant` from the event's `tenantCode`, and the acting user needs the command's own permissions ([flow-acting-user](./authorization.md#flow-acting-user)):

| Flow step | Insure command |
|---|---|
| Underwriting review task completed | `POST /policies/{id}/underwriting-decisions` |
| Adjuster requests information | `POST /claims/{id}/information-requests` |
| Adjuster completes assessment | `POST /claims/{id}/assessment` |
| Reviewer raises the review level | `POST /claims/{id}/escalations` |
| Final approver decides | `POST /claims/{id}/decisions` |
| Finance releases payment | `POST /claims/{id}/settlements` |
| Investigator resolves anomaly | `POST /anomalies/{id}/status` |
| Extraction verified | `POST /extractions/{id}/verify` |

Flow stores the Insure record ID on its process instance and passes it back as `X-Flow-Task-Id` and `flowProcessId` so Insure can link records to processes. Insure stays correct if Flow misbehaves: every command re-validates state, permission, and business rules.

## Rate Limits

| Endpoint group | Limit |
|---|---|
| Interactive AI (`coverage-questions`, `assistant/messages`) | 20 requests / user / minute |
| AI re-assessment | 10 requests / claim / hour |
| Everything else | Gateway default |
