# ADR-0006: AI Outputs Are Advisory, Structured, and Recorded

- **Status:** Accepted
- **Date:** 2026-11-22

## Context

Insurance decisions — coverage, claim approval, payout amounts — have financial and legal consequences for customers and must be explainable to customers, auditors, and regulators. Large language models are useful for reading documents, summarising, and relating claims to policy wording, but they are non-deterministic, can be manipulated through the text they read, and cannot be held accountable.

## Decision

1. **AI never makes a business decision.** Coverage is decided by the deterministic coverage engine; claim outcomes by authorised people; premiums by the rating engine. There is no straight-through AI approval or rejection.
2. **AI may increase scrutiny, never decrease it.** Routing signals take the maximum of rule-based and AI-based review levels. Model findings can only raise a deterministic baseline risk score, by a bounded amount, with evidence.
3. **Outputs are structured.** Every model output used in a workflow maps to a validated Java record. Free-text output does not enter workflows.
4. **Outputs are evidenced.** Findings and answers must reference retrievable evidence (clauses, documents, claim data); unresolvable references are rejected.
5. **Everything is recorded.** Each assessment stores model, prompt version, input hash, output, evidence, and tool calls. Each decision records which assessment the decider saw and whether they agreed.
6. **Tools are read-only and scoped.** Models can read data about the record in scope only, as the actor in scope.
7. **Data is minimised.** Prompts are pseudonymised; PII never reaches the model provider.

Details: [AI Decisioning](../ai/ai-decisioning.md), [Tool Calling](../ai/tool-calling.md), [Intelligence Architecture](../ai/intelligence-architecture.md).

## Consequences

- Every decision can be reconstructed: the facts, the rule trace, what the AI said, and what the person decided and why.
- Prompt injection cannot change an outcome; at worst it adds noise a reviewer sees.
- The system works, at reduced convenience, when AI is unavailable.
- Efficiency gains are bounded: AI speeds up reading and preparation, not decisions. Any future automation of low-value decisions must be a deterministic, configured business rule with its own ADR.
- Prompt and model changes go through an evaluation and shadow-mode release process.

## Alternatives Considered

- **AI-generated risk score.** Rejected: unstable across runs, unexplainable, and manipulable by input text.
- **Auto-approve claims the AI rates low-risk.** Rejected for the first version: unacceptable accountability and fairness risk without a track record and regulatory review.
