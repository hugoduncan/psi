# 227 — review-task-design multi-prompt exemplar

## Status

Design — ready once task **226 (multi-prompt workflow steps)** lands. This task
is the first consumer of 226's capability and validates the multi-prompt grammar
against a real authored workflow. Do not plan/implement before 226's grammar is
real.

## Depends on

- **226** — multi-prompt `:session` steps (`:prompts` queue, per-prompt named
  groups, per-prompt text output addressing, post-drain single judge). This task
  uses those surfaces; it adds no new runtime capability.

## Intent

Rewrite `.psi/workflows/review-task-design.edn` so the three review phases —
`architecture-review`, `ambiguity-review`, `inconsistency-review` — run as **one
multi-prompt `:session` step** (three back-to-back turns in one shared child
session) instead of three separate session steps. The task design + architecture
sources are assembled into the conversation **once** (turn 1) and reused by the
later review turns via the shared live session, for token efficiency and shared
conversational context across the three reviews.

## Problem

Today each review phase is its own `:session` step with its own child session,
so each phase independently re-reads `design.md` and the architecture sources
(`AGENTS.md`, `META.md`, `doc/architecture.md`). That re-embeds the same source
material three times per review pass. The phases are also independent
conversations: the ambiguity/inconsistency reviews cannot see the
architecture review's reasoning.

## This is a deliberate topology redesign, not a faithful refactor

The live `review-task-design` topology is per-phase
**review → follow-up (mutates `design.md`/`design-steps.md`) → next review**:
each `*-follow-up` executes the recorded items and mutates the artifacts
*before* the next phase reviews. A multi-prompt step is architecturally *N turns
in one session → one post-drain route* (226), so it **cannot** reproduce the
interleaved per-phase follow-up. The merge therefore deliberately **trades**
interleaved per-phase follow-up for **batch-review-then-follow-up**:

- the three reviews run as three turns in one shared session against the **same
  un-followed-up** design (each later review sees the earlier reviews' replies in
  the live session context);
- the three per-phase `*-follow-up` steps **collapse into a single**
  `design`-profile follow-up step placed *after* the merged review step's
  post-drain route, executing the items accumulated across all three reviews in
  one batch.

Sanctioned by `λα. ¬compat(backward)` (topology change allowed; behaviour
preservation is not a goal). The "faithful to the live three-phase topology"
framing is explicitly **not** claimed.

## Routing (post-drain)

The merged step's judge routes on the **post-drain step result's per-prompt
reply outputs**, reusing the **existing** `workflow/pass-feedback-routing`
operation. Each review prompt emits a `PASS_STATUS` token; the judge takes one
`*-text` arg per prompt:

```clojure
{:architecture-text  {:from {:step "design-review" :prompt "architecture"   :output :final-llm-reply}}
 :ambiguity-text     {:from {:step "design-review" :prompt "ambiguity"      :output :final-llm-reply}}
 :inconsistency-text {:from {:step "design-review" :prompt "inconsistency"  :output :final-llm-reply}}}
```

REPEAT while any prompt returned `ACTIONABLE_FEEDBACK`; DONE (→ `final-summary`)
when all are `REVIEW_COMPLETE`. Because all three phases are merged, this is
**exactly** the three-key disjunction the live `clarity-status` judge already
computes via `pass-feedback-routing` over the unmerged phase outputs — the
routing computation is preserved even though the interleaved follow-up structure
is not.

**Why not filesystem-state routing.** An alternative routed on whether
`design-steps.md` still has unchecked `- [ ]` items via a new
`workflow/open-checklist-items-routing` re-reading the file. Rejected: routing on
mutable external file state makes the decision depend on data **outside** the
workflow data-flow / event log, fighting `doc/workflows.md`'s deliberate design
that `clarity-status` "remembers whether any phase returned `ACTIONABLE_FEEDBACK`
from the phase outputs rather than re-reading task artifacts," and the VSM
`∀change → event → log → replayable` ethos (the judge normalizes a *step result*,
not a filesystem snapshot). Per-prompt reply routing keeps the merged exemplar
replay-faithful and deterministic and introduces no new routing operation.

## `final-summary` migration

The surviving `final-summary` step currently consumes the three phases via three
`{:step "<phase>-review" :yield :text}` contributions. The merge eliminates those
three step names, and `:yield` cannot recover per-phase text (per-prompt `:yield`
is invalid under 226; the merged step-level `:yield :text` resolves only to the
last prompt's reply). So `final-summary`'s three contributions migrate to
per-prompt `:output` refs:

```clojure
{:step "design-review" :prompt "architecture"   :output :final-llm-reply}
{:step "design-review" :prompt "ambiguity"      :output :final-llm-reply}
{:step "design-review" :prompt "inconsistency"  :output :final-llm-reply}
```

This is the only legal per-phase text addressing after the merge, and reuses the
same per-prompt reply addressing the post-drain routing already requires — no new
surface.

## Scope

In scope:

- Rewrite `review-task-design.edn`: replace the three review steps + three
  `*-follow-up` steps with one multi-prompt `design-review` step (three named
  prompt-groups reusing the existing
  `review-task-design-{architecture,ambiguity,inconsistency}-review.md` prompts)
  + one post-route `design`-profile follow-up step.
- Wire the post-drain `pass-feedback-routing` judge over the three per-prompt
  reply outputs; preserve the REPEAT pass loop (`:max-iterations` as today).
- Migrate `final-summary` contributions to per-prompt `:output` refs.
- Update the review prompts only as needed for the shared-session, single-read
  framing (e.g. later reviews need not re-read sources already loaded).
- Workflow-loader / definition tests for the merged topology; update
  `doc/workflows.md`.

Out of scope:

- Any change to the multi-prompt runtime capability (that is 226).
- The plan/ and implementation review-workflow analogues (`review-task-plan`,
  `review-task-implementation`) — separate follow-ons if the exemplar proves out.

## Acceptance criteria

1. `review-task-design.edn` runs the three reviews as three turns of one
   multi-prompt `design-review` step in one shared child session; the design +
   architecture sources are assembled into the conversation once (turn 1).
2. Post-drain routing reuses `pass-feedback-routing` over the three per-prompt
   reply outputs; REPEAT iff any phase returned `ACTIONABLE_FEEDBACK`, with the
   same pass-loop bound as today.
3. A single `design`-profile follow-up step after the route executes the items
   accumulated across all three reviews.
4. `final-summary` recovers all three phases' text via per-prompt
   `{:step "design-review" :prompt "<phase>" :output :final-llm-reply}` refs.
5. Workflow-loader/definition tests cover the merged topology and routing;
   `doc/workflows.md` describes the batch-review-then-follow-up shape and notes
   the deliberate departure from interleaved per-phase follow-up.
