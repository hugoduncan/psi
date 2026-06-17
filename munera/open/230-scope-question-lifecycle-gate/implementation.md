# 230 — Implementation notes

Append-only local memory: decisions, discoveries, review notes.

## Reviews

### plan-review / ambiguity (turn 1)

Two actionable ambiguities filed to `design-steps.md`:

1. DI-3 invocation-key mismatch: gate runs as the `:invoke`-step **judge**, whose
   invocation supplies `:parent-session-id` (no `:session-id`); DI-3 reads
   `:session-id` → nil session on the production path → gate silently fails-open.
   Direct-invoke test harness masks it (provides `:session-id`).
2. DI-1 `:open-questions` detail content underspecified (full line vs post-marker
   concern substring).

Verified against runtime: `known-pass-status->route` (REVIEW_COMPLETE→DONE,
ACTIONABLE_FEEDBACK→REPEAT) confirms the DI-5 design-gate-vs-scope-gate
precedence reasoning is sound; `execute-invoke-judge!` and `build-invocation`
confirm the two distinct invocation-map key sets.

### plan-review / inconsistency (turn 2)

One actionable inconsistency filed to `design-steps.md`: design.md D1 (lines
111/124) states the `SCOPE_QUESTION` handback **wins** on a both-non-converged-
and-open run, while plan.md DI-5/R4 (line 273) decides the 229 design handback
wins (scope handback does not fire) and re-interprets D1 as intent-only —
authority (design) vs plan disagree on observable routing.

### plan-review / follow-up (batch: ambiguity + inconsistency turns)

Executed all three items filed by the immediately-preceding plan-review batch
(commits `cffaffde2` ambiguity + `83f761341` inconsistency; baseline `8d7a36b3f`).
Slices 1–5 are still unimplemented, so these are plan-stage fixes to plan.md +
steps.md only (no code yet).

1. **DI-3 invocation-key mismatch (resolved).** Verified in source: judge path
   (`workflow_judge/execute-invoke-judge!`) passes `:parent-session-id` and **no
   `:session-id`**; direct path (`deterministic_operation_action/build-invocation`)
   passes `:session-id` (+ conditional `:parent-session-id`). Pinned DI-3/steps to
   resolve the session id as `(or parent-session-id session-id)` (prefer
   `:parent-session-id` — the production judge path). Added a required Slice-3
   judge-path test that drives the gate through the real `:invoke`-step judge
   invocation so the direct-invoke harness can't mask a `:session-id`-only read.

2. **DI-1 `:open-questions` shape (resolved).** Pinned to a single shape: each
   entry is the **trimmed concern substring** after the `marker` (`str/trim`),
   not the raw matched line. Updated plan.md Scanner-semantics notation
   `[<line…>]`→`[<concern…>]` and steps.md Slice-1 scanner logic.

3. **design.md D1 ↔ plan.md DI-5/R4 precedence contradiction (resolved via the
   reviewer's option b).** Changed the plan to *genuinely make the scope handback
   win when both conditions hold*: the scope gate is now inserted at `:steps`
   index 1 (immediately after `review-task-design`, **before**
   `check-design-review-status`), routing `DONE → check-design-review-status` and
   `SCOPE_QUESTION_OPEN → final-summary-scope-question-open`;
   `check-design-review-status` `:on` is left unchanged (no repoint). This aligns
   the plan's observable routing with design.md D1's authoritative, twice-stated
   governing decision ("the `SCOPE_QUESTION` handback wins"), eliminating the
   reviewer's flagged "plan says not the scope handback" contradiction.

   **Residual (human, design.md):** design.md D1 (and the "Relationship to task
   229" restatement) also phrase placement as "after `check-design-review-status`",
   which is jointly unsatisfiable with its own governing "scope handback wins"
   under linear routing (a non-converged run terminates at the 229 handback before
   ever reaching a gate placed after it). The follow-up is forbidden to edit
   design.md, so the plan implements the governing precedence decision and flags
   the placement phrasing for human reconciliation in design.md (subordinate
   wording vs governing decision). This is a design-authority wording fix, not a
   plan defect.

### plan-review / ambiguity (fresh batch, turn 1)

Two new actionable ambiguities filed to `design-steps.md`:

1. DI-3 step 3 pins the session-*id* key but not the `ctx` arg of
   `resolvers/query-in` (`(query-in ctx q extra-entity)`); a missing/wrong ctx →
   nil worktree-path → resolver nil → fails open → gate silently never fires
   (same silent-default class as the resolved session-id item).
2. DI-4 normalization regex is internally inconsistent: substring +
   `(open|closed)` vs the "mirror existing grammar" reference, whose pattern is
   open-only + anchored/full-match.

Source verification this turn: `query-in` (`resolvers.clj`) is
`(query-in ctx q extra-entity)` seeding `:psi/agent-session-ctx`←ctx and
`:psi.agent-session/session-id`←`(:psi.agent-session/session-id extra-entity)`;
judge-path invocation (`execute-invoke-judge!`) supplies `:ctx` +
`:parent-session-id`, no `:session-id`; `:from :workflow-input :path [:input]`
is a supported invoke-arg source (`apply-source-spec`), so that authored arg is
sound (not flagged); existing `munera-open-task-path-pattern` is open-only and
anchored.

### plan-review / inconsistency (turn 2)

One new actionable inconsistency filed to `design-steps.md`: design.md's
gate-placement wording (Composition para lines 109-110 + D1 lines 118-119, "after
`check-design-review-status`") now contradicts the implemented plan.md DI-5/Slice
4 + steps.md, which place the gate at `:steps` index 1 **before**
`check-design-review-status`. Prior turn fixed the plan side; design.md wording is
the stale residual, tracked only in implementation.md until now → surfaced as an
open human-reconciliation item (follow-up may not edit design.md). Verified this
turn: `task-lifecycle-test` (`workflow_definitions_test.clj:655`) asserts 13 steps
/ `(repeat 13 {})` / delegate-by-`:type` / `(repeat 6 …)` / design-gate `:on
"DONE" → create-task-plan`; all match the plan's Slice-4 `13→15` update claims
(plan↔steps↔test consistent there — not flagged).

### plan-review / follow-up (batch: fresh ambiguity + inconsistency turns)

Batch baseline `dc2b30c41` (prev follow-up completion); preceding plan-review
batch = `f73347905` (ambiguity) + `18c2aba48` (inconsistency). `git diff
dc2b30c41..HEAD -- design-steps.md` added three unchecked items. Two are
plan-stage executor work (plan.md + steps.md only — Slices 1–5 still
unimplemented, no code yet); one is human-only.

1. **DI-3 ctx arg (resolved).** Verified `resolvers/query-in` signature
   (`resolvers.clj:161`): `(query-in ctx q extra-entity)` — seeds `ctx` as
   `:psi/agent-session-ctx`, and derives the session id from `extra-entity`
   (`(:psi.agent-session/session-id extra-entity)`), **not** from `ctx`. Pinned
   DI-3 step 3 (plan.md) + steps.md Slice-3 handler: the handler passes
   `(:ctx invocation)` (the judge-path agent-session-ctx) as the first positional
   `ctx`, query `[:psi.munera/task-artifact-content]`, and a **single**
   extra-entity map carrying `:psi.agent-session/session-id (or parent-session-id
   session-id)` + `:psi.munera/task-path` + `:psi.munera/artifact-name`. Session
   id must live in extra-entity, else nil worktree-path → resolver nil → fail-open
   → silent no-fire (same class as the session-id item).

2. **DI-4 normalization grammar (resolved).** Verified existing
   `munera-open-task-path-pattern` (`routing.clj:16-17`):
   `#"^munera/open/[0-9]{3,}-[a-z0-9]+(?:-[a-z0-9]+)*$"` — open-only, anchored
   (full-match via `re-matches`), slug class `[0-9]{3,}-[a-z0-9]+(?:-[a-z0-9]+)*`.
   Pinned DI-4 (plan.md) to **open-only + anchored/full-match**, reusing that
   exact grammar (the pre-plan gate always runs on an `open` task, so `closed`
   and substring-matching are dropped — superseding the earlier
   "substring/`(open|closed)`" phrasing). Bare `NNN-slug` token → anchored
   `#"^[0-9]{3,}-[a-z0-9]+(?:-[a-z0-9]+)*$"` → `munera/open/<token>`; everything
   else (free text, `munera/closed/…`, partial substring) → no usable path →
   resolver nil → fail-open `proceed-route`. Locked with a steps.md Slice-3 test
   bullet (full path verbatim / bare token / fail-open cases).

3. **design.md gate-placement wording (NOT executed — human-only, left
   unchecked).** This item explicitly states the follow-up executor may not edit
   design.md and the **human** must update design.md (Composition para + D1 +
   "Relationship to task 229") to read "before `check-design-review-status`". The
   change it requires lies solely in design.md, which the follow-up is forbidden
   to touch (and `SCOPE_QUESTION`-class human-decision items are out of executor
   scope). No plan/steps/code edit can satisfy it (plan.md + steps.md already
   implement the "before" placement; the residual is design.md authority wording
   only). Left unchecked per the evidence rule; not a plan defect.

### plan-review / ambiguity (fresh batch, turn 1) — no new actionable ambiguity

Reviewed plan.md + steps.md for ambiguities, verifying the pinned mechanics
against runtime source. No new actionable ambiguity found; nothing filed to
design-steps.md.

Verified sound (no gap):
- Operation `:description` is a supported optional field
  (`deterministic_operation_registry/defs.clj` `operation-definition-schema`),
  so Slice-3's "register with a `:description`" is valid despite existing
  built-ins omitting it.
- Gate result `{:status :ok :data <route> :summary <route> :details {…}}` is
  valid against `operation-success-result-schema` (`:summary` string,
  `:details` map both optional).
- Malformed-args `:status :error` routes via the runtime's
  `invoke-judge-error-result` → `:action :fail` (hard fail), consistent with all
  deterministic operations; authored EDN args make this a non-production path.
- Handler receives the full invocation map
  (`deterministic_operation_runtime/core.clj:174`
  `((:handler operation) (assoc invocation :operation-id …))`), so DI-3's read of
  `:ctx` / `:parent-session-id` (judge path) / `:session-id` (direct path) is
  satisfiable as pinned.
- Index-1 insertion wiring is sound against the live `task-lifecycle.edn`:
  `review-task-design` has no `:on` (positional fall-through), so the new gate at
  index 1 receives the fall-through and routes `DONE → check-design-review-status`.

### plan-review / inconsistency (fresh batch, turn 2) — no new actionable inconsistency

Cross-file review (design.md ↔ plan.md ↔ steps.md, against live
`task-lifecycle.edn`). No new actionable inconsistency; nothing filed to
design-steps.md.

Verified consistent (no new gap):
- Step/type/count claims agree end-to-end: live edn has 13 steps / 6 delegates;
  plan + steps assert `13→15`, `(repeat 13 {})→(repeat 15 {})`, gate `:invoke`
  at index 1 + handback `:session` last, "stay 6 delegates".
- Gate `:on` (`DONE → check-design-review-status`,
  `SCOPE_QUESTION_OPEN → final-summary-scope-question-open`) and the unchanged
  `check-design-review-status` `:on "DONE" → create-task-plan` agree across
  DI-5, Slice 4, steps.md, and the live edn.
- Route labels, authored arg keys (`:task-path`/`:artifact`/`:marker`/
  `:proceed-route`/`:open-route`), handback name, resolver registration target,
  and scanner semantics are consistent plan ↔ steps.
- The two pre-existing cross-file inconsistencies remain correctly tracked: the
  D1 "scope handback wins" precedence item is resolved (plan genuinely makes
  scope fire first); the design.md "after `check-design-review-status`"
  placement-wording item stays open (human-only) and already covers every stale
  design.md placement passage (Composition lines 109-110, D1 lines 119-120;
  line 97 "after `review-task-design`" is correct, not stale).

## Implementation (Slices 1–5)

Implemented all five slices in one pass on `exhaustion-routing`.

- **Slice 1** — `routing/parse-scope-question-gate` (pure, IO-free) + 9 unit
  tests / 236 assertions. Open item = left-trimmed line starting `- [ ]` then
  (after optional ws) the marker; concern = trimmed text after the marker.
  Checked `- [x]`/`- [X]` never match the `- [ ]` prefix.
- **Slice 2** — `resolvers/session/agent-session-task-artifact-content`
  (working-tree read; nil-safe on missing inputs/file) registered in
  `session-resolvers/resolvers`. New `task_artifact_content_resolver_test`.
- **Slice 3** — `workflow/core/scope-question-gate-routing` operation +
  `routing/normalize-open-task-path` (open-only, anchored; bare token →
  `munera/open/<token>`; else nil fail-open). Handler resolves owning session as
  `(or parent-session-id session-id)`, passes `(:ctx invocation)` positionally
  to `resolvers/query-in` with session-id in extra-entity. 7 tests / 17
  assertions incl. the required judge-path divergence guard.
  - **Deviation (minor):** made the operation handler `scope-question-gate-routing`
    a **public** `defn` (not `defn-`) so the operation test can register the real
    handler into a fresh registry and exercise the actual code (rather than a
    duplicated copy). The plan did not pin visibility; public is consistent with
    the pure `routing/*` parsers being public.
  - **Test faithfulness note:** the judge-path test invokes the registered
    operation through `registry/invoke-operation-in` + `runtime/invoke-operation`
    with an invocation map shaped exactly like
    `workflow_judge/execute-invoke-judge!` produces (`:ctx` + `:parent-session-id`,
    no `:session-id`), asserting the worktree resolves from `:parent-session-id`.
    This exercises the production key-resolution divergence without standing up a
    full workflow run.
  - **Input shape (DI-4 / R1 resolved):** the authored gate arg
    `:task-path {:from :workflow-input :path [:input]}` resolves identically to
    the existing `task-lifecycle` delegate steps' `:input` field (same source
    ref), so no new input-shape assumption; fail-open normalization covers all
    forms.
- **Slice 4** — inserted `check-scope-question-status` at `:steps` index 1 and
  appended `final-summary-scope-question-open` last in `task-lifecycle.edn`;
  `check-design-review-status` `:on` unchanged. `task-lifecycle-test` updated
  (13→15, gate + handback assertions; `repeat 13`→`repeat 15`). 48 assertions.
- **Slice 5** — `doc/workflows.md` (pre-plan scope gate section), CHANGELOG
  `[Unreleased] Changed`, `mementum/state.md` gate bullet.

Regression: agent-session resolvers/operation/graph-surface suites green
(72 tests / 2618 assertions) after adding the resolver + the
`workflow.core → resolvers` require (no load cycle: resolvers does not require
workflow.core).

**Open human-only residual (unchanged):** design.md's gate-placement wording
("after `check-design-review-status`") is still stale vs the implemented "before"
placement — tracked as the unchecked human-only item in `design-steps.md`. The
implementation realizes design.md D1's governing "scope handback wins" decision.

## Out-of-scope pre-existing failure (noted, not fixed)

The full `components/workflow-loader/test` suite has **one** pre-existing failure
unrelated to task 230: `workflow-review-prompt-contract-test/
review-follow-up-plan-prompt-contract-test` (4 assertions). It checks the content
of `.psi/workflows/review-follow-up-plan.md`, last modified by commit `2b7b0face`
("⊘ workflow: review-follow-up-plan may edit steps.md…") — which predates all
task-230 slice commits. None of the 230 commits touch that prompt or its contract
test (`git log 8d7a36b3f..HEAD -- review-follow-up-plan.md
workflow_review_prompt_contract_test.clj` shows only `2b7b0face`). The contract
test still asserts the prompt must say `git diff … steps.md` / "match unchecked
step items" and must NOT contain `design-steps.md`, but the prompt was edited to
reference both `steps.md` and `design-steps.md`. Left for the owning change/task;
fixing it here would mix concerns. All task-230 suites are green
(routing 9/236, resolver 1/3, operation 7/17, task-lifecycle-test 48, plus
agent-session resolvers/operation/graph-surface regression 72/2618).
