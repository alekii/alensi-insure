# Entity Relationship Diagram

The database is PostgreSQL with one schema per module. Diagrams are split by module for readability; the overview shows how the modules connect. Column-level detail — types, nullability, enums, and constraints — is in the [Data Dictionary](data-dictionary.md).

Conventions used below:

- Primary keys are UUIDs (v7, time-ordered) unless stated otherwise.
- Money is `decimal(19,4)` paired with an ISO 4217 `currency` column on the owning record.
- Every mutable aggregate root has a `version` column for optimistic locking, plus `created_at` / `updated_at`. These are omitted from the diagrams.
- Cross-schema foreign keys only point upstream: `customer`, `product` → `policy` → `premium` → `claims`. The `intelligence` and `document` schemas reference domain records by ID without database foreign keys, so the domain schemas can be migrated independently of them.

## Overview

```mermaid
erDiagram
    CUSTOMER ||--o{ POLICY : holds
    PRODUCT ||--|{ PRODUCT_VERSION : "is versioned as"
    PRODUCT_VERSION ||--o{ POLICY : "is sold as"
    PRODUCT_VERSION ||--|{ COVERAGE_DEFINITION : defines
    POLICY ||--|{ POLICY_COVERAGE : includes
    COVERAGE_DEFINITION ||--o{ POLICY_COVERAGE : "is instantiated as"
    POLICY ||--|{ INSURED_ITEM : covers
    POLICY ||--|| PREMIUM_SCHEDULE : "is paid by"
    PREMIUM_SCHEDULE ||--|{ INSTALLMENT : "is split into"
    INSTALLMENT ||--o{ PAYMENT_REFERENCE : "is collected by"
    POLICY ||--o{ CLAIM : "is claimed under"
    CLAIM ||--|{ CLAIM_ITEM : contains
    POLICY_COVERAGE ||--o{ CLAIM_ITEM : "is claimed against"
    CLAIM ||--o{ CLAIM_DECISION : "is decided by"
    CLAIM ||--o{ SETTLEMENT : "is paid by"
    SETTLEMENT ||--o| PAYMENT_REFERENCE : "is executed as"
    CLAIM ||--o{ AI_ASSESSMENT : "is assessed by"
    CLAIM ||--o{ ANOMALY_FLAG : "is flagged by"
    CLAIM ||--o{ DOCUMENT : "is supported by"
    DOCUMENT ||--o{ DOCUMENT_EXTRACTION : "is extracted into"
    PRODUCT_VERSION ||--o{ KNOWLEDGE_DOCUMENT : "is described by"
    KNOWLEDGE_DOCUMENT ||--|{ KNOWLEDGE_CHUNK : "is split into"
```

## Customer

```mermaid
erDiagram
    CUSTOMER {
        uuid id PK
        varchar customer_number UK
        varchar customer_type "INDIVIDUAL | ORGANIZATION"
        varchar first_name
        varchar last_name
        varchar organization_name
        date date_of_birth
        bytea national_id_encrypted
        varchar national_id_hash UK
        varchar email
        varchar phone
        varchar address_line1
        varchar city
        char country_code
        varchar unit_code "Identity target"
        varchar status "ACTIVE | INACTIVE"
    }
    CUSTOMER_ACTIVITY {
        uuid id PK
        uuid customer_id FK
        timestamptz occurred_at
        varchar activity_type
        varchar subject_type
        uuid subject_id
        varchar summary
    }
    CUSTOMER ||--o{ CUSTOMER_ACTIVITY : has
```

## Product

```mermaid
erDiagram
    PRODUCT {
        uuid id PK
        varchar code UK "unique per tenant"
        varchar name
        varchar line_of_business "MOTOR | HEALTH | PROPERTY | LIFE | TRAVEL"
        varchar currency "default for new versions"
        varchar status "ACTIVE | RETIRED"
    }
    PRODUCT_VERSION {
        uuid id PK
        uuid product_id FK
        int version_number
        varchar status "DRAFT | PUBLISHED | WITHDRAWN"
        varchar currency
        int term_months
        int grace_period_days
        decimal minimum_premium
        date effective_from "set on publishing"
        date effective_to "set when superseded"
        uuid wording_document_id
    }
    COVERAGE_DEFINITION {
        uuid id PK
        uuid product_version_id FK
        varchar code
        varchar name
        boolean mandatory
        text_array incident_types
        varchar limit_type "FIXED | SUM_INSURED"
        decimal limit_amount "FIXED only"
        varchar limit_basis "PER_EVENT | AGGREGATE"
        varchar deductible_type "FIXED | PERCENTAGE"
        decimal deductible_value
        decimal deductible_minimum
        int waiting_period_days
    }
    COVERAGE_EXCLUSION {
        uuid id PK
        uuid product_version_id FK
        varchar code
        text description
        varchar clause_reference
        text_array coverage_codes "empty = all coverages"
        jsonb machine_rule "null = needs human interpretation"
    }
    ELIGIBILITY_RULE {
        uuid id PK
        uuid product_version_id FK
        varchar code
        varchar attribute
        varchar operator
        jsonb value
        varchar outcome "DECLINE | REFER"
        varchar message
    }
    BASE_RATE {
        uuid id PK
        uuid product_version_id FK
        varchar coverage_code
        decimal base_premium "or"
        decimal rate_percent "of sum insured"
    }
    RATING_FACTOR {
        uuid id PK
        uuid product_version_id FK
        varchar coverage_code "null = all"
        varchar attribute
        jsonb band
        decimal multiplier
    }
    PRODUCT ||--|{ PRODUCT_VERSION : "has"
    PRODUCT_VERSION ||--|{ COVERAGE_DEFINITION : defines
    PRODUCT_VERSION ||--o{ COVERAGE_EXCLUSION : excludes
    PRODUCT_VERSION ||--o{ ELIGIBILITY_RULE : "is guarded by"
    PRODUCT_VERSION ||--o{ BASE_RATE : "is priced by"
    PRODUCT_VERSION ||--o{ RATING_FACTOR : "is adjusted by"
```

## Policy

```mermaid
erDiagram
    POLICY {
        uuid id PK
        varchar policy_number UK
        uuid customer_id FK
        uuid product_version_id FK
        varchar status
        date effective_date
        date expiry_date
        decimal total_premium
        char currency
        varchar payment_frequency
        varchar unit_code
        uuid renewed_from_policy_id FK
        timestamptz quote_expires_at
        timestamptz issued_at
        timestamptz cancelled_at
        varchar cancellation_reason
        varchar flow_process_id
    }
    INSURED_ITEM {
        uuid id PK
        uuid policy_id FK
        varchar item_type "VEHICLE | PROPERTY | PERSON | TRIP"
        varchar description
        decimal sum_insured
        jsonb attributes
    }
    POLICY_COVERAGE {
        uuid id PK
        uuid policy_id FK
        uuid coverage_definition_id FK
        decimal limit_amount
        decimal deductible_value
        varchar deductible_type
        decimal premium_amount
        decimal consumed_amount
        decimal reserved_amount
    }
    UNDERWRITING_DECISION {
        uuid id PK
        uuid policy_id FK
        varchar decision "ACCEPT | ACCEPT_WITH_LOADING | DECLINE"
        decimal loading_percent
        text reason
        uuid decided_by
        timestamptz decided_at
        uuid ai_assessment_id
    }
    POLICY ||--|{ INSURED_ITEM : covers
    POLICY ||--|{ POLICY_COVERAGE : includes
    POLICY ||--o{ UNDERWRITING_DECISION : "is underwritten by"
    POLICY ||--o| POLICY : "is renewed from"
```

## Premium

```mermaid
erDiagram
    PREMIUM_SCHEDULE {
        uuid id PK
        uuid policy_id FK, UK
        varchar frequency "ANNUAL | SEMI_ANNUAL | QUARTERLY | MONTHLY"
        decimal total_amount
        char currency
    }
    INSTALLMENT {
        uuid id PK
        uuid schedule_id FK
        int sequence_number
        date due_date
        decimal amount
        decimal paid_amount
        varchar status
    }
    PREMIUM_ADJUSTMENT {
        uuid id PK
        uuid policy_id FK
        varchar adjustment_type "LOADING | DISCOUNT | ENDORSEMENT | CANCELLATION_REFUND"
        decimal amount
        text reason
        uuid created_by
    }
    PAYMENT_REFERENCE {
        uuid id PK
        varchar direction "COLLECTION | DISBURSEMENT | REFUND"
        uuid installment_id FK
        uuid settlement_id
        uuid adjustment_id FK
        varchar pay_payment_id UK
        varchar idempotency_key UK
        decimal amount
        char currency
        varchar status
        varchar failure_reason
        timestamptz last_event_at
    }
    PREMIUM_SCHEDULE ||--|{ INSTALLMENT : "is split into"
    INSTALLMENT ||--o{ PAYMENT_REFERENCE : "is collected by"
    PREMIUM_ADJUSTMENT ||--o| PAYMENT_REFERENCE : "is refunded by"
```

## Claims

```mermaid
erDiagram
    CLAIM {
        uuid id PK
        varchar claim_number UK
        uuid policy_id FK
        uuid customer_id FK
        varchar status
        date incident_date
        timestamptz reported_at
        varchar incident_type
        text incident_description
        varchar incident_location
        decimal claimed_amount
        decimal approved_amount
        char currency
        varchar priority
        uuid assigned_to
        varchar unit_code
        varchar minimum_review_level
        varchar flow_process_id
    }
    CLAIM_ITEM {
        uuid id PK
        uuid claim_id FK
        uuid policy_coverage_id FK
        varchar description
        decimal claimed_amount
        decimal assessed_amount
        decimal deductible_applied
        decimal payable_amount
        varchar coverage_result
    }
    COVERAGE_CHECK {
        uuid id PK
        uuid claim_id FK
        uuid claim_item_id FK
        varchar result "COVERED | PARTIALLY_COVERED | NOT_COVERED | NEEDS_REVIEW"
        jsonb rule_trace
        varchar rules_version
        timestamptz evaluated_at
    }
    INFORMATION_REQUEST {
        uuid id PK
        uuid claim_id FK
        jsonb requested_items
        text message
        varchar status "OPEN | FULFILLED | CANCELLED"
        date due_date
        uuid requested_by
    }
    CLAIM_DECISION {
        uuid id PK
        uuid claim_id FK
        varchar decision "APPROVED | PARTIALLY_APPROVED | REJECTED"
        decimal approved_amount
        varchar reason_code
        text rationale
        uuid decided_by
        timestamptz decided_at
        varchar flow_task_id
        uuid ai_assessment_id
        varchar ai_alignment "AGREED | OVERRODE | NOT_AVAILABLE"
    }
    SETTLEMENT {
        uuid id PK
        uuid claim_id FK
        decimal amount
        char currency
        varchar payee_type "CUSTOMER | SERVICE_PROVIDER"
        varchar payee_token
        varchar status
    }
    CLAIM ||--|{ CLAIM_ITEM : contains
    CLAIM ||--o{ COVERAGE_CHECK : "is checked by"
    CLAIM_ITEM ||--o{ COVERAGE_CHECK : "is checked by"
    CLAIM ||--o{ INFORMATION_REQUEST : "needs"
    CLAIM ||--o{ CLAIM_DECISION : "is decided by"
    CLAIM ||--o{ SETTLEMENT : "is paid by"
```

## Document

```mermaid
erDiagram
    DOCUMENT {
        uuid id PK
        varchar owner_type "CUSTOMER | POLICY | CLAIM"
        uuid owner_id
        varchar document_type
        varchar file_name
        varchar content_type
        bigint size_bytes
        varchar storage_key UK
        char sha256
        varchar sensitivity "STANDARD | SENSITIVE | MEDICAL"
        varchar scan_status "PENDING | CLEAN | INFECTED"
        varchar extraction_status
        uuid uploaded_by
    }
    DOCUMENT_EXTRACTION {
        uuid id PK
        uuid document_id FK
        varchar schema_name
        jsonb extracted_fields
        jsonb field_confidence
        varchar status "COMPLETED | FAILED | VERIFIED | REJECTED"
        varchar model_name
        varchar prompt_version
        uuid verified_by
        timestamptz verified_at
    }
    DOCUMENT ||--o{ DOCUMENT_EXTRACTION : "is extracted into"
```

## Intelligence

```mermaid
erDiagram
    AI_ASSESSMENT {
        uuid id PK
        varchar subject_type "CLAIM | POLICY"
        uuid subject_id
        varchar assessment_type
        varchar status
        jsonb output
        int risk_score
        varchar classification
        boolean requires_manual_review
        varchar model_provider
        varchar model_name
        varchar prompt_version
        char input_hash
        int input_tokens
        int output_tokens
        int latency_ms
        uuid requested_by
        uuid supersedes_id FK
    }
    AI_EVIDENCE {
        uuid id PK
        uuid assessment_id FK
        varchar evidence_type
        varchar reference
        text excerpt
        int rank
    }
    AI_TOOL_INVOCATION {
        uuid id PK
        uuid assessment_id FK
        varchar tool_name
        jsonb arguments
        varchar status
        int duration_ms
        varchar result_digest
    }
    AI_FEEDBACK {
        uuid id PK
        uuid assessment_id FK
        uuid user_id
        varchar rating "HELPFUL | NOT_HELPFUL | INCORRECT"
        text comment
    }
    ANOMALY_FLAG {
        uuid id PK
        uuid claim_id
        varchar anomaly_type
        varchar detector
        int severity
        jsonb details
        varchar status "OPEN | INVESTIGATING | DISMISSED | CONFIRMED"
        uuid explanation_assessment_id FK
        uuid resolved_by
        text resolution_note
    }
    KNOWLEDGE_DOCUMENT {
        uuid id PK
        varchar source_type
        uuid product_version_id
        varchar title
        varchar document_version
        varchar status "INGESTING | ACTIVE | SUPERSEDED | FAILED"
        uuid source_document_id
        date effective_from
    }
    KNOWLEDGE_CHUNK {
        uuid id PK
        uuid knowledge_document_id FK
        text content
        jsonb metadata
        vector embedding
    }
    AI_ASSESSMENT ||--o{ AI_EVIDENCE : "is grounded by"
    AI_ASSESSMENT ||--o{ AI_TOOL_INVOCATION : "called"
    AI_ASSESSMENT ||--o{ AI_FEEDBACK : "is rated by"
    AI_ASSESSMENT ||--o| AI_ASSESSMENT : supersedes
    ANOMALY_FLAG }o--o| AI_ASSESSMENT : "is explained by"
    KNOWLEDGE_DOCUMENT ||--|{ KNOWLEDGE_CHUNK : "is split into"
```

## Audit and Messaging

```mermaid
erDiagram
    AUDIT_EVENT {
        bigint id PK
        timestamptz occurred_at
        varchar actor_type "USER | SERVICE | SYSTEM | AI"
        varchar actor_id
        varchar on_behalf_of
        varchar action
        varchar subject_type
        varchar subject_id
        jsonb details
        varchar correlation_id
    }
    OUTBOX_EVENT {
        bigint id PK
        uuid event_id UK
        varchar aggregate_type
        varchar aggregate_id
        varchar routing_key
        jsonb payload
        timestamptz created_at
        timestamptz published_at
        int attempts
    }
    PROCESSED_MESSAGE {
        uuid message_id PK
        varchar consumer PK
        timestamptz processed_at
    }
```

These tables have no foreign keys by design: audit and messaging records must survive deletion or archival of the records they describe.
