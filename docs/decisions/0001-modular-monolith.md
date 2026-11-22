# ADR-0001: Modular Monolith with a Separate Worker Profile

- **Status:** Accepted
- **Date:** 2026-11-22

## Context

The README's planned architecture shows Policy, Claims, and Customer services behind a single Alensi Insure API. These are tightly coupled: a claim submission reads the policy, its coverages, and premium standing, and a decision must reserve coverage limits atomically. Splitting them into separately deployed services would turn local transactions into distributed ones (sagas, compensations) before there is any evidence the domain needs independent scaling or deployment.

The rest of the Alensi platform already provides the service boundaries that genuinely differ in responsibility and lifecycle: Identity, Flow, Pay, Recon, and Observe.

AI and document work is slow, bursty, and depends on external providers, unlike the API's CRUD and command traffic.

## Decision

Build Alensi Insure as **one Spring Boot application organised into modules** (`customer`, `product`, `policy`, `premium`, `claims`, `document`, `intelligence`, plus `platform` components), with:

- Module boundaries enforced by Spring Modulith verification in CI.
- One PostgreSQL schema per module; modules write only their own schema.
- Inter-module communication through each module's public `api` package and published events.
- The same artifact deployed twice: an **API profile** serving HTTP, and a **worker profile** consuming job queues and running schedulers.

## Consequences

- Policy, premium, and claim invariants (limit reservation, coverage in force) are enforced in local ACID transactions.
- One build, one deployment pipeline, one set of dashboards for the insurance domain.
- AI provider latency or outages cannot starve API threads; worker replicas scale on queue depth.
- Module discipline must be maintained deliberately — the verification test is the guardrail.
- If a module later needs independent deployment (most likely `intelligence`), its event-driven interface and separate schema make extraction straightforward.

## Alternatives Considered

- **Microservice per module.** Rejected: distributed transactions for limit reservation and coverage checks, more infrastructure, and no current scaling need.
- **Single module, no internal boundaries.** Rejected: the intelligence layer must be provably separable from the domain (see [ADR-0006](0006-ai-advisory-only.md)); without enforced boundaries that erodes.
- **Separate intelligence service from day one.** Deferred: the worker profile gives the operational isolation now; a network boundary can be added later without changing the event contract.
