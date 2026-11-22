# API Specification

The REST API for Alensi Insure, the events it publishes and consumes, and the contracts it relies on with Alensi Identity, Flow, and Pay.

This is the design contract. Once implemented, the OpenAPI document generated at `/v3/api-docs` is authoritative for request/response schemas, and this document remains the place for conventions, authorization, and integration behaviour.

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
| `403` | Authenticated but lacks the permission on the record's branch |
| `404` | Not found **or** not visible to the caller (no existence leak across branches) |
| `409` | Illegal state transition, e.g. deciding a claim that is `SUBMITTED` |
| `412` | `If-Match` version mismatch |
| `422` | Business rule violation, e.g. approved amount exceeds payable amount; or idempotency key reused with a different body |
| `429` | Rate limit (AI endpoints) |
| `503` | A required dependency (Identity, Pay) is unavailable |

## Authentication and Authorization

Authentication and authorization are provided by **Alensi Identity** ([ADR-0003](../decisions/0003-auth-via-alensi-identity.md)).

### Tokens

Insure is an OAuth2 resource server. Every request carries `Authorization: Bearer <JWT>` issued by the Keycloak realm `alensi` with audience `alensi-insure`. Insure validates signature (JWKS), issuer, audience, and expiry locally. The `sub` claim is the user ID used throughout Insure.

| Caller | Grant | Keycloak client |
|---|---|---|
| Web console (staff) | Authorization code + PKCE | `alensi-web` |
| Alensi Flow | Client credentials | `alensi-flow` |
| Insure → Identity, Insure → Pay | Client credentials | `alensi-insure` |

### Permissions

Insure registers the application `alensi-insure` in Identity with the permissions below. Roles (bundles of permissions) and grants (role + user + target) are managed in Identity, not Insure.

| Permission | Allows |
|---|---|
| `insure.customers.read` | View customers and their activity |
| `insure.customers.write` | Register and update customers |
| `insure.products.read` | View products |
| `insure.products.manage` | Create, publish, withdraw product versions |
| `insure.policies.read` | View policies, coverages, insured items |
| `insure.policies.write` | Quote, accept, renew |
| `insure.policies.cancel` | Cancel policies |
| `insure.underwriting.decide` | Record underwriting decisions |
| `insure.premiums.read` | View schedules, installments, payment references |
| `insure.premiums.collect` | Initiate premium collection |
| `insure.premiums.adjust` | Create premium adjustments |
| `insure.claims.read` | View claims |
| `insure.claims.submit` | Submit claims, add items, withdraw |
| `insure.claims.assess` | Run coverage checks, record assessed amounts, request information |
| `insure.claims.decide` | Record claim decisions at `STANDARD` review level |
| `insure.claims.decide-senior` | Record claim decisions at `SENIOR` and `INVESTIGATION` levels |
| `insure.claims.settle` | Initiate settlements |
| `insure.documents.read` | Download `STANDARD` documents |
| `insure.documents.read-sensitive` | Download `SENSITIVE` and `MEDICAL` documents |
| `insure.documents.write` | Upload documents, verify extractions |
| `insure.ai.read` | View AI assessments and evidence |
| `insure.ai.request` | Request AI re-assessment, coverage questions, assistant |
| `insure.anomalies.investigate` | Change anomaly flag status |
| `insure.knowledge.manage` | Ingest and supersede knowledge documents |
| `insure.audit.read` | Read audit trails |
| `insure.workflow.act` | Service-only: call commands on behalf of an acting user (granted to `alensi-flow`) |

Suggested roles: `agent`, `underwriter`, `claims-handler`, `claims-manager`, `finance`, `fraud-investigator`, `product-manager`, `auditor`, `flow-service`.

### Targets and Branch Scope

Identity grants are scoped to targets in the hierarchy `country → region → branch`. Every customer, policy, and claim has a `branch_code`. A check for `insure.claims.decide` on claim `CLM-…` becomes "does the user hold `insure.claims.decide` on target `{claim.branch_code}` or any of its ancestors?"

Insure calls Identity's permission API:

```http
GET /api/users/{userId}/applications/alensi-insure/permissions/{permission}/targets/{branchCode}
```

Results are cached in Redis for 60 seconds. List endpoints first fetch the branches where the user holds the read permission:

```http
GET /api/users/{userId}/applications/alensi-insure/permissions/{permission}/targets
```

and filter queries by `branch_code IN (...)`. If Identity is unavailable and no cached result exists, the request fails closed with `503`.

### Acting on Behalf of a User (Flow)

Human tasks live in Flow. When a user completes a task, Flow calls Insure with its own service token and the acting user:

```http
POST /api/v1/claims/{id}/decisions
Authorization: Bearer <alensi-flow client-credentials token>
X-Acting-User: 4f1c…            # Identity user ID who completed the task
X-Flow-Task-Id: task-81723
```

Insure then requires **both**:

1. The service has `insure.workflow.act`.
2. The acting user has the command's own permission (e.g. `insure.claims.decide`) on the record's branch.

Both identities are written to the audit trail (`actor_id` = `alensi-flow`, `on_behalf_of` = acting user). `X-Acting-User` is ignored on user tokens. Replacing this header with OAuth2 token exchange is a recorded follow-up in [ADR-0003](../decisions/0003-auth-via-alensi-identity.md).

---

## Endpoints

Permissions are listed per endpoint. All record-level permissions are checked against the record's branch.

### Customers

| Method | Path | Permission | Description |
|---|---|---|---|
| `POST` | `/customers` | `customers.write` | Register a customer. Rejects duplicates by national ID hash (`409` with the existing customer ID if visible) |
| `GET` | `/customers` | `customers.read` | Search by `customerNumber`, `name`, `phone`, `nationalId` (hashed server-side), `branchCode` |
| `GET` | `/customers/{id}` | `customers.read` | Customer profile. National ID returned masked (`****5678`) |
| `PATCH` | `/customers/{id}` | `customers.write` | Update contact details (JSON Merge Patch). Requires `If-Match` |
| `POST` | `/customers/{id}/deactivate` | `customers.write` | Only if no active policies or open claims |
| `GET` | `/customers/{id}/policies` | `policies.read` | |
| `GET` | `/customers/{id}/claims` | `claims.read` | |
| `GET` | `/customers/{id}/activity` | `customers.read` | Timeline |

### Products

| Method | Path | Permission | Description |
|---|---|---|---|
| `POST` | `/products` | `products.manage` | Create a product |
| `GET` | `/products` | `products.read` | List products with their current published version |
| `POST` | `/products/{id}/versions` | `products.manage` | Create a `DRAFT` version, optionally copying an existing one |
| `GET` | `/products/{id}/versions/{versionNumber}` | `products.read` | Full version: coverages, exclusions, eligibility, rating |
| `PUT` | `/products/{id}/versions/{versionNumber}` | `products.manage` | Replace a `DRAFT` version's configuration. `409` if published |
| `POST` | `/products/{id}/versions/{versionNumber}/publish` | `products.manage` | Validates and publishes; optionally links a policy wording document for RAG ingestion |
| `POST` | `/products/{id}/versions/{versionNumber}/withdraw` | `products.manage` | Stops new sales on this version |

Products are global (not branch-scoped); permission is checked on the root target.

### Policies

| Method | Path | Permission | Description |
|---|---|---|---|
| `POST` | `/policies/quotes` | `policies.write` | Create a quote. Evaluates eligibility and rating synchronously. Response status is `QUOTED`, `REFERRED`, or `DECLINED` with reasons |
| `GET` | `/policies` | `policies.read` | Filter by `customerId`, `status`, `productCode`, `expiringBefore` |
| `GET` | `/policies/{id}` | `policies.read` | Policy with insured items and coverages |
| `POST` | `/policies/{id}/accept` | `policies.write` | Customer accepts a `QUOTED` policy → `PENDING_PAYMENT`; creates the premium schedule |
| `POST` | `/policies/{id}/underwriting-decisions` | `underwriting.decide` | Record an underwriter's decision on a `REFERRED` policy (called by Flow) |
| `POST` | `/policies/{id}/cancel` | `policies.cancel` | Body: `reason`, `effectiveDate`. Calculates pro-rata refund as a premium adjustment |
| `POST` | `/policies/{id}/reinstate` | `policies.write` | Reinstate a `LAPSED` policy once arrears are paid |
| `POST` | `/policies/{id}/renewals` | `policies.write` | Create a renewal quote on the current product version |
| `GET` | `/policies/{id}/coverages` | `policies.read` | Coverages with limits, consumed and reserved amounts |
| `GET` | `/policies/{id}/coverage-status?date=` | `policies.read` | Deterministic "is this policy in force on this date, and which coverages apply" |

Example quote request:

```json
{
  "customerId": "0192f3a4-…",
  "productCode": "MOTOR-COMP",
  "effectiveDate": "2026-12-01",
  "paymentFrequency": "MONTHLY",
  "insuredItems": [
    {
      "itemType": "VEHICLE",
      "description": "Toyota Axio KDA 123A",
      "sumInsured": { "amount": "1450000.00", "currency": "KES" },
      "attributes": { "registration": "KDA 123A", "make": "Toyota", "model": "Axio", "yearOfManufacture": 2017, "use": "PRIVATE" }
    }
  ],
  "coverages": ["OWN_DAMAGE", "THIRD_PARTY_PROPERTY", "THEFT"]
}
```

Response (`201`):

```json
{
  "id": "0192f3b0-…",
  "policyNumber": "POL-2026-000418",
  "status": "QUOTED",
  "quoteExpiresAt": "2026-11-29T09:30:00Z",
  "totalPremium": { "amount": "58000.00", "currency": "KES" },
  "coverages": [
    { "code": "OWN_DAMAGE", "limit": { "amount": "1450000.00", "currency": "KES" }, "deductible": { "type": "PERCENTAGE", "value": "2.5" }, "premium": { "amount": "43500.00", "currency": "KES" } }
  ],
  "rating": [
    { "factor": "customer.age", "band": "25-35", "multiplier": "1.00" },
    { "factor": "item.vehicle.age", "band": "6-10", "multiplier": "1.15" }
  ],
  "eligibility": []
}
```

### Premiums

| Method | Path | Permission | Description |
|---|---|---|---|
| `GET` | `/policies/{id}/premium-schedule` | `premiums.read` | Schedule with installments |
| `POST` | `/installments/{id}/collections` | `premiums.collect` | Request collection through Alensi Pay. Body: `paymentMethod` (Pay method token or `MPESA_STK` with phone). Returns `202` with the payment reference |
| `GET` | `/policies/{id}/payments` | `premiums.read` | Payment references (collections and refunds) with Pay status |
| `POST` | `/policies/{id}/premium-adjustments` | `premiums.adjust` | Loading, discount, or endorsement; regenerates unpaid installments |

### Claims

| Method | Path | Permission | Description |
|---|---|---|---|
| `POST` | `/claims` | `claims.submit` | Submit a claim with items. Runs claim-level coverage checks synchronously; returns `201` even if not covered (the claim is recorded and reviewed) |
| `GET` | `/claims` | `claims.read` | Filter by `status`, `policyId`, `customerId`, `assignedTo`, `priority`, `reportedFrom/To` |
| `GET` | `/claims/{id}` | `claims.read` | Claim with items, latest coverage checks, open information requests, decision, settlements |
| `POST` | `/claims/{id}/items` | `claims.submit` | Add an item while `SUBMITTED` or `INFORMATION_REQUESTED` |
| `POST` | `/claims/{id}/coverage-checks` | `claims.assess` | Re-run the deterministic coverage engine |
| `POST` | `/claims/{id}/information-requests` | `claims.assess` | Request documents or details → `INFORMATION_REQUESTED` |
| `POST` | `/claims/{id}/information-requests/{reqId}/fulfil` | `claims.submit` | Mark fulfilled; when none remain open → `UNDER_ASSESSMENT` |
| `POST` | `/claims/{id}/assessment` | `claims.assess` | Record assessed amounts per item; computes deductible and payable amounts → `AWAITING_DECISION` |
| `POST` | `/claims/{id}/decisions` | `claims.decide` | Record the decision. Body: `decision`, `approvedAmount`, `reasonCode`, `rationale`, `aiAssessmentId`. Validates amount ≤ payable, four-eyes, review level, and reserves limit |
| `POST` | `/claims/{id}/settlements` | `claims.settle` | Create a settlement and request payout via Pay. Body: `payeeType`, `payeeToken`, `amount` |
| `POST` | `/claims/{id}/withdraw` | `claims.submit` | Withdraw before a decision |
| `POST` | `/claims/{id}/close` | `claims.assess` | Close a `SETTLED` or `REJECTED` claim |
| `GET` | `/claims/{id}/history` | `claims.read` | Status transitions and actions from the audit trail |

Example decision request (from Flow):

```json
{
  "decision": "PARTIALLY_APPROVED",
  "approvedAmount": { "amount": "182000.00", "currency": "KES" },
  "reasonCode": "PARTIAL_EXCLUSION",
  "rationale": "Windscreen replacement covered. Pre-existing rear bumper damage excluded under 4.2(c) per assessor report.",
  "aiAssessmentId": "0192f4c1-…"
}
```

### Documents

| Method | Path | Permission | Description |
|---|---|---|---|
| `POST` | `/{ownerType}/{ownerId}/documents` | `documents.write` | Register a document; returns a presigned upload URL. `ownerType` is `customers`, `policies`, or `claims` |
| `POST` | `/documents/{id}/complete` | `documents.write` | Confirm upload; triggers virus scan, then extraction for supported types |
| `GET` | `/{ownerType}/{ownerId}/documents` | `documents.read` | List metadata |
| `GET` | `/documents/{id}/content` | `documents.read` or `documents.read-sensitive` | `302` to a 5-minute presigned download URL. Audited |
| `GET` | `/documents/{id}/extractions` | `documents.read` | Extraction results |
| `POST` | `/extractions/{id}/verify` | `documents.write` | Body: `status` (`VERIFIED`/`REJECTED`), optional corrected fields |

### Intelligence

All AI responses include `assessmentId`, `modelName`, `promptVersion`, `createdAt`, and an `advisory: true` marker.

| Method | Path | Permission | Description |
|---|---|---|---|
| `GET` | `/claims/{id}/ai-assessments` | `ai.read` | Latest assessment of each type for the claim |
| `GET` | `/ai-assessments/{id}` | `ai.read` | Full output with evidence and tool invocations. Viewing is audited |
| `POST` | `/claims/{id}/ai-assessments` | `ai.request` | Re-run an assessment type; `202` with the pending assessment ID |
| `POST` | `/ai-assessments/{id}/feedback` | `ai.read` | Rate an output |
| `POST` | `/policies/{id}/coverage-questions` | `ai.request` + `policies.read` | Ask a question about a policy's coverage, answered with RAG and citations. Synchronous, 20 s timeout |
| `POST` | `/claims/{id}/assistant/messages` | `ai.request` + `claims.read` | Claim assistant: answers questions about one claim using read-only tools. Synchronous, 30 s timeout |
| `GET` | `/anomalies` | `anomalies.investigate` | Filter by `status`, `anomalyType`, `severityGte` |
| `POST` | `/anomalies/{id}/status` | `anomalies.investigate` | Change status; `resolutionNote` required for `DISMISSED`/`CONFIRMED` |
| `POST` | `/knowledge-documents` | `knowledge.manage` | Ingest a document (referencing an uploaded `documentId`); `202` |
| `GET` | `/knowledge-documents` | `knowledge.manage` | List with status and chunk counts |
| `POST` | `/knowledge-documents/{id}/supersede` | `knowledge.manage` | Mark superseded. Procedures and guidelines leave retrieval; product wordings stay retrievable for policies sold under that product version |

Example coverage question:

```json
{ "question": "Is damage from driving through a flooded road covered?", "incidentDate": "2026-11-02" }
```

```json
{
  "assessmentId": "0192f5d2-…",
  "advisory": true,
  "answer": "Flood damage to the vehicle is covered under Own Damage, but section 4.2(f) excludes loss caused by deliberately driving into flood water against official warnings.",
  "authoritativeCoverage": {
    "policyInForce": true,
    "applicableCoverages": ["OWN_DAMAGE"],
    "source": "coverage engine"
  },
  "citations": [
    { "clauseReference": "3.1", "title": "Own Damage", "excerpt": "…loss of or damage to the insured vehicle caused by accidental collision, fire, flood…" },
    { "clauseReference": "4.2(f)", "title": "General Exclusions", "excerpt": "…where the insured vehicle is driven into flood water contrary to…" }
  ],
  "confidence": "MEDIUM",
  "requiresHumanInterpretation": true
}
```

### Audit

| Method | Path | Permission | Description |
|---|---|---|---|
| `GET` | `/audit-events` | `audit.read` | Filter by `subjectType`, `subjectId`, `actorId`, `action`, time range |

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
    "branchCode": "KE-NBO-WESTLANDS",
    "incidentType": "COLLISION",
    "claimedAmount": { "amount": "240000.00", "currency": "KES" },
    "coverageResult": "COVERED",
    "priority": "NORMAL"
  }
}
```

Event payloads carry IDs, statuses, amounts, and branch codes — **never PII**. Consumers fetch details through the API with their own authorization.

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
  "metadata": { "sourceSystem": "alensi-insure", "sourceType": "INSTALLMENT", "sourceId": "0192f3c9-…" }
}
```

| Requirement | Why |
|---|---|
| Idempotency keys | Insure retries on timeout without double-charging or double-paying |
| Payment ID in the synchronous response | Insure links its payment reference before any event arrives |
| Status events on RabbitMQ carrying `metadata` back | Insure correlates outcomes without polling |
| Beneficiary tokens for payouts | Insure never stores bank or mobile-money account details |
| `GET /payments/{id}` | Reconciling `UNKNOWN` references after an outage |

## Alensi Flow Contract

Flow subscribes to the events marked **Flow** above and drives these Insure commands as the acting user:

| Flow step | Insure command |
|---|---|
| Underwriting review task completed | `POST /policies/{id}/underwriting-decisions` |
| Adjuster requests information | `POST /claims/{id}/information-requests` |
| Adjuster completes assessment | `POST /claims/{id}/assessment` |
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
