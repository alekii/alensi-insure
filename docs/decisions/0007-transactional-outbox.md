# ADR-0007: Domain Events Published Through a Transactional Outbox

- **Status:** Accepted
- **Date:** 2026-11-22

## Context

Flow processes, the intelligence worker, and other consumers react to Insure's domain events. If Insure commits a claim and then fails to publish `insure.claim.submitted`, no review process starts and the claim is silently stuck. If it publishes and then the transaction rolls back, Flow works on a claim that does not exist.

## Decision

- Events are written to `messaging.outbox_event` **in the same database transaction** as the state change.
- A relay (in the API profile, single active instance via a PostgreSQL advisory lock) publishes unpublished rows in `id` order to the `insure.events` exchange with publisher confirms, then sets `published_at`.
- Delivery is **at-least-once**. Every event has a unique `id` used as the AMQP `message-id`.
- Consumers — Insure's own and other services' — de-duplicate. Insure records consumed message IDs in `messaging.processed_message` in the same transaction as the effect.
- Published rows are deleted after 7 days.
- Spring Modulith's event publication registry may be used to implement this, provided it gives the same guarantees.

## Consequences

- No lost or phantom events.
- Consumers must be idempotent — a requirement for RabbitMQ consumers anyway.
- Event latency is the relay interval (≤ 1 s).
- Event order is preserved per relay, which keeps per-claim events ordered for consumers that process sequentially.

## Alternatives Considered

- **Publish after commit (`@TransactionalEventListener(AFTER_COMMIT)` to RabbitMQ).** Loses events if the process dies between commit and publish.
- **Change data capture (Debezium).** Robust but adds Kafka Connect infrastructure the platform does not otherwise need.
