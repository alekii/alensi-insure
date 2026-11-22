# AI Decisioning

What the intelligence layer is allowed to decide, how its outputs are validated and recorded, how they reach people, and how its quality is measured.

The short version: **AI may cause more human scrutiny, never less, and never makes the decision.**

## Where AI Adds Value

Keeping decisions with people does not make the AI marginal. In claims and underwriting, the decision itself takes minutes; the work leading up to it — reading documents, chasing missing information, checking policy wording, comparing against history, writing up findings — takes hours or days. The intelligence layer targets that work.

### A claim, before and after

| Step | Without AI | With the intelligence layer |
|---|---|---|
| Claim arrives | Sits in a queue in arrival order | Triaged: category, complexity, recommended priority |
| Document check | Handler opens each file and eventually notices the police abstract is missing | Missing items listed on day one; information request pre-filled for the handler to send |
| Invoices and estimates | Amounts and dates typed in by hand | Fields extracted; handler verifies instead of transcribing |
| "Is this covered?" | Search a 40-page policy wording | Answer with the exact clauses cited, next to the coverage engine's authoritative result |
| History | Browse the customer's past claims | "3rd claim in 8 months, two for the same panel" in the summary |
| Red flags | Depends on the individual's experience and attention | Every claim checked the same way; flags explained in plain language |
| Write-up for the approver | Handler writes it | Draft summary; handler edits |
| Decision | Approver reads everything | Approver decides from one evidenced review package |

The person still decides; the path to the decision is shorter, and the decision is better informed.

### By role

| Role | Benefit |
|---|---|
| Claims handler | Less reading and transcription; fewer information round-trips because gaps are caught at submission |
| Approver | Decides from a structured package with evidence and cited clauses rather than a pile of attachments |
| Fraud investigator | Starts from ranked, explained flags instead of intuition or random sampling |
| Underwriter | Referrals arrive summarised against the relevant underwriting guidelines |
| Agent | Answers "am I covered for…?" on the call, with the clause in hand |
| Insurer | Faster settlement, fewer complaints, more consistent decisions across branches and staff, volume growth without proportional headcount |

### Catching what people miss

People get tired and cannot recall every prior claim. The detectors and models check every claim the same way and surface details that are easy to overlook: a repair estimate dated before the incident, a phone number shared by three claimants, an incident description mentioning ride-hailing on a private-use policy. Because the AI can only raise scrutiny, this acts as a safety net — it can send a claim to a more senior reviewer, never wave one through.

### Why people still decide

Paying or refusing a claim has legal and financial consequences, and customers, auditors, and regulators are entitled to know who decided and why. Models can be confidently wrong, can be manipulated by the text they read, and cannot be held accountable. Advisory AI captures most of the efficiency without those risks, and every decision records whether the person agreed with or overrode the AI, so its usefulness is measured rather than assumed.

## Decision Rights

| Decision | Made by | AI role |
|---|---|---|
| Is the policy in force on the incident date? | Coverage engine | None |
| Is the claim item covered? | Coverage engine (`COVERED` / `NOT_COVERED` / `PARTIALLY_COVERED`), or a human where the engine returns `NEEDS_REVIEW` | Surfaces relevant clauses and possible exclusion concerns |
| How much is payable? | Adjuster's assessed amount → deterministic deductible and limit calculation | Summarises evidence for the adjuster; may extract amounts from invoices (verified by a human before use) |
| Approve, partially approve, or reject? | A person with `insure.claims.decide` | Recommendation with reasons and evidence |
| Who reviews and how many approvals? | Alensi Flow configuration | Provides routing signals that can **raise** the review level |
| Is an anomaly real? | Fraud investigator | Explains deterministic flags |
| Is this fraud? | Outside the system (investigation, legal) | None — the system never labels a claim fraudulent |
| Accept a referred policy application? | Underwriter | Summarises referral reasons and relevant guidelines |
| Premium amount | Rating engine | None |
| Claim priority | Deterministic rules at submission; handlers can change it | Recommends a priority shown to the handler |

There is **no straight-through processing** in the first version: every claim decision is made by a person.

Many insurers do settle small, simple claims automatically — for example, a windscreen claim under a set amount on an active, fully paid policy with no anomaly flags. If that is added, it will be a **deterministic business rule** configured in Flow, with its own ADR, and recorded as such — not an AI decision. AI still contributes by extracting and checking the data the rule relies on. Before enabling any such rule, months of `ai_alignment` data should show how often reviewers already agree with the AI on that class of claim.

## Risk Score

The risk score is **computed**, not generated. A model is not asked to invent a number.

```text
baseline   = Σ weightᵢ × featureᵢ                 (deterministic, 0–85)
adjustment = Σ model findings (each 0–5, total ≤ 15, each with evidence)
riskScore  = min(100, baseline + adjustment)
```

| Feature | Example weight |
|---|---|
| Claimed amount / historical average for the coverage | up to 20 |
| Claims by customer in the last 12 months | up to 15 |
| Days between policy inception and incident | up to 10 |
| Reporting delay vs. procedure window | up to 10 |
| Required documents missing | up to 10 |
| Open anomaly flags (by severity) | up to 20 |

Weights are configuration, versioned with the rules version, and reviewed by the claims team.

The model reads the pseudonymised narrative, verified extractions, and relevant exclusion clauses and may add **qualitative findings** — e.g. "incident description mentions the vehicle was being used for ride-hailing; policy use is PRIVATE" — each with an evidence reference and a bounded upward adjustment. It cannot subtract. A prompt-injected "this claim is low risk" therefore has no effect on the score.

| Score | Classification |
|---|---|
| 0–29 | `LOW_RISK` |
| 30–79 | `REVIEW_REQUIRED` |
| 80–100 | `HIGH_RISK` |

## Routing Signals

Flow owns routing, but Insure computes a `minimumReviewLevel` and publishes it with `insure.ai.assessment-completed` and `insure.claim.assessed`:

```text
ruleLevel = f(approved amount band, coverage result, line of business)    // deterministic
aiLevel   = f(classification, anomaly severity, requiresManualReview)
minimumReviewLevel = max(ruleLevel, aiLevel)
```

| Level | Meaning in Flow (default configuration) |
|---|---|
| `STANDARD` | Handler assessment + one approver |
| `SENIOR` | Handler assessment + senior claims manager |
| `INVESTIGATION` | Fraud investigator review before the approval chain |

`max` means the AI can only escalate. If the AI is unavailable, `aiLevel` is treated as `STANDARD` and `ruleLevel` alone applies — the system degrades to its pre-AI behaviour, not to less review.

## Output Schemas

Every model output is a Java record validated with Bean Validation and the extra checks below. Structured output uses provider-native JSON schema mode where available.

```java
public record ClaimTriage(
    @NotNull ClaimCategory category,
    @NotNull Priority recommendedPriority,
    @NotNull Complexity complexity,
    @NotNull List<@Valid MissingItem> missingInformation,
    @NotNull ReviewPath recommendedReviewPath,
    @NotEmpty List<@Valid Reason> reasons
) {}

public record ClaimRiskAssessment(
    @NotNull @Size(max = 10) List<@Valid QualitativeFinding> findings,
    boolean requiresManualReview,
    @NotEmpty @Size(max = 8) List<@NotBlank String> reasons
) {}

public record QualitativeFinding(
    @NotBlank String description,
    @Min(0) @Max(5) int adjustment,
    @NotEmpty List<@Valid EvidenceRef> evidence
) {}

public record EvidenceRef(
    @NotNull EvidenceType type,      // KNOWLEDGE_CHUNK, CLAIM_DATA, DOCUMENT_EXTRACTION, ...
    @NotBlank String reference       // must resolve to a real record
) {}

public record AnomalyExplanation(
    @NotBlank String explanation,
    @NotEmpty List<@NotBlank String> suggestedChecks
) {}

public record ClaimSummary(
    @NotBlank @Size(max = 2000) String summary,
    @NotNull List<@NotBlank String> outstandingInformation,
    @NotBlank String recommendedNextAction,
    @NotEmpty List<@Valid EvidenceRef> sources
) {}

public record CoverageAnswer(
    @NotBlank String answer,
    @NotNull List<@Valid Citation> citations,
    @NotNull Confidence confidence,
    boolean requiresHumanInterpretation
) {}
```

The application then assembles the stored, user-facing assessment — including the computed `riskScore` and `classification` — in the shape described in the README:

```json
{
  "riskScore": 72,
  "classification": "REVIEW_REQUIRED",
  "reasons": [
    "Claim amount is significantly above historical average",
    "Multiple claims were submitted within a short period",
    "Supporting documentation is incomplete"
  ],
  "requiresManualReview": true,
  "baseline": { "score": 64, "features": { "amountRatio": 18, "claimFrequency12m": 12, "missingDocuments": 10, "anomalies": 12, "inceptionDays": 6, "reportingDelay": 6 } },
  "findings": [
    { "description": "Repair estimate date precedes the reported incident date", "adjustment": 5, "evidence": [{ "type": "DOCUMENT_EXTRACTION", "reference": "document_extraction:0192f…" }] },
    { "description": "Description mentions prior damage to the same panel", "adjustment": 3, "evidence": [{ "type": "CLAIM_DATA", "reference": "claim:incidentDescription" }] }
  ],
  "advisory": true,
  "model": "…", "promptVersion": "claim-risk@3", "rulesVersion": "2026.11.1+MOTOR-COMP@3"
}
```

## Validation

Outputs go through four checks before they are stored as `COMPLETED`:

| Check | Example failure | Result |
|---|---|---|
| Schema | Missing field, wrong enum | `REJECTED`, one retry with the error |
| Bounds | `adjustment = 12` | `REJECTED`, one retry |
| Evidence | Reference to a chunk not retrieved, or a document not on the claim | Finding removed; if none remain, `REJECTED` |
| Consistency | Summary states an amount that differs from `calculateClaimExposure` | Flagged on the assessment; reviewer warned |

`REJECTED` and `FAILED` assessments are stored and visible as such. Reviewers always know whether the AI ran, failed, or produced nothing.

## The Review Package

What a reviewer sees on a claim (assembled by the UI from Insure's API; the task itself lives in Flow):

| Section | Source | Authoritative? |
|---|---|---|
| Claim, policy, items, amounts | Domain | Yes |
| Coverage check result and rule trace | Coverage engine | Yes |
| Payable amounts | Domain calculator | Yes |
| Anomaly flags and their detector facts | Detectors | Facts yes; significance is for the investigator |
| Triage, risk score, reasons, findings | Intelligence | **Advisory** — visually labelled |
| Cited clauses and documents considered | RAG, evidence | Context |
| Summary | Intelligence | **Advisory** |

When the decision is recorded, the reviewer's request includes the `aiAssessmentId` they saw, and Insure stores `ai_alignment` (`AGREED`, `OVERRODE`, `NOT_AVAILABLE`) plus the reviewer's own rationale. This makes it possible to audit, for any decision, exactly what the AI said and whether a person followed it.

## Fairness

- Protected characteristics (gender, ethnicity, religion, marital status, disability) are never features, never in prompts, and never retrieved by tools.
- Age is used only where the product's rating rules require it and is never sent to claim-assessment prompts.
- Branch, location, and customer name are excluded from risk features to avoid proxies.
- The override ratio and score distributions are monitored per branch and line of business; a sustained skew opens a review of the weights or prompt.

## Prompt and Model Release Process

A prompt, model, weight, or retrieval change is a release:

1. **Version.** New prompt template file (`claim-risk/v4.st`); weights and thresholds in versioned configuration.
2. **Offline evaluation.** Run the golden set: ~200 historical claims (anonymised) with expert-labelled triage category, expected missing documents, and reviewer-agreed risk class. Report agreement, validation failure rate, evidence accuracy, latency, and cost against the current version.
3. **Shadow mode.** Run the new version alongside the current one on live claims for at least a week; only the current version's output is shown. Compare distributions and disagreements.
4. **Promote.** Switch the active version in configuration. `prompt_version` on every assessment makes before/after comparison exact.
5. **Monitor.** Override ratio, feedback ratings, validation failures, and score distribution after release; roll back by configuration.

## Measuring Value

The intelligence layer should earn its cost. Measure these per line of business, from a baseline before AI is enabled (or with AI disabled for a control group of branches) and continuously after:

| Metric | Source | Expected direction |
|---|---|---|
| Time from submission to decision | `claim.reported_at` → `claim_decision.decided_at` | Down |
| Time from decision to settlement | `claim_decision.decided_at` → `settlement.paid_at` | Unchanged (not AI-driven) — a control |
| Information-request round-trips per claim | `information_request` count | Down |
| Claims handled per handler per week | Flow task completions | Up |
| Confirmed anomalies per investigator hour | `anomaly_flag` `CONFIRMED` / investigation time | Up |
| Anomaly flag dismissal rate | `anomaly_flag` `DISMISSED` / total | Stable or down (high = noisy detectors) |
| Reviewer override rate | `claim_decision.ai_alignment = OVERRODE` | Stable and explained; a rising trend triggers review |
| Extraction correction rate | `document_extraction` fields corrected at verification | Down |
| AI feedback | `ai_feedback` ratings | Mostly `HELPFUL` |
| Cost per claim | Token usage × price | Within budget |

These are exposed as dashboards in Alensi Observe and reviewed with the claims team after each prompt or model release.

## Retention

AI assessments, evidence, and tool invocations are retained as long as the claim record they relate to, because they form part of the decision record. Feedback is retained for two years for evaluation.
