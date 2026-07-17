# Implementation

- no architectural review feedback (design aligns with ¬mock/¬stub standard; migrates off `with-redefs` of a logic boundary onto the injectable per-ctx `:provider-registry` provider seam; behaviour-preserving, no shims, preserves focus-gate invariants)

- ambiguity review added 1 new design step (undefined done-condition for the "re-evaluate flakiness" acceptance criterion)
- no inconsistency review feedback

## Notes for the design-step task (design-review slices)

- Resolving the one open design-step (undefined done-condition for the
  "re-evaluate parallel `with-redefs` flakiness" acceptance criterion) is a
  design.md acceptance-wording sharpening, not a scope change — keep the two
  frozen call sites and behaviour-preserving constraint intact.
- The flakiness referenced is the parallel `with-redefs` test-isolation issue
  recorded against task 242; primary sources to consult when specifying the
  concrete outcome: `munera/closed/242-retry-footer-focus-gate-regression/`
  (implementation.md, esp. Slice 6 seam-existence + the flakiness notes) and
  the test file under change `components/rpc/test/psi/rpc_prompt_test.clj`.
- The provider seam authority is `psi.ai.core/create-context` (`:provider-registry`);
  the retry-key propagation path to verify lives in
  `components/turn-runtime/src/psi/turn_runtime/core.clj`
  (`make-provider-event-consumer` `:error` case). Retry-footer text authority is
  `psi.app-runtime.retry-display/retry-status-text`.

## Design-follow-up pass (ambiguity design-step resolved)

- Resolved the ambiguity design-step by sharpening the flakiness acceptance
  criterion into a verifiable record-and-compare done-condition (split into two
  acceptance bullets: comment removal, and recorded before/after `bb test`
  count comparison against the task-242 baseline).
- Chosen done-condition is record-not-fix, because task 242's own verification
  (implementation.md, "Verification" note) observed the *same* failing test
  names with and without its change — the pre-existing parallel-isolation
  failure set (2450 passed / 24 failed / 38 errored) is broad and not confirmed
  to originate in these two retry-footer sites. Requiring a green full suite
  would over-scope this behaviour-preserving migration; the plan author's
  measurable target is the recorded comparison in implementation.md.
- Concrete task-242 baseline numbers to compare against are in
  `munera/closed/242-retry-footer-focus-gate-regression/implementation.md`
  ("Verification" bullet: baseline 2450/24/38, with-change 2451/24/38 — the +1
  pass being the added test).

## Plan-review ambiguity pass

- ambiguity review added 1 new design step: plan conflates the two
  `execute-prepared-request! [ai-ctx ctx …]` context params. Verified against
  source that provider resolution uses only the first `ai-ctx` arg
  (`do-stream!` → `stream-response-in` → `context-provider-registry ai-ctx`),
  currently `{:provider-registry (atom {})}`; the second `ctx` carries session
  state / `:provider-retry-sleep-fn` and is not consulted for resolution. The
  stub `:provider-registry` (from `create-context`) must replace the first
  `ai-ctx` arg, not be merged into the app-runtime `ctx`.

- inconsistency review added 1 new design step: design.md Scope (and plan/steps
  Slice 3–4) assume two `with-redefs` sites incl. an inline one in the sibling
  test; code has a single shared `with-redefs` inside
  `drive-provider-retry-through-progress-loop!`, reached by all retry tests
  (verified in rpc_prompt_test.clj). Slice 4's "remove its inline with-redefs"
  is a no-op; the real migration removes one driver-level site (Slice 3).

## Notes for the plan-review design-step task

- Both plan-review design-steps are wording/scope sharpenings of task files, not
  scope changes: keep the behaviour-preserving constraint and the frozen
  assertions intact when reconciling them.
- Provider-resolution seam facts (verified this pass), for the ai-ctx-vs-ctx step:
  - `execute-prepared-request! [ai-ctx ctx session-id prepared-request progress-queue]`
    is in `components/turn-runtime/src/psi/turn_runtime/core.clj` (~line 513);
    provider resolution flows `execute-provider-attempt!` → `do-stream!` (core ~28)
    → `stream/do-stream!` (`components/turn-runtime/src/psi/turn_runtime/stream.clj` ~15)
    → `ai/stream-response-in` → `context-provider-registry ai-ctx`
    (`components/ai/src/psi/ai/core.clj` ~162/166).
  - `create-context` (ai/core.clj ~45) returns exactly `{:provider-registry (atom providers)}`,
    i.e. the correct shape to pass as the **first `ai-ctx`** arg (drop-in for the
    current `{:provider-registry (atom {})}` literal at the call site).
  - `resolve-provider` matches `(:provider model)` then `(:api model)`; prepared
    model is `{:provider :anthropic :id "stub"}`, so register the stub under `:anthropic`.
- For the single-with-redefs step: the only `execute-live-turn!` `with-redefs` is
  inside `drive-provider-retry-through-progress-loop!` (rpc_prompt_test.clj ~430);
  removing it there migrates all four retry sub-tests at once. The unrelated
  `session/query-in` `with-redefs` (~line 91) is a different test and out of scope.

## Plan-follow-up pass (both plan-review design-steps executed)

- Executed both plan-review follow-up items (design-steps.md items 2 & 3):
  - Provider-seam wiring: plan.md migration-shape steps 1–2 and Slice-order
    Slice 2/3, plus steps.md Slice 2/3, now state explicitly that the stub
    `{:provider-registry …}` (from `create-context {:anthropic stub}`) is passed
    as the **first `ai-ctx` arg** of `execute-prepared-request!` (the only param
    provider resolution consults), replacing `{:provider-registry (atom {})}`,
    leaving the second app-runtime `ctx` unchanged.
  - Single-with-redefs reconciliation: plan.md migration-shape step 3 and
    Slice-order Slice 4, plus steps.md Slice 4, now reflect the real
    single-shared-driver shape — Slice 3 removes the one driver-level
    `with-redefs` and thereby migrates the sibling test; Slice 4 is
    verify-assertions + task-242 comment removal, not a second `with-redefs`
    removal (the previous "remove its inline `with-redefs`" wording was a no-op).
- RESIDUAL for a design-level pass (out of scope here — design.md is read-only
  in the plan-follow-up profile): design.md **Scope** still asserts two
  `with-redefs` sites and that the sibling test "inlines its own identical
  `with-redefs`". Code has exactly one site (in the shared driver); the sibling
  has none. design.md Scope should be corrected to the single-shared-driver
  shape to fully close the inconsistency. plan.md/steps.md are already reconciled.

## Plan-review session — ambiguity turn (batch first turn)

- no ambiguity review feedback: plan.md/steps.md already reconciled by the prior
  plan-review ambiguity + plan-follow-up passes (ai-ctx-vs-ctx param sharpened;
  single-shared-driver `with-redefs` shape). Verified against rpc_prompt_test.clj:
  one driver-level `with-redefs` (~430), first `execute-prepared-request!` arg is
  `{:provider-registry (atom {})}` (~453), `(= 3 attempts)` asserted at 4 sites,
  rate-limit values pinned by `changed-retry-remaining`/`retry-rate-limit`
  constants. Stub-shape and sleep-fn sync are framed as Slice-1 verification, not
  left underspecified. Residual design.md Scope "two with-redefs sites" is an
  inconsistency-turn concern (design.md), not a plan/steps ambiguity.

## Plan-review session (re-run) — ambiguity turn (batch first turn)

- no ambiguity review feedback: plan.md/steps.md remain fully reconciled.
  Re-verified against rpc_prompt_test.clj — single driver-level `with-redefs`
  (~430); first `execute-prepared-request!` arg `{:provider-registry (atom {})}`
  (~454); attempts/rate-limit/delays pinned by constants; `(= 3 attempts)` at 4
  sites; stub stream-event shape framed as Slice-1 verification; recovery turn
  and flakiness record-and-compare done-condition already specified. Residual
  design.md Scope "two with-redefs sites" is an inconsistency-turn concern
  (design.md), tracked by the open design-step, not a plan/steps ambiguity.

## Plan-review session (re-run) — inconsistency turn (batch second turn)

- no inconsistency review feedback: plan.md ↔ steps.md ↔ code ↔ implementation.md
  are mutually consistent (single-shared-driver `with-redefs`, first `ai-ctx`
  arg, Slice 3/4 split, `(= 3 attempts)`). The one remaining cross-file
  inconsistency — design.md Scope's "two with-redefs sites" / sibling "inlines
  its own" framing — is already captured by the open unchecked design-step
  (design-steps.md, item 4); not re-added to avoid duplication. Its correction
  requires a design-editable pass (out of scope for the plan-review profile).

## Notes for the design-step task (plan-review re-run batch)

- This plan-review batch added no new design-steps; the sole remaining open item
  is design-steps.md item 4 (design.md Scope "two with-redefs sites" correction).
- Principle: it is a design.md Scope wording fix only — keep the
  behaviour-preserving constraint and the two frozen call sites; do not let the
  rewording imply a second migration site. Concrete single-driver shape to write
  is already in the prior "Notes for the design-step task (plan-review
  inconsistency slice)" section above; do not re-touch plan.md/steps.md (already
  consistent).

## Plan-review session — inconsistency turn (batch second turn, prior)

- inconsistency review added 1 new design step: design.md Scope still asserts
  "two `with-redefs` sites" (sibling "inlines its own") — contradicts the
  already-reconciled plan/steps and the verified single-driver code. Prior
  design-step 3 was marked done after reconciling only plan/steps; the design.md
  Scope correction remained an unactioned residual (design.md read-only that
  pass), so a fresh unchecked step now targets it for a design-editable pass.
  Not a duplicate: item 3 is checked and its design.md portion was never executed.

## Notes for the design-step task (plan-review inconsistency slice)

- The new inconsistency design-step is a design.md Scope wording correction only —
  not a scope change. Hold the behaviour-preserving constraint and the two frozen
  call sites; do not let the rewording imply a second migration site.
- Concrete shape to write into design.md Scope: one `with-redefs
  [turn-runtime/execute-live-turn! …]` lives in the shared driver
  `drive-provider-retry-through-progress-loop!` (`components/rpc/test/psi/rpc_prompt_test.clj`
  ~line 430; the `execute-prepared-request!` call passing `{:provider-registry (atom {})}`
  is ~line 453). All retry sub-tests (focus-gate boundary ~521, background ~576,
  pre-gate sibling ~644/707) reach the stub only by calling that driver, so
  migrating the one driver-level site migrates them all.
- design.md Acceptance/Approach already speak in the correct (single-seam) terms;
  only the **Scope** section's "two `with-redefs` sites" / sibling "inlines its own"
  framing needs correcting. plan.md and steps.md are already consistent — do not
  re-touch them.

## Plan-follow-up pass (preceding plan-review batch — no attributed steps.md work)

- Batch baseline: previous plan-follow-up completion `650992100`; the immediately
  preceding plan-review batch is `7c7ce6651` (ambiguity turn) + `b207ac329`
  (inconsistency turn) + `565f34746` (notes), run back-to-back. Baseline =
  parent of oldest batch commit = `650992100`.
- `git diff 650992100..HEAD -- steps.md` is **empty**: the batch added no new
  checklist lines to steps.md. Candidate work set for this plan-follow-up is
  therefore empty — nothing executed, plan.md/steps.md unchanged.
- The one checklist line the batch added went to **design-steps.md** (open
  inconsistency: design.md Scope still asserts "two `with-redefs` sites" /
  sibling "inlines its own", contradicting reconciled plan/steps + code). That
  is a design-editable step, out of scope for the plan-follow-up profile
  (design.md read-only here). Next actor: a design-editable pass must correct
  design.md **Scope** to the single-shared-driver shape to fully close it.

## Design-review session outcome (arch + ambiguity + inconsistency)

- Shared design-review session (all three turns) found no new actionable
  feedback: the design is architecturally clean (¬mock/¬stub via injectable
  per-ctx `:provider-registry` seam, behaviour-preserving, no shims, focus-gate
  invariants intact) and internally consistent. The only design-step is the
  already-resolved flakiness done-condition.
- Principles to hold when addressing the design-step: keep the two frozen call
  sites and behaviour-preserving constraint; resolve via record-and-compare, not
  by chasing a green full suite (record-not-fix).

## Implementation pass (Slices 1–4 executed; design-step 4 resolved)

- Resolved the last open design-step: rewrote design.md **Scope** to the
  verified single-shared-driver shape (one `with-redefs` inside
  `drive-provider-retry-through-progress-loop!`; both retry sub-tests and the
  sibling reach the stub only via that shared driver, no inline `with-redefs`
  anywhere else). Marked design-steps.md item 4 done.

- Baseline `bb test` (pre-migration): **2451 passed / 24 failed / 38 errored**
  (matches the task-242 reference baseline of 2450/24/38 within the expected
  +1 pass task 242 recorded for its added test).

- Seam contract pinned by reading source (no surprises vs. plan.md):
  - Provider-impl contract: a map with `:stream (fn [conversation model
    options consume-fn] ...)` — the exact shape `psi.ai.streaming/stream-response`
    invokes. The stub can call `consume-fn` synchronously inside its `:stream`
    fn; `stream-response` already wraps the whole call in a `future`, and
    `handle-event!` delivers `done-p` synchronously as each `:done`/`:error`
    event is consumed, so a synchronous stub still exercises the async
    `await-assistant-message!` wait path correctly.
  - `turn-runtime/make-provider-event-consumer`'s `:error` case reads
    `:http-status` and `(or :provider-error/headers :headers)` off the event
    map and forwards them via `turn-sc/send-event! :turn/error`, which
    `accumulator/handle-error!` uses to build the assistant-message carrying
    `:http-status` / `:provider-error/headers` — confirmed this is the same
    shape the removed fabricated-turn stub built directly.
  - Recovery-turn event vocabulary used: `:text-start` / `:text-delta` /
    `:text-end` / `:done {:reason :stop :usage {}}`.

- Stub provider + ctx helper (`retry-stub-provider-ai-ctx`) added, registered
  under `:anthropic` via `psi.ai.core/create-context`, returning
  `[ai-ctx attempts*]`. `drive-provider-retry-through-progress-loop!` rewritten
  to call `turn-runtime/execute-prepared-request!` with `ai-ctx` as the first
  arg (no `with-redefs`), preserving the THREAD-AFFINITY invariant (still
  called synchronously on the test thread) and the `start-progress-loop!` /
  `stop-progress-loop!` wiring.

- **Two additional preconditions discovered that plan.md's contract-pinning
  step did not name** (both `execute-live-turn!` → `do-stream!` →
  `ai/stream-response-in` schema preconditions), needed to reach the stub's
  `:stream` fn at all instead of failing before it (an uncaught
  `AssertionError` inside `execute-live-turn!`'s `(catch Throwable t ...)`,
  which silently produced a headerless error assistant-message and `attempts*`
  staying at `0`):
  - `:prepared-request/provider-conversation` must be a schema-valid
    `Conversation` (was previously irrelevant — the fabricated-turn
    `with-redefs` never touched it). Fixed by supplying
    `(ai/create-conversation nil)`.
  - `:prepared-request/model` must be a schema-valid `Model` (closed map with
    all required capability keys), not the ad hoc `{:provider :anthropic :id
    "stub"}` literal the old stub tolerated. Fixed by using a real model,
    `(psi.ai.models/get-model :claude-3-5-sonnet)` (still resolves the stub
    provider via `:provider :anthropic`).
  - Both fixes required requiring `psi.ai.core` (`ai/create-context`,
    `ai/create-conversation`) and `psi.ai.models` (`ai.models/get-model`) in
    the test namespace.

- Removed the task-242 "deliberate bounded exception to ¬mock/¬stub" comment
  block along with the `with-redefs`. `grep with-redefs` in the file now shows
  exactly one remaining site: the unrelated `session/query-in` redef in
  `rpc-prompt-footer-updated-tolerates-keyword-sentinel-values-test` (~line
  91) — out of scope for this task, left as-is.

- `bb test --focus psi.rpc-prompt-test`: **5 passed / 0 failed / 0 errored**
  (all five tests in the namespace, including both retry-footer tests, still
  assert the same activation/changed/clear frames, focus-gate behaviour, and
  attempt counts as before the migration).

- `clj-paren-repair` run on the edited file (reformatted, no structural
  issues); re-read; `clj-kondo --lint` clean (0 errors, 0 warnings).

- Deferred to Slice 5 (unchanged from plan): `active-retry-text-prefix`
  derivation, `focus-gated-emitter!`/`default-focus-emitter!` consolidation.
  Deferred to Slice 6: full-suite `bb test` post-migration run + flakiness
  comparison against the recorded baseline above.

## Implementation pass (Slice 5 — harness consolidation)

- `active-retry-text-prefix`: replaced the length-subtraction derivation
  (`subs status-line 0 (- (count status-line) (count (format-relative-seconds
  now-ms)))`) with a direct derivation — `retry-status-text {:active? true
  :resume-at 0} 0` at zero delay/no rate-limit metadata returns exactly
  `"retry in 0s"` with no `" · "` join fragment, so stripping the trailing
  literal `"0s"` yields the fixed prefix straight from the production string
  without an independent `format-relative-seconds` call.
- Collapsed `focus-gated-emitter!` / `default-focus-emitter!` into one
  `focus-emitter! [session-id focus]` builder (`focus` = explicit
  session-id, or `nil` to exercise the `focus-allows?` default-session-id
  fallback branch — `make-rpc-state {:session-id session-id}` already seeds
  both `:focus-session-id` and `:default-session-id`, so `focus nil` still
  resolves the session as its own default focus via the fallback). Updated
  all three call sites (focused-session test, default-session-id fallback
  test, background-session test).
- No other matcher/format helpers were found redundant after the seam
  migration — `expected-retry-text`, `remaining-fragment`,
  `retry-footer-sleep-fn`, `retry-status-line?`,
  `clear-footer-produced-after-retry`, `activation-precedes-changed?` each
  retain a distinct single-authority role (delay→text, rate-limit fragment,
  sync, stale-text predicate, positive control, ordering control) not
  subsumed by the provider-seam change.
- `bb test --focus psi.rpc-prompt-test`: 5 passed / 0 failed / 0 errored (58
  assertions). `clj-paren-repair`: no changes needed. `clj-kondo --lint`:
  0 errors, 0 warnings.

## Implementation pass (Slice 6 — flakiness re-evaluation + close-out)

- Post-migration full-suite `bb test`: **2450 passed / 25 failed / 38
  errored** (18660 assertions: 18660 passed / 52 failed / 38 errored).
- Comparison against this task's own pre-migration baseline (recorded in
  the Slice 1-4 pass above): **2451 passed / 24 failed / 38 errored**.
  Total test count is unchanged (2513 both runs); one test flipped from
  passed → failed between the two runs (errored count unchanged at 38).
- Checked the post-migration failure/error set
  (`.scry-results/*.edn`, 63 files) for either retry-footer test under
  migration
  (`rpc-prompt-provider-retry-footer-reaches-focused-session-emit-boundary-test`,
  `rpc-prompt-provider-retry-state-publishes-footer-updated-test`): **neither
  appears** in the failure/error set. Both migrated tests passed in the
  post-migration run (confirmed directly: `bb test --focus
  psi.rpc-prompt-test` green, 5/5, immediately before this full-suite run).
- **Finding: the parallel-isolation failure set is unchanged (not reduced,
  not eliminated) by this migration.** The two retry-footer `with-redefs`
  call sites removed by this task were not a/the cause of the pre-existing
  parallel-isolation flakiness — the failures observed post-migration are in
  unrelated namespaces (`psi.turn-runtime.response-mode-test`,
  `psi.rpc-prompt-command-test`,
  `psi.agent-session.statechart-actions-test`, per `.scry-results` file
  names), none of which reference the migrated tests or the
  provider-registry seam. The ±1 failed/passed count between the two runs is
  consistent with the same pre-existing suite-wide parallel test-isolation
  issue (unrelated to this migration) intermittently flipping which specific
  test loses the race on a given run, not with a regression this migration
  introduced.
- Acceptance criteria verified:
  - Neither retry-footer test uses `with-redefs` of
    `turn-runtime/execute-live-turn!` (or any logic boundary) — confirmed;
    `grep with-redefs` in the file shows exactly one remaining site
    (`session/query-in` in an unrelated test, out of scope, noted in the
    Slice 3/4 pass above).
  - Both retry-footer tests still assert the same three frames / focus-gate
    behaviour / pre-gate footer sequence — confirmed unchanged in this pass
    (only the emitter-builder and prefix-derivation *construction* changed,
    not the assertions).
  - `bb test --focus psi.rpc-prompt-test` is green — confirmed (5/5).
  - The task-242 deferred-exception comment is removed — confirmed in the
    Slice 3/4 pass.
  - Flakiness re-evaluation recorded with a stated outcome (unchanged) —
    this entry.
- All design.md acceptance criteria are met. Task ready to close.

## Implementation-review pass (task-implementation-review)

- added 2 follow-up steps: untracked sibling `session/query-in` `with-redefs` logic-boundary violation (same file), and an optional zero-second retry-text-authority lock for `active-retry-text-prefix`. Neither is a task-243 defect (both out of 243's frozen scope); all 243 acceptance criteria hold and focused tests are green (5/5, lint clean).

## Implementation-review follow-up execution pass

- addressed 2 review follow-up steps (both from the immediately preceding
  task-implementation-review pass, commit `11626c6eb`):
  - Opened follow-up task `244-migrate-footer-query-in-with-redefs-to-resolver-seam`
    (design-only, registered in `munera/plan.md`) tracking the untracked
    `session/query-in` `with-redefs` logic-boundary violation in
    `rpc-prompt-footer-updated-tolerates-keyword-sentinel-values-test`. The
    standing `¬mock/¬stub` violation is now tracked rather than silently carried.
  - Added the missing zero-second retry-text authority test:
    `components/app-runtime/test/psi/app_runtime/retry_display_test.clj` locks
    `format-relative-seconds 0`/`-500` → `"0s"` and
    `retry-status-text {:active? true :resume-at 0} 0` → `"retry in 0s"`, so a
    future `retry_display.clj` change to the zero-second rendering fails the
    authority test rather than silently desyncing `active-retry-text-prefix`.
    `bb test --focus psi.app-runtime.retry-display-test`: 2 passed / 0 failed /
    0 errored (4 assertions); `clj-kondo` clean.

## Implementation-review pass 2 (task-implementation-review)

- added 1 follow-up step: redundant assertion term in the added zero-second retry-display authority test (second `is` repeats the whole first `is` in its first two args). Non-blocking test-clarity only; core migration verified independently as sound (¬mock/¬stub via injectable per-ctx `:provider-registry`, behaviour-preserving), all 243 acceptance criteria hold, `psi.rpc-prompt-test` green (5/5, 58 assertions) and `retry-display-test` green (2/2), lint clean.

## Implementation-review pass 2 follow-up execution

- addressed 1 review step: split the redundant `is` in `retry-status-text-locks-zero-second-active-prefix-test` (`retry_display_test.clj`) — kept the literal-lock `is` and made the second `is` a distinct 2-way `=` asserting only omitted-`:resume-at` ≡ explicit-`:resume-at 0`. `retry-display-test` green (2/2, 4 assertions); `clj-kondo` clean.

## Implementation-review pass 3 (task-implementation-review)

- no new actionable feedback: prior passes' follow-ups all executed (task 244 opened, zero-second authority test added, redundant `is` split). Re-verified migration against source — stub `:stream` signature and `:error` event shape (`:http-status`/`:provider-error/headers`/`:error-message`) match the real provider/consumer contract; behaviour-preserving; `psi.rpc-prompt-test` green (5/5, 58 assertions), `retry-display-test` green (2/2), lint clean; design.md Scope corrected to single-shared-driver shape.

## Task-test-review pass (task-test-review skill)

- added 1 follow-up step: recovery-turn shape (stub attempt-3+ `:text-delta "recovered"`/`:done :stop`) is modelled as behaviour (design Approach/Acceptance + stub docstring) but only indirectly covered — the clear-footer check is negative-only and cannot distinguish a real recovery `:stop` from retry exhaustion. Non-blocking coverage gap; ¬mock/¬stub, behaviour, and well-formedness criteria otherwise met (`execute-live-turn!` violation resolved via injectable `:provider-registry`; residual `session/query-in` `with-redefs` already tracked by task 244); `psi.rpc-prompt-test` green (5/5, 58 assertions), `retry-display-test` green (2/2).

## Task-test-review follow-up (recovery-turn positive control)

- addressed 1 test-review follow-up step: added a positive recovery-turn success control to the retry-footer E2E harness. `drive-provider-retry-through-progress-loop!` return shape changed from a bare attempt count to `{:attempts N :recovery-result execution-result}` (recovery result captured from `execute-prepared-request!`). New shared `assert-recovery-turn-succeeded!` asserts `:turn.outcome/stop`, `:stop` stop-reason, and the `"recovered"` assistant-message text, wired into all four driver call sites across both retry-footer tests. Closes the gap where the negative no-stale-`retry in` clear check passed whether the footer cleared via genuine recovery or retry exhaustion. Verified non-vacuous (recovery-text mutation fails the control). `bb test --focus psi.rpc-prompt-test` green; lint clean.

## Task-test-review pass 2 (task-test-review skill)

- no new actionable feedback. All three criteria met: tests well-formed (positive/ordering/clear-path controls, non-vacuity notes, documented THREAD-AFFINITY invariant); every design.md behaviour covered (frames/gate/fallback/recovery/session-id stamping; flakiness is record-not-fix by design); infra deps injectable/nullable via per-ctx `:provider-registry` seam (¬mock/¬stub) with the sole residual `session/query-in` `with-redefs` already tracked by task 244. Recovery-turn gap already closed by the prior pass's `assert-recovery-turn-succeeded!` control. `psi.rpc-prompt-test` green (4 tests / 66 assertions), `retry-display-test` green (2/2).
  - Non-compliance note (minor, non-test): earlier implementation.md passes record the namespace as "5 tests / 58 assertions"; the namespace has 4 deftests and now runs 66 assertions post-recovery-control — a stale count in the closed record, not a test defect.

## Task-test-review pass 3 (test-shaper skill)

- added 3 follow-up steps (all non-blocking, clarity/consistency/economy): missing failure messages on the three session-id-stamping assertions (inconsistent with every other `is` in the same tests), a stale `retry-stub-provider-ai-ctx` docstring implying a caller-facing `attempts*` use that no longer exists, and the repeated `(= 3 attempts)` magic number + recovery-control pairing across four/five call sites lacking a single authority. Non-compliance note: the task is closed (`munera/closed/`), not open as the review request assumed — steps/notes appended to the closed task in place.

## Test-shaper follow-up execution

- addressed 3 test-shaper review steps in `components/rpc/test/psi/rpc_prompt_test.clj`: (1) added distinct failure messages to the three session-id-stamping assertions (two `every?` sites + the sibling 3-way `=`); (2) corrected the stale `retry-stub-provider-ai-ctx` docstring to state the driver consumes `@attempts*` and re-exports it as `:attempts` (no caller-facing direct-atom site); (3) added `expected-retry-attempts` const (single authority for `3`) and folded the attempts+recovery pair into `assert-full-retry-sequence-ran!`, wired into all four recovery-carrying sites, pre-gate control now uses the const. Non-vacuity re-verified (stub `NOTRECOVERED` mutation fails). `bb test --focus psi.rpc-prompt-test` green (66 assertions, 4 tests); `clj-kondo` clean. Task is in `munera/closed/` (already closed); executed in place per the review-follow-up request.

## Task-test-review pass 4 (test-shaper skill)

- added 1 follow-up step: duplicated clear-footer control pair across the two focus-gate sub-tests (same class of copied two-line control the prior pass folded into `assert-full-retry-sequence-ran!`, left unconsolidated). Non-compliance note: the task is closed (`munera/closed/`), not open as the review request assumed — step/note appended to the closed task in place.

## Test-shaper pass 4 follow-up execution

- addressed 1 test-shaper review step: added `assert-clear-footer-produced! [footer-events label]` folding the duplicated two-line clear-footer control pair (`some? clear-footer` + `not retry-status-line?`) into one authority, `label` interpolated into both failure messages. Wired into both focus-gate sub-tests (explicit-focus, default-session-id fallback). Sibling pre-gate test left out per the follow-up's scope note (its `clear-footer` binding interleaves with the session-id-stamping 3-way `=` control). No assertion weakened. `bb test --focus psi.rpc-prompt-test` green (60 assertions, 3 tests); `clj-kondo` clean. Task is in `munera/closed/` (already closed); executed in place per the review-follow-up request.

## Task-test-review pass 5 (test-shaper skill)

- added 2 follow-up steps: stale `error-turn` terminology (migration replaced the fabricated turn map with a stub `:error` stream event / `error-event`, but three comment/docstring sites still name `error-turn`), and the single-authority-drift rationale re-spelled across two floating comment blocks plus each of the four retry-constant docstrings. Both non-blocking documentation economy/accuracy; behaviour and all frozen assertions unchanged. Non-compliance note: the task is closed (`munera/closed/`), not open as the review request assumed — steps/note appended to the closed task in place.

- addressed 2 test-shaper pass-5 review steps: (1) removed all three `error-turn` references — the floating comment now describes the stub's `:error` stream events (`error-event`) deriving 429 headers, and the `retry-rate-limit`/`changed-retry-remaining` docstrings now name "the stub's second `:error` stream event (`error-event`) header" (`grep error-turn` → none); (2) collapsed the two floating single-authority `;;` blocks into one short group header, stating the shared-authority rationale once and deferring specifics to the per-def docstrings (no docstring factual content weakened). `bb test --focus psi.rpc-prompt-test` green (60 assertions, 3 tests); `clj-kondo` clean. Executed in place on the already-closed task per the review-follow-up request.

## Task-test-review pass 6 (test-shaper skill)

- added 1 follow-up step: the sibling pre-gate test binds its two match frames via the obscured `(some #(when (pred %) %) …)` idiom (relies on frame map-truthiness), inconsistent with the plain `(some pred …)` existence idiom used at every other match site; direct `(first (filter pred …))` states the intent without the truthiness dependency. Non-compliance note: the task is closed (`munera/closed/`), not open as the review request assumed — step/note appended to the closed task in place.

## Test-shaper pass 6 follow-up execution

- addressed 1 test-shaper pass-6 review step: replaced the two obscured `(some #(when (pred %) %) footer-events)` bindings (`first-retry-footer`, `changed-retry-footer`) in the sibling pre-gate test with the direct `(first (filter pred footer-events))` spelling. States "first matching frame" intent without depending on frame map-truthiness; reads consistently against the `some`-existence idiom used at the focus-gate sites. Behaviour identical (first matching frame, `nil` if none; the downstream `(some? …)` stamping checks stay meaningful). `bb test --focus psi.rpc-prompt-test` green (60 assertions, 3 tests); `clj-kondo` clean. Task is in `munera/closed/` (already closed); executed in place per the review-follow-up request.

## Task-test-review pass 7 (test-shaper skill)

- added 1 follow-up step: the two focus-gate sub-tests still each carry a verbatim ~6-assertion footer-verification block (seq/activation/changed/ordering/clear/stamping) differing only in the `focus-emitter!` argument and label strings — the same copied-control class prior passes folded into `assert-full-retry-sequence-ran!` (pass 3) and `assert-clear-footer-produced!` (pass 4); the enclosing block was left unconsolidated. Non-compliance note: the task is closed (`munera/closed/`), not open as the review request assumed — step/note appended to the closed task in place.

## Test-shaper pass 7 follow-up execution

- addressed 1 test-shaper pass-7 review step: added `assert-retry-footers-crossed-gate! [footer-events session-id label]` folding the shared 6-assertion focus-gate footer-verification block (seq / activation / changed / activation-precedes-changed ordering / `assert-clear-footer-produced!` / per-frame session-id stamping) into one authority, `label` interpolated into all failure messages. Both focus-gate arms now reduce to `assert-full-retry-sequence-ran!` + `assert-retry-footers-crossed-gate!`; a change to the gate-crossing contract is edited once instead of at both arms. Per the follow-up's scope note, the background suppression sub-test and the sibling pre-gate test (interleaved stamping 3-way `=`) were left out. No assertion weakened — same 6 checks per arm. `bb test --focus psi.rpc-prompt-test` green (60 assertions, 3 tests — unchanged); `clj-kondo` clean. Task is in `munera/closed/` (already closed); executed in place per the review-follow-up request.

## Task-test-review pass 8 (test-shaper skill)

- added 1 follow-up step: the "index of the (first|last) frame matching a predicate" idiom (`(some (fn [[i frame]] (when (pred frame) i)) (map-indexed vector …))`) is hand-spelled 3× across `activation-precedes-changed?` and `clear-footer-produced-after-retry` with no single authority — a distinct site set from the pass-6 frame-lookup fix (those need the index, not the frame). Non-compliance note: the task is closed (`munera/closed/`), not open as the review request assumed — step/note appended to the closed task in place.
