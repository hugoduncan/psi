# 229 — Author-routed workflow exhaustion + review-did-not-converge handback

## Intent (why)

Today, when a judged workflow loop exhausts its `:max-iterations`, the runtime
hardcodes exhaustion to a hard failure: the judge's `evaluate-routing` returns
`{:action :fail :reason :iteration-exhausted}`, which marks the run `:failed`.
The whole workflow run dies. There is no way for a workflow author to say "if
this loop never converges, route somewhere clean instead of crashing".

Concretely: `review-task-design` loops `design-review ↔ design-follow-up` up to
`:max-iterations 3`. If the design never converges, the run dies with a hard
failure, and `task-lifecycle` either crashes or (worse) has no clean place to
stop. We want an unconverged design review to **stop the lifecycle at the design
stage and hand control back to the human** with a useful summary — not proceed
to plan on a bad design, and not hard-fail.

This requires two layered changes:

1. **Engine**: let routing directives name an author-chosen exhaustion target
   (`:on-max-iterations`) instead of always routing exhaustion to `:failed`.
2. **Workflows**: use that capability so `review-task-design` (and
   `review-task-plan`) route non-convergence to a clean
   "did-not-converge" summary, and `task-lifecycle` gates on the design-review
   (and plan-review) outcome — mirroring the existing
   `check-implementation-review-status` → `final-summary-without-extraction`
   pattern — to stop and hand back rather than continue.

## Context (current behaviour)

Routing flows through four layers in `components/workflow-runtime`:

- `model.clj` `routing-directive-schema` — schema for the **authored** EDN
  routing directive: `{:goto <target> (:max-iterations N)?}`. `:goto` is
  `[:or [:enum :next :previous :done] :string]`.
- `target_ir_compiler.clj` `compile-routing-table` — translates an authored
  routing table into IR directives (currently copies `:goto` and, when present,
  `:max-iterations`).
- `ir.clj` `routing-directive-schema` — IR schema: `{:goto <target>
  (:max-iterations N)?}`.
- `workflow-judge.clj` `evaluate-routing` — **the runtime-governing exhaustion
  site for judged loops** (DI-6 in plan.md). On `:judge/enter`, the matched
  directive is evaluated here first: when the target step's iteration count ≥ the
  directive's `:max-iterations` (`check-iteration-limit` → `:exhausted`), it
  returns `{:action :fail :reason :iteration-exhausted}`, which is enqueued as
  `:judge/failed` and marks the run `:status :failed`. This short-circuits
  **before** any `:judge/signal` is enqueued, so for the review workflows this
  task targets the statechart `:judge/signal` exhaustion guard (below) never
  fires — `evaluate-routing` is the edit that actually governs runtime exhaustion.
- `statechart.clj` `compile-routing-transitions` — when a directive has
  `:max-iterations`, it emits **two** guarded `:judge/signal` transitions for
  that signal: the success path (`iter-count < max`) to the goto target, and the
  exhaustion path (`iter-count >= max`) hardcoded to `:target :failed`.
  `judged-routing-transition` then detects the `:failed` target and dispatches
  the `:iteration/exhausted` action (`:reason :iteration-limit-reached`). This is
  the second, parallel exhaustion site; it is dead code for the judged review
  loops (see `evaluate-routing` above) but is kept coherent with `:on-max-iterations`.

`pass-status-routing` (`components/agent-session/.../workflow/routing.clj`) maps
a `PASS_STATUS:` line value to a `DONE`/`REPEAT` route via the `status->route`
table (`REVIEW_COMPLETE`/`IMPLEMENTATION_COMPLETE` → `DONE`;
`ACTIONABLE_FEEDBACK`/`MORE_WORK_REMAINS` → `REPEAT`). `task-lifecycle`'s
existing `check-implementation-review-status` invoke-step uses this to route
`review-task-implementation`'s yield to either `extract-task-knowledge` (DONE) or
`final-summary-without-extraction` (REPEAT).

`review-task-design` / `review-task-plan` final-summary steps currently emit a
prose summary and are explicitly told **not** to emit a control token / PASS_STATUS.

## Scope

In scope (single repo, `exhaustion-routing` worktree):

### A. Engine — author-routed exhaustion target

1. **`ir.clj`** — add an optional `:on-max-iterations` key to
   `routing-directive-schema`, valued like `:goto`
   (`[:or [:enum :next :previous :done] step-name-schema]`). Only meaningful when
   `:max-iterations` is present, and a cross-field constraint **rejects**
   `:on-max-iterations` without `:max-iterations` (D3 below) — express as
   `[:and <directive-map> [:fn (fn [d] (or (not (contains? d :on-max-iterations)) (:max-iterations d)))]]`
   with a clear error.
2. **`model.clj`** — mirror the same optional `:on-max-iterations` key and the
   same reject-without-`:max-iterations` constraint on the authored
   `routing-directive-schema` so workflow EDN can declare it and pass model
   validation. (Without this, the authored `.edn` consumers below cannot
   validate.)
3. **`target_ir_compiler.clj`** `compile-routing-table` — thread
   `:on-max-iterations` from the authored directive into the IR directive when
   present.
4. **Runtime exhaustion routing — two sites (DI-6).** When a directive carries
   `:max-iterations` and `:on-max-iterations`, resolve the author target via the
   same goto→target resolution used for `:goto`
   (`:next`/`:previous`/`:done`/step-name); when `:on-max-iterations` is absent,
   preserve current hard-fail behaviour. This must be applied at **both** sites:
   - **`workflow-judge.clj`** `evaluate-routing` (**runtime-governing** for
     judged loops): when `check-iteration-limit` is `:exhausted` **and** the
     matched directive carries `:on-max-iterations`, return
     `{:action :goto :target …}` (or `{:action :complete}` for `:done`) via the
     existing `resolve-goto-target` logic instead of
     `{:action :fail :reason :iteration-exhausted}`. The non-`:fail` action is
     enqueued as `:judge/signal` → `:judge/record` `:goto` branch → routes to the
     author target with `:status :running` (never marked failed). Absent
     `:on-max-iterations` → unchanged `{:action :fail :reason :iteration-exhausted}`.
   - **`statechart.clj`** `compile-routing-transitions` (parallel site, kept
     coherent): compute the same exhaustion-path target for the `:judge/signal`
     exhaustion guard (author target when present, else `:target :failed`).
   **No change to `judged-routing-transition`** (D2 below): it already selects the
   dispatch action purely from whether the transition target is `:failed` —
   `:failed` → `:iteration/exhausted`; an author target (≠ `:failed`) →
   `:judge/record`.
5. **Tests** for ir/model schema acceptance **and rejection** (D3), the
   `target_ir_compiler` threading, the `statechart` `:judge/signal` routing, and
   — because the pure-statechart test bypasses `evaluate-routing` and so cannot
   detect the governing path (DI-6) — an **integration-level** assertion that a
   real exhausted judged loop with `:on-max-iterations` routes to the author
   target with `:status :running` (not `:iteration-exhausted`), and without it
   still hard-fails `:iteration-exhausted`. Extends `ir_test`, `model_test`,
   `target_ir_compiler_test`, `statechart_test`, plus a `workflow-judge` /
   review-step-routing integration test.

### B. Workflows — review-did-not-converge handback

6. **`review-task-design.edn`** — `design-follow-up`'s `:on` gains
   `:on-max-iterations` pointing at a new clean summary step
   (`final-summary-not-converged`) that produces a "design review did not
   converge" user-facing summary. Per D1, the converged `final-summary` emits a
   required `PASS_STATUS: REVIEW_COMPLETE` line and the not-converged summary
   emits `PASS_STATUS: ACTIONABLE_FEEDBACK`. The summary-template wording follows
   the strict single-line PASS_STATUS contract in plan.md DI-4 — it does **not**
   blanket-"replace" the existing anti-control-token guards: **keep** the
   prose-body guard ("respond with a concise summary, not a control token") and
   **rewrite** the anti-`PASS_STATUS` guard into the precise rule (end with
   exactly one column-0 `PASS_STATUS: <TOKEN>` line, and do not echo the
   `PASS_STATUS:` lines carried in the contributed review replies — avoiding the
   `:ambiguous-pass-status` failure). Standalone-output behaviour is governed by
   D5.
7. **`review-task-plan.edn`** — identical treatment (`plan-follow-up`
   `:on-max-iterations` → not-converged summary; `PASS_STATUS: REVIEW_COMPLETE`
   on the converged summary, `PASS_STATUS: ACTIONABLE_FEEDBACK` on the
   not-converged summary).
8. **`task-lifecycle.edn`** — add a `check-design-review-status` invoke-step
   gate after `review-task-design`, mirroring `check-implementation-review-status`
   (`workflow/pass-status-routing`, `allowed-statuses ["ACTIONABLE_FEEDBACK"
   "REVIEW_COMPLETE"]`):
   - DONE (`REVIEW_COMPLETE`, converged) → proceed to `create-task-plan`;
   - REPEAT (`ACTIONABLE_FEEDBACK`, did not converge) → route to
     `final-summary-design-not-converged`, which hands back to the human
     **without proceeding to plan and without hard-failing** (`:goto :done`).
   Add the analogous `check-plan-review-status` gate after `review-task-plan`
   (DONE → `implement-task`; REPEAT → `final-summary-plan-not-converged`). Per
   D4, the two handback summaries are **distinct per-stage steps** (mirroring the
   existing distinct `final-summary-after-extraction` /
   `final-summary-without-extraction`), each with stage-appropriate
   contributions and no knowledge extraction.
9. **Tests/coherence** — workflow-loader / definition tests locking the new
   routing in the three EDN definitions; docs (`doc/workflows.md`) and CHANGELOG
   updated for the new `:on-max-iterations` directive key and the lifecycle gate
   behaviour.

Out of scope:

- No change to actor-retry / `:max-attempts` semantics.
- No change to the `:iteration/exhausted` accounting itself (only where the
  exhaustion transition is routed).
- No new generic routing operations unless planning shows one is required.

## Key design decisions (settled)

- **D1 — PASS_STATUS reuse.** Reuse existing `REVIEW_COMPLETE` (converged →
  DONE → proceed) and `ACTIONABLE_FEEDBACK` (not converged → REPEAT → handback).
  `routing.clj` `status->route` is untouched; the lifecycle gate matches the
  implementation-review gate exactly. The status is structurally determined by
  which summary step ran (each template hardcodes its `PASS_STATUS:` line), so
  the LLM only echoes a literal line. Rejected alternative: design/plan-specific
  statuses (would push workflow-specific labels into shared `routing.clj`).
- **D2 — Exhaustion dispatch.** No change to `judged-routing-transition`. It
  already selects dispatch from whether the target is `:failed`: author-routed
  exhaustion (target ≠ `:failed`) uses `:judge/record` and does not mark the run
  failed; default exhaustion (`:failed`) keeps `:iteration/exhausted`. The
  **runtime-governing** edit is in `workflow-judge.clj` `evaluate-routing` (DI-6);
  the parallel `compile-routing-transitions` edit keeps the statechart
  `:judge/signal` site coherent. (Originally this decision read "the only
  statechart edit"; that under-described the change set — corrected per DI-6. The
  *decision* — `judged-routing-transition` untouched — is unchanged.)
- **D3 — `:on-max-iterations` requires `:max-iterations`.** Reject at schema
  (both `model.clj` and `ir.clj`) via a cross-field `:fn` constraint, rather
  than silently ignoring it. Avoids a footgun where a typo'd-away
  `:max-iterations` silently disables exhaustion routing; aligns with
  `impossible_invalid_states`.
- **D4 — Per-stage handback summaries.** `task-lifecycle` gets distinct
  `final-summary-design-not-converged` and `final-summary-plan-not-converged`
  steps (mirroring the existing distinct `final-summary-after-extraction` /
  `final-summary-without-extraction`), because context availability differs per
  stage (no plan yet at the design stage). Rejected alternative: one shared
  lowest-common-denominator stop step.
- **D5 — Standalone output.** Adding the `PASS_STATUS:` line changes the
  standalone `/delegate review-task-design`/`-plan` output, and the two standalone
  cases differ (the standalone result-text path reads `(last :step-order)`; the
  converged `final-summary` is ordered last — see plan.md DI-2/R5):
  - **Converged standalone** → surfaces the converged `final-summary`'s
    `PASS_STATUS: REVIEW_COMPLETE`. Accepted as useful.
  - **Not-converged standalone** → the never-run converged `final-summary` is
    still last, so result text is **empty** (the `final-summary-not-converged`
    `PASS_STATUS: ACTIONABLE_FEEDBACK` is not surfaced). Accepted as a known
    **degradation**, not a feature: the non-convergence handback is a
    lifecycle-only concern, consumed via the order-independent lifecycle
    delegate-gate path (reverse-scan), which resolves correctly. Fixing the
    shared `execute-workflow-run` last-step resolution is out of scope (plan.md R5).

- **Workflow-runtime boundary (invariant).** `:on-max-iterations` is a generic
  parameterized primitive (an author-supplied goto-target for the exhaustion
  edge) — it belongs in runtime code. The concrete targets, summary step names,
  and PASS_STATUS strings are authored policy living in the `.edn` definitions
  and prompts, not in `compile-routing-transitions`.

## Acceptance criteria

- AC-1: An authored routing directive may declare
  `{:goto X :max-iterations N :on-max-iterations Y}`; it validates in both
  `model.clj` and `ir.clj` schemas, and `{... :on-max-iterations Y}` **without**
  `:max-iterations` is **rejected** by both schemas with a clear error (D3).
- AC-2: `target_ir_compiler` threads `:on-max-iterations` from authored model to
  IR.
- AC-3: Runtime exhaustion routes to the resolved `:on-max-iterations` target
  when present, and hard-fails when absent, at **both** sites — the governing
  `evaluate-routing` (`:exhausted` + `:on-max-iterations` → `:action :goto`/`:complete`,
  `:status :running`; else `:action :fail :reason :iteration-exhausted`) and the
  parallel `compile-routing-transitions` `:judge/signal` guard (author target,
  else `:failed` + `:iteration/exhausted`) — verified at integration level for
  the governing path (DI-6).
- AC-4: `review-task-design` routes a non-converging design loop to a clean
  "design review did not converge" summary that emits a routable `PASS_STATUS`;
  no hard failure.
- AC-5: `review-task-plan` has the equivalent non-convergence handback.
- AC-6: `task-lifecycle` stops at the design stage (and at the plan stage) and
  hands back to the human when the corresponding review did not converge, and
  proceeds normally when it did — verified by definition/loader tests.
- AC-7: Focused workflow-runtime + workflow-loader Scry suites green; clj-kondo
  clean; `doc/workflows.md` + CHANGELOG updated.
