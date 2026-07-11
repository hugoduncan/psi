## Test-shaper review (round 10)

- added 1 step: `friction/message-snippet`'s content-block `:type` filter is
  keyword-only, and its keyword-`:type` fixtures mask a keyword-vs-string
  boundary bug — the same class of gap round-7 caught for `:role`, one layer
  down. Verified directly via nREPL: string `:type "text"`/`"error"` blocks
  yield an empty snippet (dropping the message, and the tool-error signal),
  while the canonical `content-text-parts` handles both shapes. Likely an
  implementation fix, then a string-`:type` test.
- No non-compliance observed (same pre-existing unchecked round-1
  task-test-review item noted below still stands, not this review's scope).

## Test-shaper review (round 9)

- added 2 steps. (1) `friction/history-line`'s `:is-error`-flagged-but-
  blank/dropped arm is untested — the `[error]` marker rides only on a
  non-blank, non-slash snippet, so a text-less failed toolResult
  contributes no tool-error signal; boundary verified directly via nREPL
  and `history-line` has no direct unit test at all. (2)
  `render-history-excerpt`'s `turn-count`-truncation arm is dead code (both
  callers pass `nil` after the round-3/7 `last-n-turns` fix) with a stale
  docstring — flagged for removal rather than pinning an unreachable branch.
- No non-compliance observed: one long-standing round-1 `Follow-up
  (task-test-review skill)` item (the mislabeled "retry exhaustion → nil"
  `testing` block) remains unchecked — pre-existing, owned by the
  task-test-review workflow, not this review's concern.

## Test-shaper follow-up (round 7, addressed)

- addressed 2 review steps.
- (1) git-listed-but-absent-from-disk filtering: added
  `recent-closed-tasks-test` "git-listed-but-absent-from-disk id is dropped"
  block (`context_manager_friction_task_files_test.clj`) — commits two
  closed tasks, deletes one off disk (it stays an A-record in git log),
  asserts only the surviving task is returned and the deleted id never
  appears — pinning the `(filter all-ids ids)` git-vs-disk reconciliation
  in `recent-closed-ids-git-filtered`.
- (2) git-success-but-empty fallback: added
  "git-success-but-empty (uncommitted closed dirs) falls back to name-
  descending" block — git-init'd repo with an initial commit and on-disk-
  but-uncommitted closed dirs (git log exit 0, no A-records → `closed-ids-
  by-git-order` nil via `(when (seq ids) ids)`), asserts name-descending
  fallback fires — distinct from the non-git → git-failure fallback the
  existing non-git test covers.
- focused tests: `bb test --focus
  extensions.context-manager-friction-task-files-test` → 5 tests, 28
  assertions, 0 failures. clj-kondo clean.

## Test-shaper review (round 6)

- added 1 step: the analyzer's own literal helper session-name
  `"friction-analysis"` exclusion arm of `known-helper-session-names` is
  untested — every sibling set member (`"entity-resolution"`,
  `"auto-session-name"`, the `workflow-step-session?` dynamic arm) has a
  dedicated exclusion test, but no test drives an incoming session named
  `"friction-analysis"` (the only occurrence of that string in the analysis
  suite asserts the *outgoing* create-child-session param). Gap verified
  real against the current code before filing.
- Non-compliance: one long-standing round-1 `Follow-up (task-test-review
  skill)` item remains unchecked (the mislabeled "retry exhaustion → nil"
  `testing` block whose body asserts a task *is* created) — pre-existing,
  owned by the task-test-review workflow, not duplicated here.

## Test-shaper follow-up (round 5, addressed)

- addressed 2 review steps.
- (1) known-helper exclusion negative boundary: added
  `workflow-step-session-exclusion-negative-boundary-test`
  (`context_manager_friction_analysis_test.clj`) — drives three names that
  only *resemble* the two-sided `"workflow … attempt"` boundary
  (`"my workflow builder attempt notes"` mid-string,
  `"run workflow attempt"` missing starts-with arm, `"workflow builder"`
  missing ends-with arm) through `friction-analysis` and asserts each
  reaches `:success` (not excluded) and the helper run fires — pinning the
  starts-with ∧ ends-with boundary against a `str/includes?` regression.
  Verified all three return `false` from `friction/workflow-step-session?`
  before filing.
- (2) multi-task dedup-list rendering: added
  `build-friction-prompt-multi-task-test`
  (`context_manager_friction_parsing_test.clj`) — drives 3 open + 2
  recently-closed tasks through `build-friction-prompt` and asserts each
  renders as a distinct `NNN-slug: title` line, in order, in the user
  prompt — pinning the production multi-task path against a str/join
  collapse / first-only / order-drop regression.
- focused tests: `bb test --focus
  extensions.context-manager-friction-analysis-test --focus
  extensions.context-manager-friction-parsing-test` → 33 tests, 223
  assertions, 0 failures. clj-kondo clean on both files.

## Test-shaper review (round 5)

- added 2 steps: (1) the known-helper exclusion's negative boundary is
  untested — `friction/workflow-step-session?`'s starts-with ∧ ends-with
  boundary is pinned only by exact positive names; a `str/includes?`
  regression would over-exclude legitimate sessions and pass every test.
  (2) `render-task-list`'s multi-task rendering (the production dedup-list
  shape) is untested — only single/empty task lists are driven. Both gaps
  verified real against the current code before filing.
- Non-compliance: one long-standing round-1 `Follow-up (task-test-review
  skill)` item remains unchecked (the mislabeled "retry exhaustion → nil"
  `testing` block whose body actually asserts a task *is* created) —
  confirmed still valid against current code, pre-existing, owned by the
  task-test-review workflow, not duplicated here.

## Test-shaper review (round 4)

- added 1 step: `parse-friction-output` lacks direct-layer coverage of its
  multi-block splitting and malformed-then-valid recovery (only single-block
  and issue+duplicate cases exist; multi-issue is only reached end-to-end via
  `cap-applied-test`). Recovery/multi-block behaviour verified real against
  the current code before filing.
- Non-compliance: the two long-standing `Follow-up (task-test-review skill)`
  (round-1) items in steps.md remain unchecked (no-tools `:tool-ids []`
  grant assertion; mislabeled "retry exhaustion → nil" `testing` block) —
  confirmed still valid, pre-existing, owned by the task-test-review
  workflow, not duplicated here.

## Task-test-review (round 3)

- Checked off round-1 item 1 (no-tools `:tool-ids []`/`:tool-names []`
  grant to `create-child-session`): now covered by
  `default-friction-run-helper-settled-run-test` (added in the round-2
  follow-up commit `1a46891b8`). Round-1 item 2 (mislabeled "retry
  exhaustion → nil, no task created" `testing` block) remains valid and
  unchecked. No new steps added — test net is well-formed (no mocks/stubs,
  injectable/nullable collaborators, state/output assertions) and covers
  design behaviour.

## Task-test-review round-2 follow-up (addressed)

- Addressed 2 task-test-review round-2 follow-up steps (the immediately
  preceding review pass, commit `05cbecc76`):
  - Added `default-friction-run-helper-settled-run-test`
    (`context_manager_friction_helper_runtime_test.clj`) asserting the
    settled-run path: returned text, `:session-name "friction-analysis"`
    + no-tools `:tool-ids []`/`:tool-names []` in `:create-calls`,
    selected-model threading into run params, and child close+untrack in
    `friction-helper-session-ids` on success.
  - Strengthened `render-friction-design-md-test`
    (`context_manager_friction_parsing_test.clj`) to assert the marker
    identifies the context-manager/task-239 analyzer and that `:title`
    renders as the H1 heading; strengthened `create-friction-task-test`
    (`context_manager_friction_task_files_test.clj`) to assert the
    *written* design.md contains the auto-generated marker (with
    context-manager/task-239 attribution) plus friction/evidence/
    suggestion content, not just the title.
- The two `Follow-up (task-test-review skill)` (round-1) items remain
  unchecked by design: they predate this preceding review pass and are
  left for their owning workflow / human decision.
- `bb test --focus` (3 friction test namespaces): 15 tests, 71
  assertions, 0 failures; clj-kondo clean on the 3 changed test files.

## Implementation review, round 15 (task-implementation-review skill)

- No new actionable issues. Verified round-14 empty-history guard in
  `friction/run-analysis` (`str/blank? history-excerpt` no-op mirroring
  the `:worktree-root` guard); `render-history-excerpt` returns `nil` on
  an empty tail, so the guard covers both `nil` and `""`. `clojure -M:test
  --focus extensions` green (349 tests, 1470 assertions, 0 failures);
  working tree clean; file lengths under the 800-line ratchet (786/556).
- Non-compliance: the two `Follow-up (task-test-review skill)` items in
  steps.md remain unchecked (no-tools `:tool-ids []`/`:tool-names []`
  grant assertion; mislabeled "retry exhaustion → nil" testing block) —
  confirmed still valid against current code, not new, owned by the
  task-test-review workflow (not this implementation review). No steps
  added (no new issues; existing items not duplicated).

## Implementation review, round 14 (task-implementation-review skill)

- added 1 step to steps.md: `run-analysis` runs the bounded local-model
  helper even on an empty/blank history excerpt (no `str/blank?` guard
  before `run-helper`), wasting a helper session and risking a
  hallucinated task on empty-history turns.
- Non-compliance: the two `Follow-up (task-test-review skill)` items in
  steps.md remain unchecked (no-tools `:tool-ids []` grant assertion;
  mislabeled "retry exhaustion" testing block) — confirmed still valid,
  not new, owned by the task-test-review workflow.

## Implementation review, round 13 (task-implementation-review skill)

- No new actionable issues. Verified round-11 file-length fix complete
  (friction call sites qualified, ns loads, lint clean, file 786 lines,
  `bb commit-check:file-lengths` passes), `clojure -M:test --focus
  extensions` green (348 tests, 1458 assertions, 0 failures).
- Non-compliance: the two `task-test-review` follow-up items in steps.md
  (no-tools `:tool-ids []`/`:tool-names []` assertion; mislabeled
  "retry exhaustion → nil" `testing` block) remain unchecked and
  unaddressed — confirmed still valid against current code. No steps added
  (they already exist; not duplicated). The shared
  `bounded-helper-session-run` extraction (later rounds) strengthens the
  first item's rationale.

## Implementation review, round 8 (task-implementation-review skill)

- No new actionable issues found. Re-read design.md/plan.md/steps.md/
  design-steps.md, all of `friction.clj` and `context_manager.clj`'s
  friction-related code, `doc/extensions.md`/`CHANGELOG.md` friction
  sections, and `.psi/extensions.edn`; ran `bb test --focus extensions`
  (347 tests, 1454 assertions, 0 failures) and `clj-kondo --lint` (clean).
  No steps added to steps.md.- steps.md: both round-4 follow-up items checked off; no items remain
  unchecked in that section.

## Follow-up execution (post-review pass, round 6)

- Fixed the round-6 check-then-act race in `friction-analysis`
  (`extensions/context_manager.clj`): the previous body did a plain
  `(contains? @friction-in-flight-session-ids session-id)` read followed by
  a separate `(swap! friction-in-flight-session-ids conj session-id)`, with
  no atomicity between the two. Replaced with a single atomic
  `(swap-vals! friction-in-flight-session-ids conj session-id)` — the
  returned `[prior new]` pair lets the caller see whether `session-id` was
  already present in the *same* atomic operation that adds it, closing the
  window rather than narrowing it. No other behaviour change (the
  `finally`-based release on completion is unchanged).
- Added `truly-concurrent-runs-same-session-atomic-claim-test`
  (`context_manager_friction_analysis_test.clj`): starts two calls for the
  same `session-id` with a `CountDownLatch`+shared-promise handshake (no
  ordering between them — neither waits for the other to reach a blocking
  point), with a small `Thread/sleep` inside the injected `:run-helper` to
  widen the claim-to-completion window, repeated 20 times with the
  in-flight atom reset between iterations; asserts exactly one run reaches
  `:success` and exactly one is turned away with `:no-op` every iteration.
  This is the same shape the review's minimal harness reproduced the bug
  with (20 concurrent callers, small delay between read and swap, all 20
  previously reporting `:claimed`); against the atomic `swap-vals!` fix all
  20 iterations correctly show exactly one claimant. Left the existing
  `concurrent-run-same-session-guarded-test` (ordered handshake) in place —
  it still validates the guard's user-facing skip/diagnostic behaviour;
  the new test targets the atomicity property specifically.
- Verification: `clojure -M:test --focus
  extensions.context-manager-friction-analysis-test` → 14 tests, 129
  assertions, 0 failures, stable across 5 repeated runs.
  `clojure -M:test --focus extensions` → 347 tests, 1450 assertions, 0
  failures. `clj-kondo --lint` clean on both changed files.
  `clj-paren-repair` clean. No doc/CHANGELOG changes needed — this is an
  internal atomicity fix to not-yet-released behaviour already described in
  `doc/extensions.md`'s in-flight-guard bullet and the `[Unreleased]`
  CHANGELOG entry; no new user-observable behaviour.
- steps.md: the round-6 follow-up item checked off; no items remain
  unchecked in that section.

- addressed 1 review step

## Implementation review, round 6 (task-implementation-review skill)

- Added 1 follow-up step to steps.md: the round-4 in-flight guard
  (`friction-in-flight-session-ids`) has a check-then-swap race
  (`contains?` read, then separate `swap!`), reproduced with a minimal
  harness — it narrows but doesn't close the concurrent-duplicate-task
  race it was added to prevent.
- Round-5 follow-up: added a "Per-session in-flight guard" bullet to
  `doc/extensions.md`'s context-manager friction-analyzer section,
  describing `friction-in-flight-session-ids` (round-4 follow-up,
  `friction-analysis`'s skip/no-op behaviour for a second in-flight run on
  the same session), alongside the existing scope/cap/dedup bullets.
  Checked off the round-5 steps.md item.
- addressed 1 review step

## Implementation review, round 5 (task-implementation-review skill)

- Added 1 follow-up step to steps.md: `doc/extensions.md` was not updated
  for the round-4 follow-up's per-session in-flight guard.- architectural review (design-review turn 1) added 1 new design step: the
  planned recursion-guard atom copies the extension-local `helper-session-ids`
  pattern rather than the ctx-keyed managed-service model documented in
  `ramora/META.md`. Reviewed against AGENTS.md, ramora/META.md, and
  doc/architecture.md; autonomous task-file creation itself (writing
  `munera/open/NNN-slug/design.md` directly, outside `:state*`/dispatch) is
  not flagged — munera task files are git-tracked project artifacts, not
  canonical root state, and the write mechanism is left to planning.
- ambiguity review (design-review turn 2) added 2 new design steps: (1) the
  per-run task-creation cap is stated only as a suggested range ("1–2"), not
  a decided value, despite acceptance criterion 6 requiring a cap; (2) the
  recursion-guard scope ("except the extension's own helper sessions") leaves
  open whether other extensions'/runtime infra helper sessions (e.g.
  entity-resolution helpers) should also be excluded as analysis inputs.
- inconsistency review (design-review turn 3) added 1 new design step: the
  Goal's "create a task for each newly identified issue" contradicts the
  Constraints' per-run cap of 1–2 tasks (AC6). Verified against
  extensions/context-manager source that the design's factual claims (existing
  `session_turn_finished` subscription, `psi.ai.model-selection` helper
  machinery, munera design.md-only task-creation convention, NNN allocation
  rule) all match the current codebase/AGENTS.md — no discrepancies found
  there.

## Design-follow-up completion (design-review batch: turns 1–3)

- Baseline for this follow-up: `545956c44^` (parent of the oldest of the 3
  design-steps-adding commits `545956c44`/`abe5d7066`/`99f29e624`, confirmed
  via `git diff 545956c44^..HEAD -- design-steps.md` showing all 4 items as
  additions with no prior follow-up commit existing).
- All 4 unchecked items were in-scope (added by the batch, still unchecked)
  and were resolved by editing design.md, then checked off in
  design-steps.md:
  1. Recursion-guard state: added an explicit "Recursion-guard state"
     decision requiring planning to choose ctx-keyed-managed-service vs.
     extension-local atom, rather than defaulting silently.
  2. Cap ambiguity: Constraints now states the cap as a decided single
     number (2), removing the "suggest: 1–2" range language.
  3. Helper/infra session scope: Scope-of-sessions decision now explicitly
     excludes other extensions'/runtime's known helper/infra sessions (e.g.
     entity-resolution helpers), not just this analyzer's own helpers.
  4. Goal wording: reworded to "create a Munera task for newly identified
     issues, up to a per-run cap" to match the AC6/Constraints cap.
- Cap value chosen as **2** (upper end of the prior suggested range) —
  future review/planning should treat this as final unless re-opened.

- architectural review (design-review round 2, turn 1) — no new architectural
  review feedback. Re-checked against AGENTS.md, ramora/META.md, and
  doc/architecture.md after the round-1 follow-up edits (ctx-guard decision,
  cap=2, session scope, Goal wording); no new architectural misfit found.
- ambiguity review (design-review round 2, turn 2) added 2 new design steps:
  (1) "recently-closed" duplicate-suppression window is undefined (no time
  bound / count / lookback rule); (2) whether dedup/duplicate-matching runs
  as part of the same bounded helper-session call as friction detection, or
  as a separate helper-session invocation, is unstated — this matters
  because a second helper call would need its own bounding/recursion-guard
  accounting, which the design doesn't currently mention.
- inconsistency review (design-review round 2, turn 3) added 1 new design
  step: the Decisions section's "Scope of sessions" exclusion was broadened
  (round 1 follow-up) to cover other extensions'/runtime's helper/infra
  sessions too, but AC5 and the AC7 test-coverage list still only name the
  analyzer's own-helper-session exclusion/recursion guard — the acceptance
  criteria text lags behind the widened Decisions scope.

## Notes for addressing accumulated design-steps

- All 4 design-steps are design.md edits (wording/decision clarifications),
  not code changes — resolve them by editing design.md itself, then re-check
  coherence with plan.md/steps.md only if those files already exist and
  reference the changed wording.
- Keep edits minimal/localized per `AGENTS.md`'s
  `λreq. λspec. localized_change(...) ∧ ¬broad_restructure(spec)` — don't use
  these steps as an excuse to rewrite unrelated Decisions/Constraints prose.
- Recursion-guard steps (arch step 1, ambiguity step 2) are related: deciding
  the ctx-keyed-vs-atom question may also inform (but should not expand into
  deciding) which other sessions count as "helper/infra" for exclusion
  purposes — keep these as two separate, narrow edits.
- The cap-value ambiguity (ambiguity step 1) and the Goal-wording
  inconsistency (inconsistency step 1) both trace back to the same
  Constraints sentence ("suggest: 1–2"); fixing the cap to one concrete
  number in Constraints and then aligning the Goal sentence's wording to it
  resolves both in one coordinated edit rather than two independent ones.
- Relevant non-task files for context (already consulted, no need to re-read
  in full): `ramora/META.md` (managed-service-vs-extension-atom principle),
  `AGENTS.md` (task-creation/design.md-only convention, NNN allocation rule),
  `extensions/context-manager/src/extensions/context_manager.clj` (existing
  `helper-session-ids`/`entity-resolution-helper-session-ids` atoms and
  `session_turn_finished` subscription this design extends).

## Notes for addressing round-2 design-steps (3 open items)

- Baseline: all 3 open items were added in this round-2 batch (ambiguity
  turn-2 x2, inconsistency turn-3 x1); none pre-date it, so all 3 are
  in-scope to resolve together.
- All 3 remain design.md wording/decision edits, not code changes — same
  resolution pattern as round 1 (edit design.md, then check off in
  design-steps.md).
- The two ambiguity items (recently-closed window; whether dedup runs inside
  the same helper-session call as detection or a separate one) are coupled:
  resolving "one helper session, two phases (detect + dedup-match)" vs.
  "two helper sessions" affects how a recently-closed lookback would be
  implemented/tested, so decide the helper-session-shape question first, then
  state the recently-closed window in terms of that shape (e.g. "closed
  within the current recursion-guard/analysis-session lifetime" vs. an
  absolute time bound) — don't fix the window wording before the shape
  decision or it may need re-wording afterward.
- The inconsistency item (AC5/AC7 not reflecting the broadened
  Scope-of-sessions exclusion) is independent of the two ambiguity items —
  it's a pure sync-up of Acceptance criteria wording to already-decided
  Decisions text; resolve by adding the "other extensions'/runtime's known
  helper/infra sessions" phrase to AC5 and to the AC7 test list, mirroring
  the Decisions section's existing phrasing rather than inventing new
  wording.
- Keep edits minimal/localized (same `AGENTS.md` guidance as round 1) — don't
  use these steps as a reason to restate or reorganize the whole Decisions/
  Acceptance criteria sections.
- No new non-task files needed beyond those already listed above for round 1;
  these 3 items are resolvable from design.md content alone.

## Design-follow-up completion (design-review batch: round 2, turns 2–3)

- Segment: commits `1ae167bca` (ambiguity round 2, turn 2) and `3661627c9`
  (inconsistency round 2, turn 3). Baseline: `1ae167bca^` (parent of the
  oldest of the two), confirmed via
  `git diff 1ae167bca^..HEAD -- design-steps.md` showing all 3 items as pure
  additions.
- All 3 unchecked items were in-scope and resolved by editing design.md,
  then checked off in design-steps.md:
  1. Helper-session shape: Dedup decision now states dedup-matching is a
     second phase of the *same* bounded helper session as friction
     detection (not a separate call), so no second recursion-guard/bounding
     accounting is needed.
  2. Recently-closed window: defined (given the shape decision above) as a
     fixed count — the N=20 most-recently-closed tasks by closure order,
     not a time window — chosen so the list stays boundable within the
     single session's existing output-size limit.
  3. AC5/AC7 sync: both now name exclusion of other known helper/infra
     sessions (entity-resolution, other workflow helpers), matching the
     already-broadened Decisions "Scope of sessions" text.
- N=20 is a planning-stage placeholder chosen for concreteness (not derived
  from a stated constraint) — future review/planning may revise this count
  if it proves wrong in practice.

- architectural review (design-review round 3, turn 1) — no new architectural
  review feedback. Re-checked against AGENTS.md, ramora/META.md, and
  doc/architecture.md; the current design.md (round-2 follow-up state:
  ctx-guard-vs-atom deferred as deliberate planning decision, cap=2 fixed,
  helper/infra session scope broadened, dedup as single-helper-session second
  phase, N=20 fixed recently-closed count) remains architecturally consistent
  — confirmed the existing `entity-resolution-helper-session-ids` atom
  pattern in `extensions/context-manager.clj` this design extends already
  performs the same kind of session-id exclusion check the design describes,
  and file-based `munera/open/NNN-slug/design.md` task creation stays outside
  `:state*`/dispatch (git-tracked project artifact, not canonical root
  state), so no dispatch-boundary violation.

- ambiguity review (design-review round 3, turn 2) added 1 new design step:
  "the session's effective worktree" (Task location) is undefined for
  delegated/workflow sessions, which may run in a different checkout than
  their parent/originating session — the design doesn't say whether task
  creation targets the analyzed session's own worktree or one resolved by
  walking up to an originating/top-level session.

- inconsistency review (design-review round 3, turn 3) added 1 new design
  step: the Dedup decision bounds the closed-tasks list to N=20 for
  output-size reasons but leaves the open-tasks list (checked in the same
  dedup call) unbounded, undermining its own stated boundedness rationale.

## Notes for addressing round-3 design-steps (2 open items)

- Both open items (effective-worktree ambiguity, open-tasks-list bound
  inconsistency) were added in this round-3 batch; neither pre-dates it —
  both are in-scope to resolve together.
- Same resolution pattern as rounds 1–2: these are design.md wording/decision
  edits, not code changes — edit design.md, then check off in
  design-steps.md.
- Keep edits minimal/localized (`AGENTS.md`'s
  `λreq. λspec. localized_change(...) ∧ ¬broad_restructure(spec)`) — resolve
  each in its own Decisions-section sentence rather than restating adjacent
  prose.
- The two items are independent: "effective worktree" resolution (which
  worktree delegated/workflow sessions target) doesn't bear on the
  open-tasks-list bounding question, and vice versa — no shared decision
  blocks either.
- For "effective worktree": resolving it need only state the resolution rule
  (e.g. "the analyzed session's own worktree, not resolved through parent
  sessions" or the reverse) — no new mechanism needs designing, since
  worktree resolution presumably already exists for delegated/workflow
  sessions elsewhere in the runtime (check `app-runtime`/`agent-session`
  worktree resolvers before assuming this needs new design surface).
- For the open-tasks bound: the simplest resolution consistent with the
  existing N=20 closed-list precedent is stating an analogous fixed count
  for open tasks (or explicitly noting open-task count is expected to stay
  small and doesn't need bounding in v1) — avoid inventing a different
  bounding mechanism (e.g. time-window) than the one already chosen for
  closed tasks, to keep the two dedup-list bounding rules symmetric.
- Relevant non-task files already consulted for this round (no need to
  re-read in full): `doc/architecture.md` (adapter/app-runtime ownership,
  no worktree-resolution detail found there), `ramora/META.md`
  (managed-service principle, unrelated to these two items),
  `extensions/context-manager/src/extensions/context_manager.clj`
  (existing helper-session-id atoms, session_turn_finished subscription).

## Design-follow-up completion (design-review batch: round 3, turns 2–3)

- Segment: commits `85b96ce61` (ambiguity round 3, turn 2) and `63ec1df55`
  (inconsistency round 3, turn 3). Baseline: `85b96ce61^` (parent of the
  oldest of the two), confirmed via
  `git diff 85b96ce61^..HEAD -- design-steps.md` showing both items as pure
  additions with no intervening follow-up commit.
- Both unchecked items were in-scope and resolved by editing design.md, then
  checked off in design-steps.md:
  1. Effective worktree: "Task location" decision now states it means the
     analyzed session's own worktree (the checkout it is actually running
     in), even for delegated/workflow sessions — v1 does not walk up to an
     originating/top-level session's worktree.
  2. Open-tasks dedup bound: Dedup decision now states the open-tasks side
     of the list is unbounded in v1 (all open tasks passed), on the
     assumption open-task count stays small enough for the helper session's
     output-size limit; revisit with a bound later if that assumption
     breaks. This chose the "no bound needed in v1" resolution rather than a
     symmetric fixed-count bound, since open-task volume is expected to
     differ in growth pattern from the closed-task history.

- architectural review (design-review round 4, turn 1) — no new architectural
  review feedback. Re-checked against AGENTS.md, ramora/META.md, and
  doc/architecture.md; design.md is unchanged since the round-3 follow-up
  (ctx-guard deferred as deliberate decision, cap=2, effective worktree
  defined, dedup single-helper-session phase, N=20 closed bound, open-tasks
  unbounded rationale) and remains architecturally consistent — no new gap.

- ambiguity review (design-review round 4, turn 2) — no new ambiguity
  feedback. Reused design.md/context from turn 1 (unchanged since round-3
  follow-up); remaining deferred items (exact dedup-matching mechanism,
  ctx-keyed-vs-atom recursion-guard choice) are already explicitly marked as
  planning-stage decisions in design.md's Open questions / Decisions, not
  unresolved ambiguity — no new unclear wording found.

- inconsistency review (design-review round 4, turn 3) — no new
  inconsistency feedback. Reused design.md/context from turns 1–2 (unchanged
  since round-3 follow-up). Checked AC6's generic "capped per analysis run"
  wording against Constraints' concrete "at most 2" figure — not treated as
  a new inconsistency, since AC6/AC7 intentionally refer to whatever value
  Constraints defines rather than restating it, matching the resolution
  already accepted in round 1 (inconsistency turn 3) for the Goal-wording
  fix; no other Decisions/Constraints/AC/Open-questions mismatch found.

## Round 4 outcome (all three review turns — no design-steps added)

- design-steps.md has zero unchecked items after round 4: architectural,
  ambiguity, and inconsistency turns all found design.md unchanged and
  fully consistent since the round-3 follow-up commits. There is no
  design-steps follow-up task to perform for this round — design.md needs
  no further edits before planning/implementation proceeds.
- If a future review round does add new unchecked items, follow the same
  pattern used for rounds 1–3 (see the "Design-follow-up completion"
  entries above): resolve each by editing design.md directly (these have
  all been wording/decision clarifications, not code changes), then check
  the item off in design-steps.md, confirming the batch's baseline commit
  via `git diff <parent-of-oldest-adding-commit>..HEAD -- design-steps.md`
  before starting.
- Remaining explicitly-deferred (not ambiguous) planning-stage decisions to
  carry forward, already stated in design.md itself: (1) ctx-keyed-managed-
  service vs. extension-local `defonce` atom for the recursion-guard state
  (`ramora/META.md` process-scoped-managed-service principle applies), and
  (2) exact duplicate-matching mechanism (model-judged vs. slug similarity).
  Neither needs a design-steps entry — both are already framed in
  design.md's Decisions/Open-questions as planning's job, not design's.
- No new non-task files were read this round beyond what's already listed
  in earlier "Notes for addressing ... design-steps" sections
  (`ramora/META.md`, `AGENTS.md`, `doc/architecture.md`,
  `extensions/context-manager/src/extensions/context_manager.clj`).

- ambiguity review (plan-review session, turn 1) — no ambiguity review
  feedback. Read plan.md/steps.md (steps.md treated read-only) against
  design.md and current `extensions/context_manager.clj` (existing
  `entity-resolution-helper-session-ids`, `default-run-helper`,
  `default-select-model`, `render-history-excerpt`); plan's stated deviations
  from literal reuse (e.g. "bash-tool-less" helper vs. `default-run-helper`'s
  hardcoded `:tool-ids ["bash"]`) are self-consistent adaptations of the
  existing pattern, not unstated ambiguity — no new design-steps added.

- inconsistency review (plan-review session, turn 2) — no inconsistency
  review feedback. Cross-checked plan.md ↔ steps.md ↔ design.md using
  context already loaded from turn 1 (no new re-reads needed): slice
  titles/order, collaborator names (`:select-model`/`:run-helper`/
  `:fetch-history`/`:list-tasks`/`:create-task!`/`:session-info`), the
  `friction-helper-session-ids` atom name, cap=2, and the 120s wall-clock
  budget all match consistently across plan.md and steps.md, and the
  session-exclusion guard in both correctly maps onto design.md's
  own-helper-vs-other-known-helper/infra distinction. No new design-steps
  added.

## Plan-review batch (turns 1–2): no design-steps follow-up needed

- This batch (ambiguity turn 1, inconsistency turn 2) added zero unchecked
  items to design-steps.md — plan.md/steps.md remain fully consistent with
  design.md as-is. There is no follow-up task to perform before
  implementation proceeds; skip the usual "resolve → check off" cycle for
  this batch.
- If a later plan-review round (or the plan review itself is re-run after a
  plan.md/steps.md edit) does add unchecked items, the prior
  design-review resolution pattern applies analogously but targets
  plan.md/steps.md instead of design.md: edit the plan/steps artifact that
  the item concerns, then check the item off in design-steps.md, confirming
  the batch's baseline commit via
  `git diff <parent-of-oldest-adding-commit>..HEAD -- design-steps.md`
  first (same convention used for all prior design-review batches above).
- No new non-task files were needed for this plan-review batch beyond what
  was already read for the design-review rounds (listed in earlier "Notes
  for addressing ... design-steps" sections); `extensions/context-manager/
  src/extensions/context_manager.clj` was re-consulted (not re-read in full)
  only to confirm `default-run-helper`'s hardcoded `:tool-ids ["bash"]`
  doesn't conflict with plan's "bash-tool-less" helper variant.

## Slice 1 implementation (pure core)

- `render-history-excerpt` generalized to 3-arity
  `([history] [history turn-count char-cap])`; 1-arg call preserves prior
  entity-resolution behaviour exactly (`nil` turn-count = no tail
  truncation, `max-history-chars` cap). Friction analyzer will call the
  2-extra-arg form with `friction-history-turn-count` (4) and
  `max-history-chars` — no separate char-cap constant introduced for
  friction; reusing the existing one per plan step 6 ("adapting" not
  duplicating).
- Added under a new "Post-turn tooling-friction analyzer (task 239)"
  section, mirroring the entity-resolution section's pure-core/
  parsing/rendering/orchestration layout: `friction-history-turn-count`
  (4), `render-task-list`, `build-friction-prompt`,
  `parse-friction-output`, `cap-issues`, `render-friction-design-md`.
  `cap-issues` takes its cap as a parameter (no module-level cap
  constant needed yet); the design.md-decided cap value (2) and the
  closed-task dedup-list bound (N=20) will be introduced as constants in
  slices 2/3 where they're first referenced (avoids an unused-private-var
  lint warning for constants with no caller yet).
- `parse-friction-output` block-scanning: an `ISSUE:` line starts a block
  that greedily consumes following lines until the next `ISSUE:` or
  `DUPLICATE:` line (or end of input); this lets `FRICTION/EVIDENCE/
  SUGGESTION:` lines appear in any order within a block (matched via
  `some` + prefix-strip) and tolerates blank lines between fields.
  `parse-friction-block` requires all four of
  slug/title/friction/evidence/suggestion non-blank or the whole block is
  dropped (fail-safe, no partial-issue task).
- `DUPLICATE: <slug> ~ <existing-id>` lines are collected independently of
  ISSUE blocks (order-preserving relative position not required by
  callers — orchestration in slice 3 will just log each duplicate).
- `cap-issues` is a pure, order-preserving `take`/`drop` split — no
  ranking/scoring, matching plan step 8 ("take first 2 parsed issues").
- `render-friction-design-md` produces design.md content only (no
  plan.md/steps.md — that's slice 2's `create-friction-task!` job); the
  auto-generated marker text explicitly names "the context-manager
  post-turn tooling-friction analyzer (task 239)" per design's Constraints
  requirement.
- New test ns:
  `extensions/context-manager/test/extensions/context_manager_friction_parsing_test.clj`
  (mirrors `context_manager_rendering_test.clj` naming style). Full
  `bb test --focus extensions` run: 317 tests, 1256 assertions, 0
  failures (confirms `render-history-excerpt` generalization didn't
  regress existing entity-resolution rendering tests). `clj-kondo` clean
  except two expected "unused private var" warnings for
  `friction-task-cap`/`friction-recent-closed-limit`, which slice 2/3
  will consume.

## Slice 2 implementation (task-file creation)

- Extracted the entire post-turn friction analyzer pure-core/task-file
  section out of `extensions/context_manager.clj` into a new
  `extensions.context-manager.friction` namespace
  (`extensions/context-manager/src/extensions/context_manager/friction.clj`).
  Reason: adding slice 2 pushed `context_manager.clj` to 843 lines, over the
  bb.edn `file-length-legacy-max-lines` default ratchet (800); rather than
  adding a legacy-ratchet exception, split into a second namespace (more code
  is still coming in slices 3/4, so this avoids re-hitting the ratchet
  repeatedly). `context_manager.clj` re-exports the friction ns's public fns
  as plain `def` aliases (`build-friction-prompt`, `parse-friction-output`,
  `cap-issues`, `render-friction-design-md`, `allocate-task-id`,
  `next-free-task-id`, `create-friction-task!`, `open-tasks`,
  `recent-closed-tasks`) so existing/slice-1 tests and callers keep using
  `context-manager/...` unchanged. Slice 3's orchestration fn can call either
  the re-exported aliases or `friction/...` directly — no preference yet.
- `allocate-task-id` scans `munera/open/` ∪ `munera/closed/` directory names
  matching `^(\d+)-.+$`, taking `max + 1`, zero-padded to 3 digits (`"001"`
  when nothing exists).
- `create-friction-task!`'s retry-on-collision logic is NOT reachable via
  pre-existing directories alone: `allocate-task-id` always scans the full
  directory listing first, so any directories a test pre-creates are already
  reflected in the computed starting NNN (there's no way to make the initial
  scan "miss" existing dirs). The retry loop only matters for a genuine
  concurrent-writer race (another process creates `NNN-slug` after our scan
  but before our `mkdirs`). To keep this testable without real concurrency,
  the collision/retry/exhaustion algorithm is factored into a pure
  `next-free-task-id [start-n slug taken? max-retries]` (fake `taken?`
  predicate, no filesystem) — `create-friction-task!` calls it with a real
  `.exists`-backed `taken?`. `next-free-task-id` is re-exported too, and is
  the thing slice 2's "retry exhaustion" test actually exercises directly;
  the `create-friction-task!`-level test for that scenario only asserts the
  happy path still works (documented in a test comment — see
  `context_manager_friction_task_files_test.clj`).
- `recent-closed-tasks` git-ordering: `git log --format=%H --name-only
  --diff-filter=A -- munera/closed/`, most-recent commit first (git's
  default `log` order), taking the first `munera/closed/<id>/...` path
  component per commit, `distinct` (keeps first/most-recent occurrence).
  Falls back to `sort` descending by directory name when git exits non-zero
  or the parsed id list is empty (covers both "not a git repo" and "no
  closed tasks yet" cases uniformly).
- `task-title` (shared by `open-tasks`/`recent-closed-tasks`) reads the
  first `# ` heading line of a task's `design.md` for its title, falling
  back to the task id itself when `design.md` is missing or has no heading.
- New test file:
  `extensions/context-manager/test/extensions/context_manager_friction_task_files_test.clj`.
  `bb test --focus extensions`: 322 tests, 1275 assertions, 0 failures
  (up from 317/1256 after slice 1). `clj-kondo` clean on both source files
  and the new test file. `clj-paren-repair` clean (no reformatting needed).
- Full-repo `bb test` still shows pre-existing, unrelated failures in
  `psi.turn-runtime.accumulator-test`/`psi.turn-runtime.core-test`
  (statecharts working-memory/turn-data errors) — unrelated to
  context-manager/friction code, not introduced by this slice (confirmed by
  file/namespace scope; these failures are outside `extensions/`).

## Slice 3 implementation (orchestration)

- Recursion-guard state decision (plan.md decision 1, confirmed at
  implementation): extension-local `defonce friction-helper-session-ids`
  atom, not a ctx-keyed managed service. Rationale recorded in-code: the
  extension API map exposes no ctx to key a managed service on, and both
  pre-existing guards (`helper-session-ids`,
  `entity-resolution-helper-session-ids`) already use the atom pattern.
  Migrating all three atoms to a ctx-keyed managed service (per
  `ramora/META.md`'s process-scoped-managed-service model) is recorded here
  as a candidate follow-up task, not done in this task.
- `friction-analysis` orchestration added directly in
  `extensions/context_manager.clj` (not the `friction` ns) — it needs
  `default-select-model`, `helper-wall-clock-ms`, `blank?`,
  `entity-resolution-helper-session-ids`, and the friction pure-core
  re-exports, all already local to this ns; placed immediately after
  `default-run-helper` (before `entity-resolution-augmentation`) so its
  `default-select-model` reference resolves at compile time.
- `known-helper-session?` combines three signals: membership in
  `friction-helper-session-ids` (own helpers), membership in
  `entity-resolution-helper-session-ids` (task-238 helpers), and
  `known-helper-session-names` (`#{"entity-resolution" "friction-analysis"}`)
  matched against the injected `:session-info` collaborator's
  `:session-name` — the name-based check is the "known helper/infra
  session" backstop design.md's Scope-of-sessions decision calls for
  (e.g. would also catch an entity-resolution helper session this
  extension's own atom didn't happen to have tracked, such as after a
  process restart).
- `default-friction-run-helper` mirrors `default-run-helper`'s
  future-owns-teardown timeout handling exactly, but requests a **no-tools**
  child session (`:tool-ids []`, empty `:tool-names`) per plan.md decision 9
  — the friction helper only reasons over the prompt-embedded excerpt +
  task list, no bash needed.
- `friction-analysis`'s collaborator seam:
  `:select-model`/`:run-helper`/`:fetch-history`/`:session-info`/
  `:list-tasks`/`:create-task!`, matching plan.md decision 10 exactly. Every
  collaborator call (including `session-info`, called before the
  known-helper-session guard) is wrapped in its own `try/catch Throwable`,
  and the whole orchestration body is additionally wrapped in an outer
  `try/catch Throwable` that logs and returns `{:status :no-op :diagnostic
  "error"}` — belt-and-braces so no combination of throwing collaborators
  can escape to the (not-yet-wired) fire-and-forget caller.
- Return shape (new, not in design.md/plan.md verbatim — chosen for
  testability): `{:status :no-op :diagnostic ..}` for every guard/failure
  exit, or `{:status :success :created-task-ids [..] :duplicate-diagnostics
  [{:slug :existing-id} ..] :dropped-count n}` on completion (including the
  zero-issues case, which is `:success` with an empty `:created-task-ids`,
  not `:no-op` — a clean "nothing to report" run is not a failure path).
  The event-subscription wiring (slice 4) will discard this value; it
  exists purely so tests can assert on outcomes without a real model/
  sessions.
- Per-run cap (`friction-task-cap` = 2, a new private constant in this
  slice) is applied via the existing `friction/cap-issues`; dropped count is
  logged as a diagnostic string, not returned as data beyond
  `:dropped-count`.
- Gotcha for future slices: manually inserting a large multi-`try`/`cond`/
  `let` form via a single text edit is easy to get paren-count wrong on
  (this slice needed one extra `)`, caught via a small ad-hoc Python
  paren-balance script since `clj-paren-repair` silently *mis-repaired* an
  unbalanced two-namespace-move edit into different-but-still-broken code
  earlier in this slice, rather than surfacing the imbalance — verify new
  large orchestration-shaped edits by loading the ns under `clojure -M:test
  --focus <ns>` directly, not just by trusting a clean `clj-paren-repair`
  run).
- New test file:
  `extensions/context-manager/test/extensions/context_manager_friction_analysis_test.clj`,
  covering all AC7 cases plus the all-collaborators-throw case. `bb test`-
  equivalent direct run (`clojure -M:test --focus extensions`): 332 tests,
  1297 assertions, 0 failures (up from 322/1275 after slice 2).
  `clj-kondo --lint` clean on all context-manager src + test files.

## Slice 4 implementation (wiring & real collaborators)

- EQL attributes confirmed against `psi.agent-session.resolvers.session`:
  `:psi.agent-session/message-history` (raw agent-core messages, via
  `query-session`) for history; `:psi.agent-session/worktree-path` for
  effective worktree (task location); `:psi.agent-session/session-name`
  for the known-helper-session-name backstop. No dedicated "helper-flag"
  attribute exists — the design's accepted fallback (atom membership +
  session-name matching, already built in slice 3's
  `known-helper-session?`) is what's actually wired; no new fallback logic
  needed for slice 4.
- `default-fetch-history` queries `:psi.agent-session/message-history` and
  converts each raw message to `{:role :snippet}` via a new
  `friction/message-snippet` (joins `:content` entries where
  `:type :text`) — a deliberately minimal, dependency-free extraction (does
  *not* pull in `psi.agent-session.message-text`, which is not on this
  extension's classpath), then reuses the existing
  `render-history-excerpt` with `friction/friction-history-turn-count`
  (now public, was private) and the existing `max-history-chars` cap —
  exactly per plan.md decision 6 ("adapting", not duplicating).
- `default-session-info` queries `:psi.agent-session/worktree-path` +
  `:psi.agent-session/session-name` and shapes them via a new
  `friction/session-info-of` into the `{:worktree-root .. :session-name
  ..}` collaborator contract `friction-analysis` already expected from
  slice 3.
- `:list-tasks`/`:create-task!` default collaborators are now thin inline
  fns built from the existing slice-2 `open-tasks`/`recent-closed-tasks`/
  `create-friction-task!` re-exports — no new wrapper `defn`s were kept
  (removed after being added, to stay under the file-length ratchet; see
  below).
- `:select-model`/`:run-helper` real defaults were already implemented in
  slice 3 (`default-select-model`, `default-friction-run-helper`) — slice 4
  only had to wire them as `friction-analysis`'s actual defaults, which
  slice 3 already did.
- Fire-and-forget wiring: `init`'s `session_turn_finished` handler now
  spawns `(future (try (friction-analysis api payload) (catch Throwable
  ...)))` after its existing synchronous `on-turn-finished` log call. The
  future body has its own outer `catch Throwable` as a second line of
  defence beyond `friction-analysis`'s own internal outer catch — belt and
  braces so no exception shape can ever reach the turn/dispatch path.
  Handler still always returns `nil` (unchanged contract).
- Manifest/permissions: confirmed no change needed. `.psi/extensions.edn`'s
  `psi/context-manager {}` entry declares no `:allowed-events` restriction,
  and neither did the pre-existing entity-resolution augmenter (which
  already uses the same `create-child-session`/`run-agent-loop-in-session`/
  `close-session` mutation ops) — task 239 adds no new manifest surface.
- File-length ratchet: adding slice 4's real collaborators pushed
  `context_manager.clj` to 815 lines (ratchet: 800). Resolved by moving the
  two purely-mechanical extraction helpers (`message-snippet`,
  `session-info-of`) into the `friction` ns (where the rest of task 239's
  pure/support code already lives) and inlining the two trivial
  `:list-tasks`/`:create-task!` default-collaborator wrappers directly at
  their one call site instead of as separate `defn-`s — no behaviour
  change, `context_manager.clj` back to 797 lines.
- Pre-existing handler tests (`context_manager_test.clj`) started failing
  once the fire-and-forget future was wired in: they asserted
  `(last (:log-lines @state))` immediately after a synchronous handler
  call, which raced against the new concurrent friction-analysis future's
  own async diagnostic log line (using the same nullable-api `state`).
  Fixed by changing those assertions from exact-last-line equality to
  `(some #{expected-line} (:log-lines @state))` membership checks — this
  preserves each test's actual intent (verify the synchronous
  on-turn-finished log format) without being racy against the new
  concurrent logging. This is the general pattern any *future* extension
  of `session_turn_finished`'s synchronous logging should also follow if
  it adds another async side effect sharing the same log sink.
- New integration-style test:
  `extensions/context-manager/test/extensions/context_manager_friction_wiring_test.clj`
  — injects a 200ms-sleeping `:query-fn` into a real `create-nullable-extension-api`,
  confirms the `session_turn_finished` handler returns in well under the
  sleep duration (non-blocking proof), then polls (up to ~2s) for the
  friction-analysis future's own async diagnostic log line to appear
  (proof the analysis actually ran on its own thread). Uses the nullable
  API's real (undoctored) `:query-session` default behaviour (returns
  effectively-empty maps for unqueried attributes) rather than injected
  collaborators, since this test is specifically about the *wiring*
  (real collaborators + fire-and-forget), not `friction-analysis`'s
  internal logic (already fully covered by slice 3's collaborator-injected
  tests).
- `bb test`-equivalent direct run (`clojure -M:test --focus extensions`):
  333 tests, 1300 assertions, 0 failures (up from 332/1297 after slice 3).
  `clj-kondo --lint` clean on all context-manager src + test files.
  `clj-paren-repair` clean.

## Slice 5 implementation (docs & verification)

- Extension namespace docstring (`context_manager.clj`) trimmed to a short
  summary of all three registered behaviours (project-context,
  entity-resolution, friction analyzer) plus a pointer to
  `doc/extensions.md` for full behaviour details — kept short deliberately:
  a full-detail docstring (matching the entity-resolution paragraph's level
  of detail) pushed `context_manager.clj` over the `file-length-legacy-max-
  lines` ratchet (800; file was 816 with the first, fuller docstring draft,
  then 805, then 799 after trimming both the friction and entity-resolution
  summary paragraphs). Full behavioural detail (trigger, scope/exclusions,
  cap=2, dedup mechanism, N=20 closed-task bound, generated-task format,
  failure paths) lives in `doc/extensions.md`'s context-manager section
  instead, mirroring the existing entity-resolution sub-bullet style.
- `doc/extensions.md` and `CHANGELOG.md [Unreleased] → Added` both updated
  with user-visible-behaviour descriptions of the friction analyzer,
  matching design.md's Decisions/Constraints/AC wording (scope/exclusions,
  single bounded no-tools helper session doing detection+dedup as one call,
  cap=2, N=20 closed-task dedup bound, design.md-only auto-generated task
  format, no-op failure paths).
- Acceptance criteria 1–7 verified against design.md and the slice 1–4
  implementation:
  1. AC1 (async, non-blocking) — covered by slice 4's
     `context_manager_friction_wiring_test.clj` (handler returns well under
     a 200ms-sleeping `:query-fn`'s duration).
  2. AC2 (task created with friction/evidence/suggestion/marker) — covered
     by slice 1's `render-friction-design-md` test and slice 3's
     `friction-analysis` issue→task-created test.
  3. AC3 (duplicate → no task + diagnostic) — covered by slice 3's
     duplicate-skip test.
  4. AC4 (failure paths → no-op, no disruption) — covered by slice 3's
     no-model/no-worktree/helper-failure tests and the all-collaborators-
     throw test.
  5. AC5 (never runs on own or other known helper/infra sessions) — covered
     by slice 3's own-helper-session and entity-resolution-helper-session
     exclusion tests, backed by `known-helper-session?`'s three-signal
     check (own atom, entity-resolution atom, session-name backstop).
  6. AC6 (capped per run) — covered by slice 3's 3-issues→2-tasks cap test
     (`friction-task-cap` = 2).
  7. AC7 (test coverage list) — all named cases (issue→created,
     duplicate→skipped, failure paths, both recursion-guard cases, cap)
     confirmed present across the slice 1–4 test files.
  All seven criteria are satisfied by existing slice 1–4 code/tests; slice
  5 added no new test file, only docs.
- Full `bb test` (whole repo): 2439 passed / 19 failed / 38 errored,
  all in pre-existing unrelated namespaces (streaming/retry/turn-runtime/
  review-workflow tests, same failure set noted after slice 2) — confirmed
  none are in `extensions.context-manager*`. `bb test --focus extensions`:
  333 tests, 1300 assertions, 0 failures (unchanged from slice 4).
  `clj-kondo --lint` clean on all context-manager src + test files (doc/
  extensions.md is not Clojure source and is not kondo-lintable — confirmed
  its lint errors are markdown-parsed-as-edn noise, not real). `clj-paren-
  repair` clean.

## Implementation review (task-implementation-review skill)

- Added 3 follow-up steps to steps.md: a reproducible flaky-test cross-namespace
  atom-pollution bug (`context_manager_model_selection_test.clj` missing
  `use-fixtures` reset, sharing session-id `"s1"` with friction/entity-resolution
  tests — confirmed via 1-in-6 unseeded `clojure -M:test --focus extensions`
  failures), duplicated bounded-helper-session logic between
  `default-run-helper`/`default-friction-run-helper`, and a missing real
  wall-clock-timeout test for `default-friction-run-helper`.

## Follow-up execution (post-review pass)

- addressed 3 review steps:
  - Added `use-fixtures :each` to `context_manager_model_selection_test.clj`
    resetting both `entity-resolution-helper-session-ids` and
    `friction-helper-session-ids` (the actual gap; the only shared-atom-
    touching test file with no fixture — `friction_parsing_test.clj`,
    `friction_task_files_test.clj`, and `rendering_test.clj` were audited
    and confirmed to never reference session-ids or either atom, so no
    fixture gap there).
  - Extracted the shared bounded-child-helper-session mechanism (create
    child → track → bounded run via future-owns-teardown → deref-or-
    timeout → close/untrack) out of `default-run-helper` and
    `default-friction-run-helper` into one private `bounded-helper-
    session-run` fn parameterized on `:session-name`, `:tool-ids`/
    `:tool-names`, and `:tracking-atom`; both original fns are now thin
    wrappers supplying their own config. No behaviour change (verified by
    the full existing `default-run-helper`/`default-friction-run-helper`
    test suites, unchanged, all still passing).
  - Added `default-friction-run-helper-timeout-branch-test`
    (`context_manager_friction_helper_runtime_test.clj`, new file),
    mirroring `default-run-helper-timeout-branch-test`: drives the real
    `deref`/`::timeout` branch with an injected small `:wall-clock-ms`,
    asserting the child stays tracked in `friction-helper-session-ids`
    (not the unrelated entity-resolution atom) mid-run, is not closed
    until the orphan settles, and is closed+untracked afterward.
  - Verification: `clojure -M:test --focus extensions` → 334 tests, 1307
    assertions, 0 failures (up from 333/1300), stable across 7 repeated
    unseeded runs (the flaky-test repro no longer reproduces). `clj-kondo
    --lint` clean on all changed src/test files. `clj-paren-repair` clean.
    Full `bb test`: same pre-existing unrelated failure set as before
    (57 files under `.scry-results`, none in `extensions.context-manager*`).

## Implementation review, round 2 (task-implementation-review skill)

- Added 2 follow-up steps to steps.md: unsanitized model-supplied `slug`
  reaches filesystem-path construction unvalidated (confirmed via direct
  repro), and missing direct unit tests for the real `default-fetch-history`/
  `default-session-info` collaborators.

## Follow-up execution (post-review pass, round 2)

- addressed 2 review steps:
  - `parse-friction-block` (`friction.clj`) now requires `slug` to match a
    plain kebab-case token (`#"^[a-z0-9]+(-[a-z0-9]+)*$"`) before accepting
    an ISSUE block; a slug containing `/`, `..`, uppercase, or underscores
    is dropped as malformed (same fail-safe pattern as the existing
    missing-field checks), so path-traversal-shaped model output never
    reaches `create-friction-task!`'s `io/file` construction. Added
    `parse-friction-output-slug-sanitization-test`
    (`context_manager_friction_parsing_test.clj`) covering
    path-traversal/slash/non-kebab-case rejection and the plain-kebab-case
    accept case.
  - Added `context_manager_friction_collaborators_test.clj`: direct unit
    tests for `default-fetch-history`/`default-session-info`
    (`#'context-manager/...`) and `friction/message-snippet`/
    `friction/session-info-of`, driven against realistic EQL query-session
    result shapes (raw `{:role :content [{:type :text :text ..}]}` message
    maps; `:psi.agent-session/worktree-path`/`:psi.agent-session/
    session-name` result maps) — mirrors the existing
    `default-select-model`/`default-run-helper` direct-test pattern.
  - Verification: `clojure -M:test --focus extensions` → 339 tests, 1325
    assertions, 0 failures (up from 334/1307). `clj-kondo --lint` clean on
    all context-manager src/test files. `clj-paren-repair` clean. Full
    `bb test`: same pre-existing unrelated failure set as before (58 files
    under `.scry-results`, none in `extensions.context-manager*`).
- steps.md: both round-2 follow-up items checked off; no items remain
  unchecked in that section.

## Implementation review, round 3 (task-implementation-review skill)

- Added 2 follow-up steps to steps.md: `known-helper-session-names`'s fixed
  literal set doesn't match real workflow-step child session names
  (`"workflow <step-id> attempt"`), so "other workflow helper sessions"
  aren't actually excluded per AC5; and `friction-history-turn-count`
  bounds raw messages, not conversational turns, undercounting AC1's
  "last 4 turns" for tool-heavy turns.

## Follow-up execution (post-review pass, round 3)

- addressed 2 review steps:
  - Added `friction/workflow-step-session?` (`friction.clj`) matching the
    workflow runtime's dynamic `"workflow <step-id> attempt"` child-session
    naming convention (`str/starts-with?`/`str/ends-with?`), and wired it
    into `known-helper-session?` alongside the existing fixed-literal
    check. Added `other-known-workflow-step-session-excluded-test`
    (`context_manager_friction_analysis_test.clj`) using a realistic
    `"workflow builder attempt"` session-name. Updated `doc/extensions.md`
    to name workflow-step-attempt sessions as an excluded example.
  - Added `friction/group-into-turns`/`friction/last-n-turns` (pure,
    grouping raw messages on `:role :user` boundaries) and switched
    `default-fetch-history` to bound its query result to
    `friction-history-turn-count` *turns* via `last-n-turns` before
    rendering, instead of `take-last`-ing raw messages via
    `render-history-excerpt`'s `turn-count` arg (now called with `nil`,
    since the message list is already turn-bounded). Updated
    `friction-history-turn-count`'s docstring to state it counts turns, not
    messages. Added `group-into-turns-test`/`last-n-turns-test`
    (`context_manager_friction_collaborators_test.clj`) and a
    `default-fetch-history` tool-heavy-turn test demonstrating a
    multi-message turn is no longer undercounted.
  - Kept `extensions/context_manager.clj` at the 800-line file-length
    ratchet (moved the workflow-step-name predicate into `friction.clj`
    and trimmed docstrings) rather than raising the ratchet.
  - Verification: `clojure -M:test --focus extensions` → 342 tests, 1336
    assertions, 0 failures (up from 339/1325). `clj-kondo --lint` clean on
    all changed src/test files. `clj-paren-repair` clean. Full `bb test`:
    same pre-existing unrelated failure set as before (57 files under
    `.scry-results`, none in `extensions.context-manager*`).
- steps.md: both round-3 follow-up items checked off; no items remain
  unchecked in that section.

- addressed 2 review steps

## Implementation review, round 4 (task-implementation-review skill)

- Added 2 follow-up steps to steps.md: `default-fetch-history` rescans the
  entire unbounded session message history every turn (O(n) per turn, O(n²)
  over a session's life) instead of a bounded tail; and no per-session
  in-flight guard exists, so two overlapping runs on the same session can
  both pass their own dedup check and create duplicate tasks for the same
  issue.

## Follow-up execution (post-review pass, round 4)

- addressed 2 review steps:
  - Added `friction/bounded-message-tail` (pure, `subvec`-based, O(1)) and a
    new `friction/friction-history-raw-message-cap` (200) constant;
    `default-fetch-history` now bounds the raw EQL-queried message vector to
    this tail *before* `friction/group-into-turns`/`friction/last-n-turns`,
    so per-turn grouping work is O(bounded-tail) instead of
    O(total-session-messages) — mirrors `build-augmentation-history-
    projection`'s pre-turn `take-last 8` precedent. Added
    `bounded-message-tail-test` (pure fn, at/under/over-cap and nil/
    non-positive-cap cases) and
    `default-fetch-history-bounds-a-long-session-history-test` (500 old
    turns + 4 recent turns still renders exactly the last 4)
    (`context_manager_friction_collaborators_test.clj`).
  - Added a `friction-in-flight-session-ids` defonce atom (alongside the
    existing `friction-helper-session-ids`/`entity-resolution-helper-
    session-ids` guards) and wrapped `friction-analysis`'s body in a
    swap-in/`try`/`finally`-swap-out claim on `session-id`; a new run for a
    session already claimed returns `{:status :no-op :diagnostic "analysis
    already in flight for this session"}` immediately instead of racing the
    in-flight run's own dedup snapshot. Extracted the previously-inline
    cond/orchestration body into `friction/run-analysis` (a fully
    collaborator-parameterized pure-orchestration fn in
    `extensions.context-manager.friction`) to keep `context_manager.clj`
    under the file-length ratchet (800) after the new guard code; no
    behaviour change to the orchestration logic itself, only relocation +
    explicit collaborator-map parameterization instead of closing over ns
    privates. Added `concurrent-run-same-session-guarded-test` (blocks a
    first run inside its injected `:run-helper`, confirms a second run for
    the same session-id is skipped while the first is still in flight, then
    confirms the first still completes normally once unblocked) and
    `sequential-runs-same-session-not-blocked-test` (guard doesn't leak
    across separate, non-overlapping runs)
    (`context_manager_friction_analysis_test.clj`); both atoms reset in that
    file's `use-fixtures`.
  - Verification: `clojure -M:test --focus extensions` → 346 tests, 1350
    assertions, 0 failures (up from 342/1336), stable across 5 repeated runs
    of the friction-analysis test namespace. `clj-kondo --lint` clean on all
    changed src/test files. `clj-paren-repair` clean. Full `bb test`: same
    pre-existing unrelated failure set as before (57 files under
    `.scry-results`, none in `extensions.context-manager*`).
- steps.md: both round-4 follow-up items checked off; no items remain
  unchecked in that section.

## Implementation review, round 7 (task-implementation-review skill)

- Added 2 follow-up steps to steps.md: `group-into-turns`'s turn-boundary
  check compares `:role` against the keyword `:user`, but real agent-core
  messages persist `:role` as the string `"user"`/`"assistant"` — confirmed
  by direct repro (20 real turns collapse into 1 group) — silently undoing
  the round-3 fix's "last 4 turns" intent in production even though its own
  tests (which use keyword-role fixtures) pass; and `message-snippet` drops
  `:type :error` content blocks, hiding the "tool errors" friction signal
  design.md itself names as a primary example.

## Follow-up execution (post-review pass, round 7)

- addressed 2 review steps:
  - `friction/group-into-turns`'s turn-boundary check now goes through a
    new `friction/user-turn-boundary?` predicate comparing `(name (:role
    message))` against `"user"` (via `some->`, nil-safe), so it matches the
    real string-role shape (`"user"`/`"assistant"`) as well as a `:user`
    keyword. Updated `context_manager_friction_collaborators_test.clj`'s
    `group-into-turns`/`last-n-turns`/`default-fetch-history` fixtures from
    keyword to string roles (matching the real 237/agent-core shape used
    elsewhere in the test suite), added a direct round-7 repro test (40
    string-role messages → 20 turns, not 1), and kept one dedicated test
    confirming the legacy `:user` keyword shape still works for backward
    compatibility.
  - `friction/message-snippet` now also extracts `{:type :error :text ..}`
    blocks alongside `{:type :text :text ..}` (both share a `:text` key),
    without adding a dependency on `psi.agent-session.message-text` — kept
    the deliberate slice-4 classpath-isolation choice (`psi.agent-session`
    is a test-only dep of `context-manager`'s `deps.edn`, not a `src` dep)
    since a plain type filter is sufficient; added
    `message-snippet-test` cases for a text+error mix and an error-only
    message.
  - Verification: `clojure -M:test --focus extensions` (via `bb test
    --focus extensions`) → 347 tests, 1454 assertions, 0 failures (up from
    346/1350). `clj-kondo --lint` clean on both changed files.
    `clj-paren-repair` clean. Full `bb test`: same pre-existing unrelated
    failure set as before (58 `.scry-results` files, none under
    `extensions.context-manager*`; the one context-manager-adjacent name,
    `workflow-child-session-context-test`, is agent-session's, unrelated).
- steps.md: both round-7 follow-up items checked off; no items remain
  unchecked in that section.

## Test review (task-test-review skill)

- added 2 steps to steps.md: missing test coverage for the friction
  helper's no-tools child-session grant, and a misleadingly-labeled
  retry-exhaustion test that actually asserts success.

## Implementation review, round 9 (task-implementation-review skill)

- added 2 steps to steps.md: `known-helper-session-names` omits the
  `auto-session-name` extension's `"auto-session-name"` helper child
  sessions (which run an agent loop and fire `session_turn_finished`), an
  uncaught AC5 exclusion gap of the same class as the round-3 workflow-step
  fix; and `message-snippet` drops the `:is-error` flag of `"toolResult"`
  messages (a distinct role/shape from round-7's assistant `:type :error`
  blocks), so the excerpt can't mark a failed tool result as an error —
  the primary friction signal design.md names.

## Round-9 follow-up (independent execution)

- addressed 2 round-9 review steps:
  - added `"auto-session-name"` to `known-helper-session-names`
    (`extensions/context_manager.clj`) closing the AC5 exclusion gap for
    the auto-session-name extension's helper child sessions; documented the
    literal-set drift risk + round-9 audit (only auto-session-name, besides
    the two augmenters, creates a fixed-name turn-completing helper session)
    in the def docstring. Added
    `other-known-auto-session-name-session-excluded-test`
    (mirrors `other-known-workflow-step-session-excluded-test`).
  - surfaced the out-of-band `:is-error` tool-result signal: threaded
    `:is-error` from raw messages through `default-fetch-history`'s tail
    entries and prefixed an `[error]` marker in `history-line`
    (`extensions/context_manager.clj`) so a failed `"toolResult"` message is
    distinguishable from a successful one in the helper excerpt. Added two
    `default-fetch-history-test` cases (`:is-error true` → `[error]` prefix;
    successful tool result → no marker). Also updated
    `doc/extensions.md`'s exclusion list to name `"auto-session-name"`.
  - lint clean; friction-analysis (15 tests), collaborators (8 tests), and
    context-manager (33 tests) suites all pass.
- older unchecked items (task-test-review skill section, commit `9bf3b9142`)
  predate round 9 — left for their owning workflow/human decision.

## Implementation review, round 10 (task-implementation-review skill)

- added 1 step to steps.md: `context_manager.clj` is 828 lines, over the
  800-line `file-length-legacy-max-lines` ratchet with no exception entry —
  the round-9 additions pushed it over and `bb commit-check:file-lengths`
  now fails on it (tests pass, kondo clean otherwise).

## Implementation review, round 11 (task-implementation-review skill)

- added 1 step to steps.md: the uncommitted working-tree round-10
  file-length fix is broken — `history-line`/`tail-lines-within`/
  `slash-command-only?` moved to `friction.clj` but their three call sites
  in `context_manager.clj` still use unqualified names (3 kondo unresolved-
  symbol errors; ns won't compile). Finishing the qualify-the-call-sites
  edit both fixes the compile break and drops the file under the 800-line
  ratchet (~786 lines), resolving the round-10 item too. Non-compliance
  note: the working tree currently holds a partial/broken (non-compiling)
  change that must not be committed as-is. HEAD itself is clean (tests pass,
  kondo clean) except the already-flagged round-10 file-length ratchet.

## Round-11 follow-up addressed (ψ)

- Completed the interrupted round-10 file-length fix: re-pointed the three
  remaining call sites in `context_manager.clj` at the `friction/` alias —
  `friction/history-line`, `friction/tail-lines-within` (render-history-excerpt)
  and `friction/slash-command-only?` (entity-resolution-augmentation).
- kondo clean (0 errors), ns compiles/loads (all 12 context-manager test
  namespaces pass), `bb commit-check:file-lengths` reports no violation
  (file at 786 lines, under the 800 ratchet). Resolves round-11 and, in the
  same edit, round-10.

## Implementation review, round 12 (task-implementation-review skill)

- No new actionable issues found. Verified rounds 9–11 fixes in live code
  (auto-session-name/workflow exclusions, toolResult `[error]` marker
  threading through `default-fetch-history`/`history-line`, slug
  sanitization, bounded history tail, atomic in-flight claim, file-length
  fix at 786 lines). `clojure -M:test --focus extensions` → 348 tests,
  1458 assertions, 0 failures; `clj-kondo --lint` clean;
  `bb commit-check:file-lengths` clean.
- Non-compliance note: the two `Follow-up (task-test-review skill)` items
  in steps.md (no-tools `:tool-ids []` grant assertion for
  `default-friction-run-helper`; mislabeled "retry exhaustion" testing
  block in `context_manager_friction_task_files_test.clj`) remain
  unchecked and unaddressed — still valid, not new. No steps added (would
  duplicate existing unchecked items).

## Follow-up execution (post-round-12 review pass)

- No actionable items to execute. The immediately preceding review pass —
  implementation review round 12 (commit `b607947e3`) — added zero
  follow-up items to steps.md. The only unchecked items (the two
  `Follow-up (task-test-review skill)` steps) were added by the earlier
  task-test-review pass (commit `9bf3b9142`), which predates rounds 9–12;
  they are left for their owning workflow / human decision per the
  execute-only-preceding-review-pass constraint.
- addressed 0 review steps

## Follow-up execution (post-round-13 review pass)

- No actionable items to execute. Immediately preceding review pass —
  implementation review round 13 (commit `ce16113ec`) — added zero
  follow-up items and explicitly noted the 2 test-review follow-ups remain
  open. The only unchecked steps (the two `Follow-up (task-test-review
  skill)` items) predate rounds 9–13 and belong to the task-test-review
  workflow, so they are left for their owning workflow / human decision per
  the execute-only-preceding-review-pass constraint.
- addressed 0 review steps

## Round-14 follow-up (empty-history short-circuit)

- addressed 1 review step (round 14). Added a `str/blank?` short-circuit on
  the fetched `history-excerpt` in `friction/run-analysis`
  (`extensions/context_manager/friction.clj`): the `:else` branch now derefs
  `select-model` and `fetch-history`, then a `cond` returns
  `{:status :no-op :diagnostic "no history"}` (with a diagnostic log) before
  `run-helper` is reached — mirroring the existing no-worktree/no-model
  no-op branches. Prevents a wasted bounded local-model helper session (and
  the spurious-task hallucination risk) on any turn with empty/blank
  analyzable history (e.g. a session's first turn). Added
  `blank-history-no-op-test` (nil/""/"   " cases) asserting no-op + "no
  history" diagnostic + no `run-helper`/`create-task!` call. Updated the
  `doc/extensions.md` no-op bullet for the new user-observable skip.
  Focused suite green (16 tests). The two `Follow-up (task-test-review
  skill)` items remain unchecked — they predate round 14 and belong to
  their owning workflow / human decision.

## Test review, round 2 (task-test-review skill)

- added 2 steps to steps.md (Follow-up (task-test-review skill, round 2)):
  friction helper-runtime happy-path/session-name coverage gap;
  auto-generated-marker (AC2) assertion gap in render + created-file tests.
- Non-compliance note: the two prior `Follow-up (task-test-review skill)`
  items (friction helper `:tool-ids []` grant assertion; mislabeled "retry
  exhaustion" testing block) remain unchecked/unaddressed — still valid,
  not re-added.

## Test review (test-shaper skill)

- added 2 steps to steps.md: untested `create-task!` failure/nil-return
  partial-creation path and untested `list-tasks` throwing/nil-return
  dedup-degradation path in `friction/run-analysis` (both success-branch
  collaborator-degradation contracts the all-throwing test misses by
  short-circuiting at `session-info`).

## Follow-up execution (test-shaper skill)

- addressed 2 test-shaper review steps: added
  `create-task-partial-failure-still-completes-test` (partial create-task!
  nil/throw → :success, only successful id in :created-task-ids) and
  `list-tasks-degradation-still-detects-test` (throwing/nil list-tasks →
  run-helper still reached, task still created) to
  `context_manager_friction_analysis_test.clj`. Both green; 18 tests,
  152 assertions, 0 failures; lint clean.

## Test review (test-shaper skill, round 2)

- added 2 steps to steps.md: unasserted per-run-cap dropped-issues
  diagnostic log in `run-analysis` (`cap-applied-test` asserts only the
  return shape with no `:log` api); missing orchestration-level mixed
  issue+duplicate helper-output test (coexistence pinned only at the pure
  parse layer by `parse-friction-output-mixed-test`).
- Non-compliance note: the two `Follow-up (task-test-review skill)` items
  (friction helper `:tool-ids []` grant assertion; mislabeled "retry
  exhaustion" testing block) remain unchecked/unaddressed — still valid,
  not re-added.

## Test-shaper round-2 follow-up (addressed)

- addressed 2 test-shaper round-2 follow-up steps (immediately preceding
  review pass, commit `49ee0aefd`):
  - `cap-applied-test`: added a `:log` collaborator + assertion that a
    `#"dropped by per-run cap"` line fires when issues > cap, plus a
    sibling negative case asserting no cap line fires when issues <= cap.
  - added `mixed-issue-and-duplicate-test`
    (`context_manager_friction_analysis_test.clj`): one ISSUE block + one
    DUPLICATE line in a single `run-helper` output → `:status :success`,
    `:created-task-ids` = the one created id, `:duplicate-diagnostics` =
    the one duplicate entry, and a dedup log line fires — orchestration
    analog of `parse-friction-output-mixed-test`.
- the one remaining unchecked `Follow-up (task-test-review skill)` item
  (mislabeled "retry exhaustion → nil" testing block) predates this
  review pass and is left for its owning workflow / human decision.
- `bb test --focus extensions.context-manager-friction-analysis-test`:
  19 tests, 160 assertions, 0 failures; clj-kondo clean.

## Test review (test-shaper skill, round 3)

- added 2 steps to steps.md: in-flight-claim release untested on the
  no-op/throw exit paths (`sequential-runs-same-session-not-blocked-test`
  only proves release after a `:success` first run); exclusion no-op
  branch not diagnostic-pinned (five exclusion tests + blank-history
  assert only `:status :no-op`, never the specific `:diagnostic`, unlike
  the no-model/no-worktree tests).
- Non-compliance note: the two `Follow-up (task-test-review skill)` items
  (friction helper `:tool-ids []` grant assertion; mislabeled "retry
  exhaustion" testing block) remain unchecked/unaddressed — still valid,
  not re-added.

## Address test-shaper round-3 follow-ups

- addressed 2 test-shaper round-3 review steps in
  `context_manager_friction_analysis_test.clj`:
  - `in-flight-claim-released-on-non-success-first-run-test`: first run
    no-ops (no worktree) and, separately, throws (`:session-info` throws) →
    second run for same session-id still reaches `:success`, and the
    in-flight set is empty after — pins the `finally` release on the
    no-op/throw exits, not just the `:success` path.
  - `exclusion-no-op-branch-diagnostic-pinned-test`: asserts
    `:diagnostic "known helper/infra session excluded"` across all five
    exclusion sources (own-helper atom, entity-resolution atom, literal
    name, `workflow-step-session?`, auto-session-name), pinning which
    no-op branch fired — not just that some no-op occurred.
- `clojure -M:test --focus extensions.context-manager-friction-analysis-test`:
  21 tests, 177 assertions, 0 failures; clj-kondo clean.
- older unchecked items (top-of-file per-session in-flight-guard note;
  `create-friction-task-test` rename in task-test-review) predate this
  review pass and are left for their owning workflow / human.

## Address test-shaper round-4 follow-up

- addressed 1 test-shaper round-4 review step in
  `context_manager_friction_parsing_test.clj`:
  - `parse-friction-output-multi-block-test`: two well-formed ISSUE
    blocks → ordered two-element `:issues` (pins block accumulation).
  - `parse-friction-output-malformed-then-valid-recovery-test`: a
    malformed leading ISSUE block (missing SUGGESTION) followed by a
    valid one → only the valid issue (pins the drop-then-recover boundary
    that a single-block test cannot reach).
- `clojure -M:test --focus extensions.context-manager-friction-parsing-test`:
  10 tests, 33 assertions, 0 failures; clj-kondo clean.
- older unchecked items (top-of-file per-session in-flight-guard note;
  `create-friction-task-test` mislabeled-"retry exhaustion" rename in
  task-test-review) predate this review pass and are left for their
  owning workflow / human.

- addressed 1 test-shaper round-6 review step in
  `context_manager_friction_analysis_test.clj`:
  - `own-helper-session-name-excluded-test`: drives `:session-info` →
    `{:session-name "friction-analysis"}` (the analyzer's own helper name
    in `known-helper-session-names`, distinct from the id-tracked
    `friction-helper-session-ids` arm) → `:no-op` + `:diagnostic "known
    helper/infra session excluded"`, pinning the name-set backstop that is
    the only guard after a reload empties the id atom.
  - extended `exclusion-no-op-branch-diagnostic-pinned-test` with a
    literal-`"friction-analysis"`-name arm, completing the per-member
    diagnostic-pinned contract alongside the sibling names.
- `bb test --focus extensions.context-manager-friction-analysis-test`:
  23 tests, 187 assertions, 0 failures; clj-kondo clean.
- older unchecked items (top-of-file per-session in-flight-guard note;
  `create-friction-task-test` mislabeled-"retry exhaustion" rename in
  task-test-review) predate this review pass and are left for their
  owning workflow / human.

## Review note (test-shaper skill, round 7)

- added 2 steps: both target untested `recent-closed-tasks` branches —
  git-listed-but-deleted-off-disk `(filter all-ids …)` reconciliation, and
  the git-success-but-empty-history → name-descending fallback (distinct
  from the non-git git-failure fallback the existing test covers). Both
  verified directly against the current code; both are load-bearing for the
  dedup-list correctness (a phantom/empty closed-task list defeats AC2/AC3).

## Review note (test-shaper skill, round 8)

- added 1 step: `friction/task-title`'s design.md-present-but-no-`# `-heading
  fallback branch is untested — `open-tasks-test` covers only heading-present
  and file-missing, not file-present-headingless (the `not-empty` + outer
  `(or … id)` fallback). Load-bearing: `open-tasks`/`recent-closed-tasks`
  scan all task dirs (incl. human/malformed ones) and the title feeds the
  dedup list (AC3).

## Follow-up execution (test-shaper round 8)

- addressed 1 review step: added `open-tasks-test` branch-3 case
  (design.md present but no usable `# ` heading) — body-only, `## `
  sub-heading, and blank-`# ` heading (the `not-empty` guard's reject
  case) all fall back to the directory id, distinct from the file-missing
  branch. Pins `task-title`'s `(or (some->> … not-empty) id)` fallback.
  Lint clean; focused test suite green (5 tests, 29 assertions).

## Round 9 test-shaper follow-ups (addressed 2 review steps)

- `history-line` `:is-error`-blank/dropped arm: kept current behaviour (the
  `[error]` marker rides on a real snippet; a text-less failure is dropped
  whole) — the plausible "surface all errors" reading would inject a
  content-free `[error] Role: ` line, so the current gating is the right
  contract. Pinned it directly with a new `history-line-test`
  (`context_manager_friction_collaborators_test.clj`): `:is-error true` +
  real snippet → `[error]`-prefixed line; blank/nil/whitespace/slash snippet
  → nil (dropped); non-error snippet → no prefix.
- `render-history-excerpt` dead `turn-count` arm removed: both production
  callers passed `turn-count` nil (entity-resolution 1-arity default;
  friction bounds turns upstream via `friction/last-n-turns`). Collapsed the
  3-arity to `[history char-cap]`, dropped the `take-last turn-count` branch,
  corrected the stale docstring, re-pointed the `default-fetch-history`
  caller. Existing entity-resolution + friction excerpt tests cover the
  remaining behaviour. Lint clean; friction-collaborators (9 tests, 45
  assertions), context-manager-test (33/143), rendering (1/2) all green.
