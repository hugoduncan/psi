# 229 — Plan

Approach, sequencing, and decisions for implementing the author-routed workflow
exhaustion primitive and the review-did-not-converge handback. Reads `design.md`
as the authority for *what* and *why*; this file is *how*. Decision tags `D1–D5`
refer to the settled decisions in `design.md`.

## Strategy

Vertical slices, **engine before workflows**. Slice 1 lands the
`:on-max-iterations` primitive end-to-end through the four routing layers with
its own unit tests, and is **behaviour-inert** for every existing workflow
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

Test (Slice 2/3): lock the **converged standalone result text**, not just
definition-level routing — drive `review-task-design` (resp. `review-task-plan`)
standalone to a converged terminal via the `workflow_review_step_routing_test`
harness (stub `psi.agent-session.turn/prompt-execution-result-in!` to return
`REVIEW_COMPLETE` for the review step, then the converged summary text), run it
through `execute-workflow-run`, and assert `:psi.workflow/result` contains
`PASS_STATUS: REVIEW_COMPLETE`.

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

Test note: the Slice 2/3 converged-standalone result-text test (DI-2) already
asserts the converged `final-summary` yields a single `PASS_STATUS: REVIEW_COMPLETE`
line; that test is the runtime lock that the DI-4 template wording produces a
parser-accepted status.

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
  dispatches `:judge/record` and does not mark the run failed.

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

Exit: focused workflow-runtime Scry suites green; clj-kondo clean; no existing
workflow definition changes.

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
  explicitly terminal and carry their PASS_STATUS lines.
  **Pre-existing RED baseline (must fix here):** this test currently asserts
  `design-follow-up` `:on` = `{"DONE" {:goto "design-review" :max-iterations 6}}`
  (`workflow_definitions_test.clj:121`) while the edn is `:max-iterations 3`
  (post `de19cc5bf` "lower loop cap to 3") — so `review-task-design-test` is
  already failing (`-6 +3`) before this slice. Correct the stale `6→3` assertion
  as part of this same edit; the "focused workflow-loader Scry green" exit
  criterion assumes that fix. (Verified: `review-task-plan-test` `:max-iterations
  5` and `review-step-test` `:max-iterations 10` match their edns — no drift
  there.)
- Add a **converged standalone result-text** runtime test (DI-2): drive
  `review-task-design` to a converged terminal via the
  `workflow_review_step_routing_test` harness and assert `execute-workflow-run`'s
  `:psi.workflow/result` contains `PASS_STATUS: REVIEW_COMPLETE` (locks the
  yielded text, not just definition-level routing).
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
  type vectors pinned in **DI-5** (Slice-2 vectors). It must assert the
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
    assert `plan-follow-up` `:on-max-iterations`; both summaries explicit-terminal
    + PASS_STATUS lines). (`review-task-plan-test`'s `:max-iterations 5`
    assertion already matches the edn — no stale-baseline fix needed here.)
  - Converged standalone result-text runtime test for `review-task-plan` (DI-2),
    mirroring Slice 2.
  - `task-lifecycle-test` **MUST be updated again in this slice** (R3): adding
    `check-plan-review-status` + `final-summary-plan-not-converged` bumps the
    step count 11→13; update the name/type vectors, positional `nth` indices, and
    `repeat` counts accordingly to the exact 13-element ordered name and type
    vectors pinned in **DI-5** (Slice-3 vectors), and assert the plan gate routes
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
  (11→13) — a separate `229` test is additive-only, never a substitute. Also note
  the *pre-existing* RED `review-task-design-test` `:max-iterations` 6→3 drift,
  corrected as part of the Slice 2 edit (see Slice 2 tests).
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
