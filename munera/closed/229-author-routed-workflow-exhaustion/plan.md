# 229 — Plan

Approach, sequencing, and decisions for implementing the author-routed workflow
exhaustion primitive and the review-did-not-converge handback. Reads `design.md`
as the authority for *what* and *why*; this file is *how*. Decision tags `D1–D5`
refer to the settled decisions in `design.md`.

## Strategy

Vertical slices, **engine before workflows**. Slice 1 lands the
`:on-max-iterations` primitive end-to-end through the four routing layers
**plus the judge-side `evaluate-routing` site that actually governs runtime
exhaustion for judged review loops** (DI-6) with its own unit and
integration-level tests, and is **behaviour-inert** for every existing workflow
(nothing authors it yet) — independently shippable and reviewable. Slices 2–3
then consume it (design review, then the symmetric plan review), each adding the
lifecycle gate and handback. Slice 4 is docs/coherence.

Each slice keeps the change `small` (one intent, one rule + test cluster) and
preserves behaviour for code paths it does not explicitly change.

## Implementation decision — terminal fall-through (DI-1)

A non-judged leaf session step routes `:actor/done → next-step-target`, which is
`:completed` **only when the step is last in `:steps` order**
(`statechart.clj` `next-step-target`). `review-task-design` /
`review-task-plan` currently rely on their `final-summary` being the last step
to terminate. Appending a second summary (`final-summary-not-converged`) would
make the converged `final-summary` no longer last, so its `:next` would fall
through into the not-converged summary — a silent wrong-path bug.

Resolution: make **both** summary steps in each review workflow explicitly
terminal using the established `task-lifecycle` idiom —
`:judge {:type :invoke :operation "workflow/constant-routing" :args {:route "DONE"}}`
plus `:on {"DONE" {:goto :done}}` — so termination is order-independent. This
also gives both summaries a judge turn, which is where the required
`PASS_STATUS:` line (D1) is produced and read by the lifecycle gate.

## Implementation decision — terminal-yield resolution for the two summaries (DI-2)

DI-1 makes both summaries explicitly terminal, fixing the internal `:next`
fall-through, but two distinct consumers read a completed review workflow's
terminal `:yield :text` and resolve "the terminal step" *differently*:

- **Lifecycle delegate-gate path** (`statechart_runtime/delegate.clj`
  `terminal-step-result-envelope` → `terminal_contract/terminal-result-envelope`):
  prefers `:terminal-outcome :result-envelope`, else *reverse-scans*
  `:step-order` for the last step with an `:accepted-result` — i.e. the
  **actually-executed** terminal step. Order-independent: surfaces whichever
  summary actually ran (converged `final-summary` or `final-summary-not-converged`).
- **Standalone `/delegate` result-text path**
  (`agent_session/mutations/canonical_workflows.clj` `execute-workflow-run`, and
  `terminal_contract/terminal-yielded-text`): keys strictly off
  `(last (:step-order …))` — the **last-defined** step, regardless of whether it ran.

Resolution (ordering, decided here): in both `review-task-design.edn` and
`review-task-plan.edn`, place `final-summary-not-converged` **before** the
converged `final-summary`, keeping the converged `final-summary` **last** in
`:steps`. Consequences, per consumer:
- **Converged run:** converged `final-summary` is both last-defined and
  actually-run → **both** paths surface its `PASS_STATUS: REVIEW_COMPLETE` text.
  (This is the guarantee the review item requires, and satisfies D5.)
- **Not-converged run (lifecycle):** `task-lifecycle` consumes the handback only
  through the delegate-gate path (reverse-scan), which surfaces
  `final-summary-not-converged`'s `PASS_STATUS: ACTIONABLE_FEEDBACK` correctly,
  independent of order.
- **Not-converged run (standalone `/delegate review-task-design`/`-plan`):** the
  strict `(last :step-order)` path resolves to the never-run converged
  `final-summary` → empty result text. This degraded standalone-not-converged
  edge is **accepted** (R5): the non-convergence handback is a lifecycle concern;
  making `execute-workflow-run` resolve the last *executed* step would change the
  shared standalone result-text behaviour for every workflow (broader blast
  radius, out of this task's scope).

Test (Slice 2/3) — converged-standalone result-text construction (resolves the
loop-4 ambiguity that the original note conflated the synthetic proof harness
with the real loaded `.edn`): lock the **converged standalone result text**, not
just definition-level routing.

**Scope of what this runtime test does and does NOT lock (loop-5 correction).**
This runtime test stubs `psi.agent-session.turn/prompt-execution-result-in!` —
i.e. the **model's response** — for the converged `final-summary` turn. The
`PASS_STATUS: REVIEW_COMPLETE` line it asserts is therefore whatever the **stub
returns**, not what the template instructs: the `final-summary` template text is
the *prompt* sent to the model, never the model's *output*, so stubbing the
output bypasses the template entirely. Consequently this runtime test **cannot**
lock the DI-4 template **wording** (a synthetic def with a bare `"final-summary"`
template would yield the identical assertion). What it *does* lock is the
**summary-step ordering + standalone plumbing invariant**: that the converged
`final-summary`, ordered last per DI-2, is the step whose yielded text surfaces
through the standalone `(last :step-order)` path as `:psi.workflow/result` (and
not the never-run not-converged summary). The **DI-4 template text** is locked
separately and authoritatively by the **definition-level**
`review-task-design-test` / `review-task-plan-test` assertion specified in DI-4
(point 4). The test MUST be built as follows, disambiguating the three coupled
points the loop-4 review raised:

- **(a) Definition — load the real `.edn`, not the synthetic proof def.** The
  purpose of this runtime test is to lock the **ordering/plumbing** invariant
  (above) against the real, ordered definition: that the converged
  `final-summary` ordered last per DI-2 in the real
  `.psi/workflows/review-task-design.edn` / `review-task-plan.edn` is the step
  whose yielded text reaches `:psi.workflow/result` via the standalone
  `(last :step-order)` path. So the test MUST load `review-task-design.edn`
  (resp. `review-task-plan.edn`) via the workflow-loader and register it for the
  run, exercising the real step ordering rather than a synthetic def that could
  drift from the authored order. It MUST NOT reuse the synthetic
  `conditional-review-design-definition` / `-plan-definition`
  (`review-task-design-proof` etc.) from `workflow_review_step_routing_test`,
  whose `:steps` ordering is not the authored review-workflow ordering. (Loading
  the real `.edn` does **not** by itself lock the DI-4 template wording, because
  the model reply is stubbed — that wording is locked by the DI-4 point-4
  definition-level template-text assertion instead.)
- **(b) Execution entry point — drive through `execute-workflow-run`, stubbing at
  `prompt-execution-result-in!`.** `:psi.workflow/result` is produced by the
  `canonical_workflows/execute-workflow-run` mutation, whose
  `:execute-workflow-run-fn` is `workflow-execution/execute-run!` (the default
  real actor-turn path that calls `psi.agent-session.turn/prompt-execution-result-in!`).
  The test MUST therefore bypass the harness's custom
  `:workflow-execute-actor-turn-fn` (`execute-conditional-review-proof!`) entirely
  and instead `with-redefs` `psi.agent-session.turn/prompt-execution-result-in!`
  while invoking the `execute-workflow-run` mutation (or its
  `:execute-workflow-run-fn`) so the produced `:psi.workflow/result` is exercised
  on the real mutation path.
- **(c) Multi-prompt convergence — stub every per-prompt turn.** The real
  `design-review` is a 3-prompt step (`architecture-review`, `ambiguity-review`,
  `inconsistency-review`) and `plan-review` a 2-prompt step
  (`ambiguity-review`, `inconsistency-review`), judged by
  `workflow/pass-feedback-routing`, which routes DONE only when **every**
  per-prompt `:final-llm-reply` carries exactly one `PASS_STATUS: REVIEW_COMPLETE`
  line. The stub MUST supply a convergent `PASS_STATUS: REVIEW_COMPLETE` reply for
  **each** per-prompt turn (keyed by per-prompt prompt text, per the existing
  `design-review-full-pass-routing-test` pattern in
  `workflow_review_step_routing_test`), then the converged `final-summary` turn's
  text — not one combined `REVIEW_COMPLETE`.

Assertion: after the converged run via `execute-workflow-run`, assert
`:psi.workflow/result` contains exactly one `PASS_STATUS: REVIEW_COMPLETE` line
(the converged `final-summary`'s yielded text surfacing through the
`(last :step-order)` standalone path, since the converged `final-summary` is
ordered last per DI-2). This asserts the **ordering/plumbing** invariant — the
stubbed reply supplies the literal `PASS_STATUS: REVIEW_COMPLETE` string, so the
assertion proves the converged (last-ordered) summary's yielded text is the one
that surfaces, **not** that the template wording produced it. The DI-4 point-4
definition-level assertion is the authority for the template text itself.

## Implementation decision — not-converged summary wording, no iteration count (DI-3)

The not-converged summary templates must not claim a literal "after N follow-up
iterations" count: the not-converged summary step's contributions do not include
an iteration count, no runtime source for `N` is plumbed to that step, and
sourcing one is out of scope (no new generic routing operations). Hardcoding the
`:max-iterations` cap into the template wording would silently drift from the
authored cap whenever it changes (it already moved 6→3 for design review).

Resolution: **drop the numeric count** from both not-converged templates. Phrase
the wording without a number, e.g. "design/plan review did not converge within
the configured follow-up iteration limit". Slices 2/3 author this wording; no
contribution/source for `N` is added.

## Implementation decision — summary PASS_STATUS line contract (DI-4)

The lifecycle gates read each summary's `:yield :text` through
`workflow/pass-status-routing`
(`agent_session/workflow/routing.clj` `parse-pass-status-routing`), which is
**strict** on two axes: it errors `:ambiguous-pass-status` when **more than one**
line begins with `PASS_STATUS:`, and it accepts a line as a valid status only
when it is *exactly* `PASS_STATUS:<space><TOKEN>` — column 0, single space, bare
token, nothing else on the line (`exact-known?` = `(= raw-value (str " " trimmed))`).
Both summary steps' `:contributions` include the design-review (resp.
plan-review) per-prompt `:final-llm-reply` outputs, and every review prompt
(`review-task-design-ambiguity-review.md` etc.) ends with its own
`PASS_STATUS: …` line — so the summary LLM's context already contains 3 (design)
/ 2 (plan) `PASS_STATUS:` lines it could echo. This DI supersedes the looser D1
phrasing "emit a required PASS_STATUS line, replacing the existing 'do not output
REPEAT/DONE/control tokens' instruction"; Slices 2/3 author exactly the contract
below.

Resolution (the authored template contract, applied identically to the converged
`final-summary` **and** the new `final-summary-not-converged` in **both**
`review-task-design.edn` and `review-task-plan.edn`):

1. **Required line format.** Each summary template MUST instruct the model to end
   its response with exactly one line of the form `PASS_STATUS: <TOKEN>` — column
   0, a single space after the colon, the bare token, nothing else on that line —
   and this MUST be the **sole** `PASS_STATUS:` line in the output. Converged
   `final-summary` → `PASS_STATUS: REVIEW_COMPLETE`; `final-summary-not-converged`
   → `PASS_STATUS: ACTIONABLE_FEEDBACK`. This is exactly what
   `parse-pass-status-routing` accepts (single occurrence, exact form), so the
   gate resolves deterministically.
2. **Retain an anti-echo instruction.** The template MUST explicitly forbid the
   summary from reproducing or quoting the `PASS_STATUS:` lines carried in the
   contributed review replies, so the single mandated final line is the only
   `PASS_STATUS:` line present. Without this, the model may echo a contributed
   status, producing >1 `PASS_STATUS:` line → `:ambiguous-pass-status` →
   lifecycle hard-fail (the exact failure mode this task removes).
3. **Reconcile the two existing anti-control-token sentences.** The current
   converged templates carry *two* guards: (a) "Respond with a concise summary
   for the user, not an internal control token." and (b) "Do not output REPEAT or
   DONE unless quoting prior workflow behavior." Do **not** ambiguously "replace
   the instruction". Instead: **keep** sentence (a) — it constrains the prose
   body and is compatible with a mandated trailing status line. **Rewrite**
   sentence (b) so it is no longer a blanket anti-`PASS_STATUS` guard but the
   precise rule from (1)+(2), e.g. "Do not include REPEAT or DONE control tokens,
   and do not reproduce the `PASS_STATUS:` lines from the review replies provided
   as context; end your response with exactly one line
   `PASS_STATUS: REVIEW_COMPLETE`." (the not-converged template uses
   `PASS_STATUS: ACTIONABLE_FEEDBACK`).
4. **Definition-level template-text assertion (the authority for the wording).**
   Because the DI-2 converged-standalone runtime test stubs the model reply (it
   locks ordering/plumbing, not template text — see DI-2), the **definition-level**
   `review-task-design-test` / `review-task-plan-test` MUST be the authority that
   locks the DI-4 template **text**. Each MUST assert, against the real loaded
   `.edn`, that the converged `final-summary` template string contains the
   required line in the exact form `parse-pass-status-routing` accepts —
   column 0, a single space after the colon, the bare token, nothing else on the
   line, and as the **sole** `PASS_STATUS:` line in the template body —
   `PASS_STATUS: REVIEW_COMPLETE` for the converged summary and
   `PASS_STATUS: ACTIONABLE_FEEDBACK` for the `final-summary-not-converged`
   template. (Concretely: assert the converged template body contains the literal
   substring `"\nPASS_STATUS: REVIEW_COMPLETE"` as its final line and exactly one
   `PASS_STATUS:` occurrence; symmetric for the not-converged token.) This
   replaces the earlier vague "carry their PASS_STATUS lines" Slice 2/3 wording,
   which did not pin the exact DI-4 column-0/single-space/sole-occurrence format.

Test note: the Slice 2/3 converged-standalone result-text test (DI-2) asserts the
**ordering/plumbing** invariant — that the converged `final-summary` (ordered
last per DI-2) is the step whose yielded text surfaces via the standalone
`(last :step-order)` path; it does **not** lock the template wording (the model
reply is stubbed). The DI-4 point-4 definition-level template-text assertion is
the authority that the converged/not-converged template bodies carry the exact
parser-accepted `PASS_STATUS:` line.

## Implementation decision — lifecycle step placement & fall-through (DI-5)

DI-1/DI-2/R1 reason about `:next` fall-through
(`statechart.clj` `next-step-target` / `compile-leaf-step`
`:actor/done → next-step-target`) **only** for the `review-task-design.edn` /
`review-task-plan.edn` summary steps. The identical hazard applies to
`task-lifecycle.edn`: its `:delegate` steps are non-judged leaf steps that route
`:actor/done` to the **next step in `:steps` order**, and the four new lifecycle
steps (two gates + two handbacks) must be positioned so no converged path silently
falls through into a not-converged handback.

Resolution (the authored insertion positions, decided here):

1. **Each gate immediately follows its delegate.** `check-design-review-status`
   is inserted **immediately after** `review-task-design`, and
   `check-plan-review-status` **immediately after** `review-task-plan`, so the
   delegate's `:actor/done` falls through into its gate, and the gate's explicit
   `:on {"DONE" {:goto …}}` continues the main flow (design gate
   DONE→`create-task-plan`; plan gate DONE→`implement-task`).
2. **Both handbacks are appended after the existing terminal summaries.**
   `final-summary-design-not-converged` and `final-summary-plan-not-converged`
   are appended **last**, after `final-summary-after-extraction` and
   `final-summary-without-extraction` (mirroring `final-summary-without-extraction`
   already being last). Every step that precedes them terminates explicitly via a
   judge + `:on {"DONE" {:goto :done}}` (the two existing summaries and, in
   Slice 3, the Slice-2 `final-summary-design-not-converged`), so no preceding
   leaf step can fall through into a handback. Each handback is itself
   explicit-terminal (`:goto :done`); the last one is additionally `:completed`
   by order.

Resulting ordered `:steps` vectors the updated `task-lifecycle-test` MUST assert:

- **After Slice 2 (9 → 11 steps).** Names, in order:
  `["review-task-design" "check-design-review-status" "create-task-plan"
    "review-task-plan" "implement-task" "review-task-implementation"
    "check-implementation-review-status" "extract-task-knowledge"
    "final-summary-after-extraction" "final-summary-without-extraction"
    "final-summary-design-not-converged"]`.
  Types, in order:
  `(concat [:delegate :invoke] (repeat 4 :delegate) [:invoke :delegate]
           (repeat 3 :session))`
  = `[:delegate :invoke :delegate :delegate :delegate :delegate :invoke :delegate
      :session :session :session]`.
  Index shifts vs the current test: `check-implementation-review-status`
  5 → 6, `extract-task-knowledge` 6 → 7, `final-summary-after-extraction` 7 → 8,
  `final-summary-without-extraction` 8 → 9; `repeat 9 {}` → `repeat 11 {}`.
- **After Slice 3 (11 → 13 steps).** Names, in order:
  `["review-task-design" "check-design-review-status" "create-task-plan"
    "review-task-plan" "check-plan-review-status" "implement-task"
    "review-task-implementation" "check-implementation-review-status"
    "extract-task-knowledge" "final-summary-after-extraction"
    "final-summary-without-extraction" "final-summary-design-not-converged"
    "final-summary-plan-not-converged"]`.
  Types, in order:
  `[:delegate :invoke :delegate :delegate :invoke :delegate :delegate :invoke
    :delegate :session :session :session :session]`
  = `(concat [:delegate :invoke :delegate :delegate :invoke :delegate :delegate
              :invoke :delegate] (repeat 4 :session))`.
  Index shifts vs Slice 2: `check-plan-review-status` inserts at 4, so
  `implement-task` 4 → 5, `review-task-implementation` 5 → 6,
  `check-implementation-review-status` 6 → 7, `extract-task-knowledge` 7 → 8, the
  two existing summaries 8/9 → 9/10, `final-summary-design-not-converged`
  10 → 11; `repeat 11 {}` → `repeat 13 {}`.

**Additional `task-lifecycle-test` assertions that MUST be restructured (not just
index/`repeat`-count bumped).** Beyond the count/name/type vectors, positional
`nth`, and `repeat`-count edits enumerated above, the live `task-lifecycle-test`
(`workflow_definitions_test.clj`) contains **three further assertions keyed off
`(take 5 steps)`** that break once an `:invoke` gate is inserted at `:steps`
index 1 (Slice 2) and index 4 (Slice 3) — because the gate steps carry **no**
`:target`/`:prompt-string`/`:context`, so `(take 5 steps)` no longer yields five
delegate steps:

1. `(is (= first-five-targets (mapv :target (take 5 steps))))` (`:643`) —
   `check-design-review-status` / `check-plan-review-status` have no `:target`.
2. `(is (= (repeat 5 standard-prompt) (mapv :prompt-string (take 5 steps))))`
   (`:645-646`) — the gate steps have no `:prompt-string`.
3. `(is (= (repeat 6 [{:type :source :from :workflow-original}])
   (mapv :context (concat (take 5 steps) [extraction-step]))))` (`:667-668`) —
   the gate steps have no `:context`, and `extraction-step`'s `nth` index also
   shifts (6 → 7 in Slice 2, 7 → 8 in Slice 3).

These three MUST be **restructured away from the `(take 5 steps)` "first five are
delegates" positional assumption** — e.g. select the delegate steps by name/type
filter (or explicit per-name `nth`) rather than `take 5` — in **both** Slice 2
(gate inserted at index 1) and Slice 3 (gate inserted at index 4). This is part
of the in-place `task-lifecycle-test` update mandate (R3/DI-5), not a mechanical
`nth`/`repeat` edit: the `first-five-targets` target vector, the
`(repeat 5 standard-prompt)` prompt-string vector, and the `(repeat 6 …)` context
vector must each be rewritten to assert only the delegate steps' targets/prompts/
contexts (and the gate steps' distinct shape) under the new ordering.

## Implementation decision — judge-side exhaustion is the runtime-governing site (DI-6)

The loop-5 ambiguity review found (and code verification confirms) that, for the
review workflows this task targets, runtime exhaustion is **not** decided by the
statechart `:judge/signal` exhaustion transition the original Slice 1 modifies.
There are **two parallel exhaustion sites**, and the judge-side one fires first:

1. **Judge-side (governing).**
   `components/workflow-judge/src/psi/workflow_judge.clj` `evaluate-routing`
   resolves the judge signal's directive, and when the target step's
   `:iteration-count` ≥ the directive's `:max-iterations`
   (`check-iteration-limit` → `:exhausted`) returns
   `{:action :fail :reason :iteration-exhausted :step-id target …}`. In
   `statechart_runtime.clj` `:judge/enter` (`:383`–`:417`), the judge sees
   `routing-table (or (:on step-def) {})` — i.e. the **full IR directive map**,
   so it already has `:on-max-iterations` available (threaded by the
   `target_ir_compiler` Slice-1 change). An `:action :fail` is enqueued as
   **`:judge/failed`** (not `:judge/signal`), whose `:judge/record` `else` branch
   marks the run `:status :failed`, `:terminal-outcome {:reason :iteration-exhausted}`.
2. **Statechart-side (dead code for these workflows).** The `:judge/signal`
   exhaustion guard + `:iteration/exhausted` action (`:reason
   :iteration-limit-reached`) fires only when `:judge/signal` is enqueued, which
   never happens once `evaluate-routing` short-circuits to `:fail`.

Verified against the **integration** test
`workflow_review_step_routing_test/review-pass-loop-iteration-limit-failure-test`
(`:675`+): a real exhausted review run terminates `:reason :iteration-exhausted`,
`:step-id "design-follow-up"` / `"clarity-status"` (the judge-side path keyed off
the `:max-iterations`-bearing step), **not** `:iteration-limit-reached`. The
pure-statechart `statechart_test/iteration-exhaustion-fires-action-test` feeds
`:judge/signal` directly and bypasses `evaluate-routing`, so it cannot detect
this gap.

Consequence: a `compile-routing-transitions`-only change is **dead code at
runtime** for `review-task-design`/`-plan` — they would still hard-fail with
`:iteration-exhausted` and never reach `final-summary-not-converged`, making
AC-3/AC-4/AC-5/AC-6 unachievable from the original change set.

Resolution (option (a), chosen — the judge-side site governs): extend Slice 1 to
thread `:on-max-iterations` through `evaluate-routing` as well. When
`check-iteration-limit` is `:exhausted` **and** the matched directive carries
`:on-max-iterations`, `evaluate-routing` MUST resolve that target via the same
`resolve-goto-target` logic it already uses for `:goto`
(`:next`/`:previous`/`:done`/step-name) and return that
`{:action :goto :target <on-max-iterations-target>}` (or `{:action :complete}`
for `:done`) **instead of** `{:action :fail :reason :iteration-exhausted}`. A
non-`:fail` action is enqueued as `:judge/signal` → `:judge/record` `:goto`
branch → sets `:current-step-id` to the author target, `:status :running` — so
the run routes to the handback summary and is **never marked failed**, end to
end. When `:on-max-iterations` is absent, `evaluate-routing` keeps returning
`{:action :fail :reason :iteration-exhausted}` (current behaviour preserved).

This is additive and consistent with D2: `judged-routing-transition` is still
untouched, and the statechart `compile-routing-transitions` change is still made
(keeps the two sites coherent and covers any future direct-`:judge/signal`
path), but the **runtime-effective** edit for review workflows is in
`evaluate-routing`. Slice 1's exit criterion is widened to include an
**integration-level** exhaustion-routing assertion (not just pure-statechart),
since the existing `statechart_test` cannot exercise the judge-side path.

Design reconciliation note (read-only here): `design.md` "Context" frames
exhaustion as flowing through `statechart.clj` `compile-routing-transitions` /
`judged-routing-transition` only and omits the judge-side `evaluate-routing`
governing site; D2 says "the only statechart edit is computing the exhaustion
target in `compile-routing-transitions`". D2's *decision* ("no change to
`judged-routing-transition`") remains valid, but the Context description and the
"only … edit" framing under-describe the change set (they should also note the
`evaluate-routing` edit). This `design.md` clarification is **read-only-blocked**
for this plan-profile follow-up and is recorded for a design pass (see
`implementation.md`); plan.md (the *how* authority) now carries the authoritative
two-site mechanism above.

## Slice 1 — `:on-max-iterations` engine primitive (inert)

Files:
- `components/workflow-runtime/src/psi/workflow_runtime/model.clj`
  `routing-directive-schema` — add optional `:on-max-iterations`
  (`[:or [:enum :next :previous :done] :string]`) and wrap the directive map in
  `[:and <map> [:fn require-max-iterations-when-on-max]]` so
  `:on-max-iterations` without `:max-iterations` is rejected with a clear error
  (D3).
- `components/workflow-runtime/src/psi/workflow_runtime/ir.clj`
  `routing-directive-schema` — same optional key
  (`[:or [:enum :next :previous :done] step-name-schema]`) and the same
  cross-field reject constraint (D3).
- `components/workflow-runtime/src/psi/workflow_runtime/target_ir_compiler.clj`
  `compile-routing-table` — in the per-outcome `cond->`, additionally
  `(contains? transition :on-max-iterations) (assoc :on-max-iterations (:on-max-iterations transition))`.
- `components/workflow-runtime/src/psi/workflow_runtime/statechart.clj`
  `compile-routing-transitions` — in the `(if max-iterations …)` branch, compute
  the exhaustion target: when the directive has `:on-max-iterations`, resolve it
  through the same goto→target logic used for `:goto`
  (`:next`/`:previous`/`:done`/step-name) and use it as the exhaustion
  transition `:target`; otherwise keep `:target :failed`. No change to
  `judged-routing-transition` (D2): an exhaustion target ≠ `:failed` already
  dispatches `:judge/record` and does not mark the run failed. **Note (DI-6):**
  this statechart edit alone is dead code at runtime for the review workflows —
  the runtime-governing exhaustion site is the judge-side `evaluate-routing`
  below; this edit is retained for two-site coherence and any future
  direct-`:judge/signal` path.
- `components/workflow-judge/src/psi/workflow_judge.clj` `evaluate-routing`
  (**DI-6 — the runtime-governing edit**) — when `check-iteration-limit` is
  `:exhausted` **and** the matched directive carries `:on-max-iterations`,
  resolve that target via the existing `resolve-goto-target`
  (`:next`/`:previous`/`:done`/step-name) and return the resulting
  `{:action :goto :target …}` / `{:action :complete}` **instead of**
  `{:action :fail :reason :iteration-exhausted}`; when `:on-max-iterations` is
  absent, return `{:action :fail :reason :iteration-exhausted …}` unchanged. The
  judge already receives the full IR directive (`routing-table (or (:on
  step-def) {})` in `statechart_runtime.clj`), so `:on-max-iterations` is present
  without further plumbing. A non-`:fail` action is enqueued as `:judge/signal`
  → `:judge/record` `:goto` branch → routes to the author target with
  `:status :running` (run not failed).

Refactor note: the goto→target resolution currently lives inline in
`compile-routing-transitions` (the `(case goto …)` block). Extract it to a small
named helper so both the `:goto` target and the `:on-max-iterations` target use
one implementation (avoid divergence). Keep it private to `statechart.clj`.

Tests:
- `ir_test.clj` / `model_test.clj` — `:on-max-iterations` with `:max-iterations`
  validates; without `:max-iterations` is rejected (both schemas).
- `target_ir_compiler_test.clj` — `:on-max-iterations` is threaded model→IR.
- `statechart_test.clj` — extend the existing CHANGED/max-iterations cases: with
  `:on-max-iterations`, an exhausted signal routes to the author target's acting
  state and dispatches `:judge/record` (run not `:failed`); without it, exhausted
  still routes to `:failed` + `:iteration/exhausted` (regression-lock current
  behaviour).
- `components/workflow-judge/test/psi/workflow_judge_test.clj` (**DI-6**) —
  `evaluate-routing` unit coverage: when the matched directive carries
  `:on-max-iterations` and the target step's `:iteration-count` ≥
  `:max-iterations`, returns `{:action :goto :target <on-max-iterations-target>}`
  (and `{:action :complete}` for `:on-max-iterations :done`); when
  `:on-max-iterations` is absent, returns
  `{:action :fail :reason :iteration-exhausted}` (regression-lock current
  behaviour); within-limit still returns the success `:goto`.
- **Integration exhaustion-routing test (DI-6) — mandatory, the gap the
  pure-statechart test cannot detect.** Add an integration-level test (e.g. in
  `workflow_review_step_routing_test`, alongside
  `review-pass-loop-iteration-limit-failure-test`) that drives a real exhausting
  judged review loop carrying `:on-max-iterations` through the runtime
  (`execute-run!` / `execute-workflow-run`) and asserts it terminates at the
  **author target** (the not-converged summary) with the run **not** `:status
  :failed` — i.e. the judge-side `evaluate-routing` path routes to the handback
  rather than `:reason :iteration-exhausted`. (The existing
  `review-pass-loop-iteration-limit-failure-test`, which has **no**
  `:on-max-iterations`, stays as the regression-lock that exhaustion without the
  key still hard-fails.)

Exit: focused workflow-runtime **and workflow-judge** Scry suites green; the
DI-6 integration exhaustion-routing assertion green (run reaches the author
target, not `:status :failed`); clj-kondo clean; no existing workflow definition
changes.

Slice-1 scope note (DI-6): although DI-6's runtime-effective edit lives in the
`workflow-judge` component and is verified by an integration test that drives a
judged review loop, Slice 1 remains **behaviour-inert for existing workflows** —
no shipped `.edn` authors `:on-max-iterations` until Slices 2/3, so
`evaluate-routing`'s new branch is only reachable by directives that opt in. The
integration test introduces its own opt-in definition/fixture rather than
mutating a shipped workflow.

## Slice 2 — review-task-design handback + lifecycle design gate

Files:
- `.psi/workflows/review-task-design.edn`:
  - `design-follow-up` `:on` gains
    `:on-max-iterations "final-summary-not-converged"` alongside the existing
    `{:goto "design-review" :max-iterations 3}`.
  - Existing `final-summary` (converged): add the explicit-terminal judge + `:on`
    (DI-1) and update its template to emit the required
    `PASS_STATUS: REVIEW_COMPLETE` line per the **DI-4** summary PASS_STATUS line
    contract (single column-0 `PASS_STATUS: <TOKEN>` line, sole occurrence, last;
    retain an anti-echo instruction; reconcile the two existing
    anti-control-token sentences — keep the "concise summary … not an internal
    control token" guard, rewrite the "do not output REPEAT/DONE" guard into the
    precise anti-echo + single-required-line rule), D1/D5.
  - New `final-summary-not-converged` session step, placed **before** the
    converged `final-summary` so the converged summary stays last in `:steps`
    (DI-2): contributions sourced from `:workflow-original` + the three
    `design-review` per-prompt `:final-llm-reply` outputs; template produces a
    "design review did not converge within the configured follow-up iteration
    limit" user summary (no literal iteration count, DI-3) and emits the required
    `PASS_STATUS: ACTIONABLE_FEEDBACK` line per the **DI-4** contract (same
    single-line/anti-echo/reconciliation rules, ACTIONABLE_FEEDBACK token);
    explicit-terminal judge + `:on` (DI-1).
- `.psi/workflows/task-lifecycle.edn` (insertion positions per **DI-5**):
  - New `check-design-review-status` invoke-step inserted **immediately after**
    `review-task-design` (`:steps` index 1, so `review-task-design` falls through
    into the gate), mirroring `check-implementation-review-status`:
    `:operation "workflow/constant-routing" :args {:route "DONE"}`,
    `:judge {:type :invoke :operation "workflow/pass-status-routing"
             :args {:text {:from {:step "review-task-design" :yield :text}}
                    :allowed-statuses ["ACTIONABLE_FEEDBACK" "REVIEW_COMPLETE"]}}`,
    `:on {"DONE" {:goto "create-task-plan"}
          "REPEAT" {:goto "final-summary-design-not-converged"}}`.
  - New `final-summary-design-not-converged` session step **appended last**
    (after both existing terminal summaries, per **DI-5**, so no preceding leaf
    step falls through into it), mirroring `final-summary-without-extraction`:
    contributions from `:workflow-original`
    + `review-task-design` yield; template explains the lifecycle stopped at the
    design stage because design review did not converge, hands back to the human,
    does not extract knowledge; `:on {"DONE" {:goto :done}}`.

Tests:
- `components/workflow-loader/test/.../workflow_definitions_test.clj`
  `review-task-design-test`: update the step-order vector to include
  `final-summary-not-converged` (ordered before the converged `final-summary`,
  DI-2); assert `design-follow-up` `:on` now carries
  `:on-max-iterations "final-summary-not-converged"`; assert both summaries are
  explicitly terminal; and assert the **exact DI-4 template text** per **DI-4
  point 4** — the converged `final-summary` template body contains the sole,
  column-0, single-space, last-line `PASS_STATUS: REVIEW_COMPLETE`, and
  `final-summary-not-converged` the same-form `PASS_STATUS: ACTIONABLE_FEEDBACK`.
  This definition-level template-text assertion (not the DI-2 stubbed runtime
  test) is the authority that the template wording is parser-accepted.
  **Pre-existing RED baseline (must fix here):** this test currently asserts
  `design-follow-up` `:on` = `{"DONE" {:goto "design-review" :max-iterations 6}}`
  (`workflow_definitions_test.clj:121`) while the edn is `:max-iterations 3`
  (post `de19cc5bf` "lower loop cap to 3") — so `review-task-design-test` is
  already failing (`-6 +3`) before this slice. Correct the stale `6→3` assertion
  as part of this same edit; the "focused workflow-loader Scry green" exit
  criterion assumes that fix. (Verified: `review-task-plan-test` `:max-iterations
  5` and `review-step-test` `:max-iterations 10` match their edns — no drift
  there.)
- Add a **converged standalone result-text** runtime test (DI-2), constructed
  per the DI-2 "converged-standalone result-text construction" note: load the
  **real** `review-task-design.edn` (not the synthetic `conditional-review-*`
  proof def), `with-redefs` `psi.agent-session.turn/prompt-execution-result-in!`
  to return `PASS_STATUS: REVIEW_COMPLETE` for **each** of the three
  `design-review` per-prompt turns (keyed by per-prompt prompt text, per
  `design-review-full-pass-routing-test`) plus the converged `final-summary`
  text, drive it through the `execute-workflow-run` mutation (not the harness's
  custom actor-turn fn), and assert `:psi.workflow/result` contains exactly one
  `PASS_STATUS: REVIEW_COMPLETE` line (locks the yielded text, not just
  definition-level routing).
- task-lifecycle definition coverage: the existing `task-lifecycle-test`
  (`workflow_definitions_test.clj:602`) is **positionally hard-coded** —
  `(= 9 (count steps))`, exact `:name` vector, the `:type` vector
  `(concat (repeat 5 :delegate) [:invoke :delegate :session :session])`,
  positional `(nth steps 5/6/7/8)`, `(repeat 6 …)` contexts, and
  `(repeat 9 {})` for `:yields`/`:terminal-contract`. Adding
  `check-design-review-status` + `final-summary-design-not-converged` breaks all
  of these, so the existing `task-lifecycle-test` **MUST be updated in this
  slice** (R3): bump count 9→11, insert the two new step names/types at their
  positions, fix the `nth` indices, and update the `repeat` counts (e.g.
  `repeat 9 {}`→`repeat 11 {}`) — assert the exact 11-element ordered name and
  type vectors pinned in **DI-5** (Slice-2 vectors). Additionally **restructure
  the three `(take 5 steps)`-based assertions** (`first-five-targets` targets,
  `(repeat 5 standard-prompt)` prompt-strings, `(repeat 6 …)` contexts) away from
  the `take 5` positional assumption — the design gate at index 1 has no
  `:target`/`:prompt-string`/`:context` — per DI-5's "Additional
  `task-lifecycle-test` assertions" note. It must assert the
  `check-design-review-status`
  gate routes DONE→`create-task-plan` and REPEAT→`final-summary-design-not-converged`,
  and that `final-summary-design-not-converged` terminates with `:goto :done`.
  A separate `229` definition test is **additive-only**, never a substitute for
  updating `task-lifecycle-test`.
- If a runtime routing test exists for the lifecycle gate pattern
  (`workflow_review_step_routing_test`), add a design-gate analogue; otherwise
  rely on definition-level coverage plus Slice 1 runtime coverage.

Exit: focused workflow-loader + affected runtime suites green; clj-kondo clean.

## Slice 3 — review-task-plan handback + lifecycle plan gate (symmetric)

Mirror Slice 2 for the plan review:
- `.psi/workflows/review-task-plan.edn`: `plan-follow-up` `:on` gains
  `:on-max-iterations "final-summary-not-converged"`; converged `final-summary`
  becomes explicit-terminal + `PASS_STATUS: REVIEW_COMPLETE` per the **DI-4**
  summary PASS_STATUS line contract (single column-0 `PASS_STATUS: <TOKEN>` line,
  sole occurrence, last; retain an anti-echo instruction; keep the "concise
  summary … not an internal control token" guard and rewrite the
  "do not output REPEAT/DONE" guard into the precise anti-echo + single-line
  rule); new `final-summary-not-converged` placed **before** the converged
  `final-summary` so the converged summary stays last in `:steps` (DI-2), sourced
  from the two `plan-review` per-prompt replies, wording with no literal iteration
  count (DI-3) + `PASS_STATUS: ACTIONABLE_FEEDBACK` per DI-4 + explicit-terminal.
- `.psi/workflows/task-lifecycle.edn` (insertion positions per **DI-5**): new
  `check-plan-review-status` gate inserted **immediately after**
  `review-task-plan` (`:steps` index 4, so `review-task-plan` falls through into
  the gate) → `{"DONE" {:goto "implement-task"}
  "REPEAT" {:goto "final-summary-plan-not-converged"}}`; new
  `final-summary-plan-not-converged` handback step **appended last** (after
  `final-summary-design-not-converged`, so no preceding leaf falls through into
  it) with explicit-terminal `:goto :done`.
- Tests:
  - `review-task-plan-test` step-order + routing updates (include
    `final-summary-not-converged` ordered before the converged `final-summary`;
    assert `plan-follow-up` `:on-max-iterations`; both summaries
    explicit-terminal; and assert the **exact DI-4 template text** per **DI-4
    point 4** — converged `final-summary` body contains the sole, column-0,
    single-space, last-line `PASS_STATUS: REVIEW_COMPLETE`, and
    `final-summary-not-converged` the same-form `PASS_STATUS: ACTIONABLE_FEEDBACK`;
    this definition-level assertion, not the DI-2 stubbed runtime test, is the
    authority for the template wording). (`review-task-plan-test`'s
    `:max-iterations 5` assertion already matches the edn — no stale-baseline fix
    needed here.)
  - Converged standalone result-text runtime test for `review-task-plan` (DI-2),
    mirroring Slice 2 and constructed per the DI-2 "converged-standalone
    result-text construction" note: load the **real** `review-task-plan.edn` (not
    the synthetic `conditional-review-*` proof def), `with-redefs`
    `psi.agent-session.turn/prompt-execution-result-in!` to return
    `PASS_STATUS: REVIEW_COMPLETE` for **each** of the two `plan-review`
    per-prompt turns (keyed by per-prompt prompt text) plus the converged
    `final-summary` text, drive it through the `execute-workflow-run` mutation,
    and assert `:psi.workflow/result` contains exactly one
    `PASS_STATUS: REVIEW_COMPLETE` line.
  - `task-lifecycle-test` **MUST be updated again in this slice** (R3): adding
    `check-plan-review-status` + `final-summary-plan-not-converged` bumps the
    step count 11→13; update the name/type vectors, positional `nth` indices, and
    `repeat` counts accordingly to the exact 13-element ordered name and type
    vectors pinned in **DI-5** (Slice-3 vectors), **and** re-apply the DI-5
    restructuring of the three `(take 5 steps)`-based assertions
    (`first-five-targets` targets, `(repeat 5 standard-prompt)` prompt-strings,
    `(repeat 6 …)` contexts) — the plan gate at index 4 again has no
    `:target`/`:prompt-string`/`:context`, so the delegate-only selection from
    Slice 2 must hold under the Slice-3 ordering. Assert the plan gate routes
    DONE→`implement-task` and REPEAT→`final-summary-plan-not-converged`, with
    `final-summary-plan-not-converged` terminating `:goto :done`. A separate
    `229` test remains additive-only.

Exit: focused workflow-loader suites green; clj-kondo clean.

## Slice 4 — docs + coherence

- `doc/workflows.md`: document the `:on-max-iterations` routing-directive key and
  the design/plan non-convergence handback behaviour in `task-lifecycle`.
- `CHANGELOG.md` `[Unreleased]`:
  - `Added`: `:on-max-iterations` author-routed exhaustion target on workflow
    routing directives.
  - `Changed`: `task-lifecycle` stops and hands back at the design/plan stage
    when the corresponding review does not converge (was hard-fail).
- Coherence sweep: `mementum/state.md` workflow-routing bullet mentions the new
  primitive if warranted; re-read edited files (`sync`).

## Risks

- **R1 — terminal fall-through (DI-1, DI-5).** Mitigated by making both review
  summaries explicitly terminal (DI-1) and, in `task-lifecycle.edn`, by the DI-5
  insertion positions (each gate immediately after its delegate; both handbacks
  appended last after the already-terminal summaries) so no converged leaf step
  falls through into a not-converged handback. Locked by the `task-lifecycle-test`
  ordered name/type vectors (DI-5) and review-workflow definition termination
  assertions.
- **R2 — PASS_STATUS reliability / echo.** The gate parses an LLM-produced
  `PASS_STATUS:` line. Mitigated: each summary template hardcodes exactly one
  required status string (structurally determined by which step ran, D1), and
  `pass-status-routing` errors on missing/ambiguous status — failing loud, not
  silent. Same reliability profile as the existing implementation-review gate.
  The summary steps also receive the review per-prompt replies (each ending in
  its own `PASS_STATUS:` line) as contributions, so a naive template could echo
  one and trip the strict >1-line `:ambiguous-pass-status` error; the DI-4
  contract (mandatory single column-0 last line + explicit anti-echo
  instruction) is the mitigation.
- **R3 — definition-test drift.** Step-order assertions in
  `workflow_definitions_test.clj` are brittle to added steps; update them in the
  same slice that changes each `.edn`. Concretely: `task-lifecycle-test` is
  positionally hard-coded (count, name/type vectors, `nth` indices,
  `repeat`-counts) and MUST be updated in both Slice 2 (9→11) and Slice 3
  (11→13) — a separate `229` test is additive-only, never a substitute. The
  enumerated count/name/type/`nth`/`repeat` edits are **not exhaustive**: three
  further assertions keyed off `(take 5 steps)` (`first-five-targets` targets
  `:643`, `(repeat 5 standard-prompt)` prompt-strings `:645-646`, `(repeat 6 …)`
  contexts `:667-668`) also break — the inserted `:invoke` gates carry no
  `:target`/`:prompt-string`/`:context` — and MUST be **restructured away from
  the `take 5` positional assumption** (select delegate steps by name/type
  filter), per DI-5's "Additional `task-lifecycle-test` assertions" note. Also
  note the *pre-existing* RED `review-task-design-test` `:max-iterations` 6→3
  drift, corrected as part of the Slice 2 edit (see Slice 2 tests).
- **R4 — standalone behaviour change (D5).** Standalone `/delegate
  review-task-design`/`-plan` now emits a `PASS_STATUS:` line. Accepted; noted in
  docs.
- **R5 — degraded standalone not-converged result text (DI-2).** Because the
  standalone result-text path reads `(last :step-order)` and the converged
  `final-summary` is ordered last, a *standalone* non-converging
  `/delegate review-task-design`/`-plan` run surfaces empty result text (the
  not-converged summary is not last-defined). Accepted: the non-convergence
  handback is consumed only via the order-independent lifecycle delegate-gate
  path, which resolves correctly; fixing the shared `execute-workflow-run`
  last-step resolution for all workflows is out of scope.

## Out of scope (restate)

No change to actor-retry/`:max-attempts`, to `:iteration/exhausted` accounting
itself, or new generic routing operations.
