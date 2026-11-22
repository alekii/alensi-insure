# ADR-0004: Payments Executed by Alensi Pay; Insure Tracks Status

- **Status:** Accepted
- **Date:** 2026-11-22

## Context

Insure needs to collect premiums, pay claim settlements, and refund premiums on cancellation. Alensi Pay is the platform's payment orchestration service: provider integrations (cards, mobile money, bank), routing, idempotency, retries, callbacks, and refunds. Alensi Recon reconciles Pay's transactions against provider statements and ledgers.

Holding payment instrument data in Insure would bring it into PCI-DSS scope and duplicate Pay.

## Decision

- **All money movement goes through Alensi Pay.** Insure never calls payment providers.
- Insure keeps a **`payment_reference`** per payment: direction, link to installment / settlement / adjustment, Pay's payment ID, an idempotency key, amount, and the latest status from Pay.
- Insure requests payments synchronously (`POST /payments` with `Idempotency-Key`) and receives outcomes asynchronously from Pay's events on RabbitMQ.
- Insure stores **no** card numbers, bank accounts, or mobile-money numbers for payouts. Payouts use Pay **beneficiary tokens**.
- Domain effects follow Pay's outcome: an installment is `PAID` and a policy activates only on `pay.payment.succeeded`; a claim is `SETTLED` only when its settlements are paid.
- Timeouts produce an `UNKNOWN` reference that is resolved by retrying with the same idempotency key or querying Pay — never by creating a second payment.

## Consequences

- Insure is out of PCI scope and has no provider-specific code.
- Premium and claim status depend on Pay's events arriving; consumers are idempotent and ordered by event timestamp.
- Recon exceptions can be traced to policies and claims via Pay's payment ID and the `metadata.sourceType/sourceId` Insure sends.
- Pay's API is not yet published. Insure's `pay-client` is written against the contract in the [API Specification](../api/api-spec.md#alensi-pay-contract) and adapted when Pay's spec exists.

## Alternatives Considered

- **Direct provider integration in Insure.** Rejected: duplicates Pay and brings PCI scope.
- **Polling Pay for status.** Rejected as the primary mechanism (latency, load); used only to resolve `UNKNOWN` references.
