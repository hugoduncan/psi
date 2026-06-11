# Steps — workflow cancellation halts in-flight execution

## Slice 1 — Dispatch event skeleton and public routing

- [x] Add/register agent-session dispatch handlers for `:psi.workflow/cancel-run` and `:psi.workflow/remove-run`.
- [x] Define the shared event-data parsing/defaulting for `{:run-id string :reason string? :session-id string?}`.
- [x] Update Pathom `'psi.workflow/cancel-run` to dispatch `:psi.workflow/cancel-run` instead of directly calling `workflow-runtime/cancel-run` / resetting `:state*`.
- [x] Update Pathom `'psi.workflow/remove-run` to dispatch `:psi.workflow/remove-run` instead of directly calling `workflow-runtime/remove-run` / resetting `:state*`.
- [x] Update `psi-tool workflow op=cancel-run` to route through the canonical cancel event or Pathom adapter with no direct workflow-runtime mutation.
- [x] Update `delegate remove` to use the canonical remove path and remove its command-layer `inflight-runs` `swap!`.
- [x] Add/adjust tests proving public cancel/remove surfaces route through the dispatch handlers and do not mutate `:state*` or `inflight-runs` inline.

## Slice 2 — Runtime cancellation/cleanup effects and ctx reachability

- [x] Inject the production `workflow/runtime_state/inflight-runs` handle onto the agent-session dispatch ctx (for example `:workflow-inflight-runs-handle`).
- [x] Extend the agent-session effect schema with `:runtime/cancel-inflight-run` requiring `:run-id`.
- [x] Extend the agent-session effect schema with `:runtime/drop-inflight-run` requiring `:run-id`.
- [x] Extend the `:runtime/agent-abort` schema to support two variants: unguarded optional `:session-id`, and guarded workflow-cancellation flat keys requiring `:session-id`, `:workflow-run-id`, `:workflow-step-id`, `:workflow-attempt-id`, and `:expected-session-id`.
- [x] Implement `execute-effect! :runtime/cancel-inflight-run` to look up exactly `(:run-id effect)` in the ctx-injected handle and `future-cancel` the stored future when present.
- [x] Implement `execute-effect! :runtime/drop-inflight-run` to dissoc exactly `(:run-id effect)` from the ctx-injected handle.
- [x] Add the guarded branch to `execute-effect! :runtime/agent-abort`: re-read canonical workflow run/step/latest-attempt state and only perform existing abort side effects when the flat guard still matches a live attempt.
- [x] Preserve existing unguarded `:runtime/agent-abort` behaviour, including effects whose `:session-id` is supplied by the effects interceptor.
- [x] Add effect-schema and executor unit tests for exact payload shapes, ctx-handle use, exact-key no-op behaviour, and guarded/un guarded abort compatibility.

## Slice 3 — Shared cancel/remove transition semantics

- [x] Add a helper to identify terminal workflow-run statuses and live statuses (`:pending`, `:running`, `:blocked`).
- [x] Add a helper to determine whether a run is top-level (no `:delegating-run-id`) vs nested.
- [x] Add a helper to enumerate non-terminal descendant runs transitively by `:delegating-run-id` from a directly cancelled run.
- [x] Add a helper to locate a run's current live attempt and construct the guarded `:runtime/agent-abort` payload from `:execution-session-id`.
- [x] Implement the shared cancel-transition builder used by both handlers: handler-before gate, cascade-set enumeration, one multi-run `:root-state-update`, and ordered cancellation effects.
- [x] Ensure each per-run terminal guard is evaluated inside the returned `:root-state-update` fn, not only in handler-before.
- [x] Emit `:runtime/cancel-inflight-run` according to the D35 split: canonical cancellation/cascade emits worker cancel only for top-level cancel or the live top-level remove first pass; runtime-handle cleanup may also emit it before `:runtime/drop-inflight-run` for terminal top-level remove (D38) and absent stale-handle cleanup (D36b); direct/terminal nested sub-run remove emits no worker cancel and must not infer a parent/top-level worker.
- [x] Emit guarded `:runtime/agent-abort` once per cascade-set run with a live current attempt.
- [x] Emit `:runtime/mark-workflow-jobs-terminal` from the cancel dispatch while the run record is still present.
- [x] Implement `:psi.workflow/cancel-run` live behaviour using the shared cancel-transition builder with no re-entrant remove effect and no record drop.
- [x] Implement `:psi.workflow/cancel-run` terminal/absent behaviour as success/no-op with no canonical cancellation/cascade effects.
- [x] Implement `:psi.workflow/remove-run` live first pass using the shared cancel-transition builder plus an ordered re-entrant `:runtime/dispatch-event` targeting `:psi.workflow/remove-run`.
- [x] Ensure live `remove-run` first pass does not apply the pure record dissoc before terminalization effects.
- [x] Implement `:psi.workflow/remove-run` terminal top-level branch as bare canonical record dissoc plus ordered `:runtime/cancel-inflight-run` then `:runtime/drop-inflight-run` cleanup.
- [x] Implement `:psi.workflow/remove-run` terminal nested branch as bare canonical record dissoc plus exact-key `:runtime/drop-inflight-run` only.
- [x] Implement `:psi.workflow/remove-run` absent branch as success/no-op canonical result plus ordered stale-handle cleanup pair: `:runtime/cancel-inflight-run` then `:runtime/drop-inflight-run`.
- [x] Return the D29 public result fields for live/terminal/absent cancel and remove, including `:found?`, `:noop?`, `:cancelled?`, `:removed?`, `:status`, and `:error nil` as applicable.
- [x] Add handler tests for ordered effect vectors, public result shapes, sequential idempotency, top-level vs nested distinction, absent cleanup semantics, and re-entrant remove event shape.

## Slice 4 — Cooperative execution stop points

- [x] Add a read-path stop predicate equivalent to `(or (nil? run) (= :cancelled (:status run)))` for workflow execution checkpoints.
- [x] Insert the stop predicate before starting each workflow step attempt.
- [x] Insert the stop predicate before creating/delegating a sub-run.
- [x] Insert the stop predicate before spawning an ordinary child agent session for a workflow step.
- [x] Insert the stop predicate after child-turn or delegate waits return, before advancing to the next workflow state.
- [x] Make the `send-and-drain`/blocking wait path interrupt-aware so `InterruptedException` or interrupted status returns control to the cooperative checkpoint cleanly.
  - Covered 2026-06-11: top-level `execute-run!`/resume catches `InterruptedException`, clears interrupted status, and reports the current canonical run result; lifecycle checkpoints also stop on canonical `:cancelled`/absence.
- [x] Ensure a stopped run exits without starting further ordinary workflow advancement while leaving cancellation-control effects/writes allowed.
- [x] Add controlled tests that record the D31 cancel checkpoint and assert no step attempt/session/sub-run starts after it for a cancelled top-level run.
- [x] Add a test showing a top-level worker parked in a wait is woken by `future-cancel(true)` and terminates cleanly.
  - Covered 2026-06-11: `workflow-async-path-test` uses a real parked future plus the canonical `:runtime/cancel-inflight-run` effect and asserts interruption/cancellation plus cancelled job terminalization.

## Slice 5 — Delegate result and nested-run semantics

- [x] Update `delegate-step-runtime-result` so a missing delegate run maps to the same failed-step result as `:cancelled` (message may say cancelled or removed).
- [x] Keep present non-terminal delegate statuses on the existing anomaly/default path; only run absence is folded into cancelled semantics.
- [x] Add a direct nested sub-run cancel test proving no `:runtime/cancel-inflight-run` worker effect is emitted, the child attempt is aborted, the sub-run reaches `:cancelled`, and the parent continues via a failed delegate step.
  - Covered 2026-06-11: dispatch test asserts no worker cancel + guarded child abort + parent still `:running`; delegate-result test asserts a directly cancelled child maps to failed delegate-step semantics while the parent remains running.
- [x] Add a direct live nested sub-run remove test proving cancel-then-remove drops the sub-run record, run absence maps to cancelled failure, and the parent continues.
  - Covered 2026-06-11: dispatch test asserts live nested remove emits guarded child abort and re-entrant record-drop with no worker cancel, leaves parent `:running`, and existing removed-run delegate-result test covers run-absence ⇒ cancelled/removed failure.
- [x] Add a top-down parent cancel test proving descendant cascade-set runs reach `:cancelled`, guarded child aborts target `:execution-session-id`, and only the single top-level worker cancel effect is emitted.

## Slice 6 — Background job terminalization and public-surface cleanup

- [x] Extend `maybe-mark-workflow-jobs-terminal!` (or its workflow status predicate) so `:cancelled` runs terminalize background jobs with `:outcome :cancelled`.
- [x] Add tests proving cancel-without-remove terminalizes the job as cancelled while the run record remains present.
  - Covered 2026-06-11: cancellation dispatch test seeds a workflow background job, dispatches cancel, and asserts job `:cancelled` + canonical run still present as `:cancelled`.
- [x] Add tests proving live remove terminalizes the job before the re-entrant remove drops the run record.
  - Covered 2026-06-11: live remove dispatch test asserts the job is `:cancelled` even though the re-entrant remove has dropped the canonical run record.
- [x] Remove or reroute any remaining command-layer or mutation-layer cancel/remove side effects: direct `inflight-runs` cleanup and the current `delegate remove` active-background-job cleanup (`cleanup-active-delegate-background-jobs-before-remove!` / `terminalize-active-delegate-background-jobs!`) must be removed/routed through canonical `:psi.workflow/remove-run` dispatch/effects, or any retained pre-remove cleanup must be explicitly documented as not being a cancellation/remove side effect.
- [x] Document the existing re-entrant `:runtime/dispatch-event` sequencing in `doc/architecture.md` dispatch sequencing/runtime effects guidance.
- [x] Add a CHANGELOG `[Unreleased]` entry for the user-visible fix: cancelling/removing delegated workflows now stops in-flight execution and avoids orphaned workflow workers.

## Slice 7 — Acceptance test net and gates

- [x] Add or update tests for acceptance criterion 1: top-level cancel stops post-checkpoint attempts and reaches cancelled terminal state with terminal job.
  - Covered 2026-06-11: controlled top-level execution test proves post-checkpoint step attempts/results stop and the run remains `:cancelled`; cancellation dispatch/async-path tests assert cancelled background-job terminalization.
- [x] Add or update tests for acceptance criterion 2: live top-level remove cancels the top-level future and drops the handle via effects.
- [x] Add or update tests for acceptance criterion 3: no forbidden ordinary workflow side effects are initiated after the D31 checkpoint in a nullable/controlled harness.
  - Covered 2026-06-11: a nullable workflow execution harness cancels during the first child turn and asserts no second child session/attempt starts and no late actor result is recorded after the checkpoint.
- [x] Add or update tests for acceptance criterion 4: top-down nested propagation uses guarded child aborts and one top-level future cancel only.
  - Covered 2026-06-11: parent cancel cascades `:cancelled` to live descendants, emits guarded aborts for cascade-set live attempts, skips terminal descendants, and emits exactly one top-level worker cancel.
- [x] Add or update tests for acceptance criterion 5: live nested remove aborts child turn, emits no worker cancel, and parent continues.
  - Covered 2026-06-11: direct live nested remove dispatch test asserts guarded abort, no worker cancel, record drop, and parent `:running`; removed-run delegate result covers the parent failed-step continuation input.
- [x] Add or update tests for acceptance criterion 6: direct nested cancel produces failed delegate-step semantics and parent continuation.
  - Covered 2026-06-11: direct nested cancel dispatch test asserts parent survives and child is `:cancelled`; delegate result test asserts cancelled child ⇒ failed delegate-step semantics.
- [x] Add or update tests for acceptance criterion 7: removed/absent delegate run maps to cancelled failed-step result.
- [x] Add or update tests for acceptance criterion 8: sequential terminal requests emit no canonical cancellation/cascade effects while terminal remove still removes records/cleans handles.
- [x] Add or update tests for acceptance criterion 10: D29 public result contracts and ordered handle cleanup for terminal/absent remove.
- [x] Add comments or review notes covering out-of-test-scope criteria 9 and 9a, tying the code to D22.2 and D27.
  - Covered 2026-06-11 in `implementation.md`: D22.2 concurrent duplicate-effect idempotency and D27 direct-sub-run post-enumeration spawn race remain construction-review items rather than deterministic tests.
- [x] Run focused workflow-runtime tests affected by cooperative checkpoints and delegate result semantics.
- [x] Run focused agent-session dispatch/effect/mutation/workflow tests affected by cancel/remove routing and effects.
- [x] Run `bb test`.
  - Passed 2026-06-11 after focused cancellation/async-path additions.
- [x] Run `clj-kondo --lint components` (or the project-standard lint target if narrower/faster lint tasks are available).
- [x] Fix any failing tests or lint findings without weakening the cancellation contract.
  - No failing focused/full tests or focused lint findings remained in this pass.

## Plan/steps ambiguity follow-ups (ψ, 2026-06-11)

- [x] Qualify the Slice 3 `:runtime/cancel-inflight-run` emission step with the D35 split: **canonical cancellation/cascade** emits worker cancel only for top-level cancel / live top-level remove, while **runtime-handle cleanup** may also emit it for terminal top-level remove (D38) and absent stale-handle cleanup (D36b) before `:runtime/drop-inflight-run`; direct/terminal nested sub-run remove must still emit no worker cancel and must not infer a parent/top-level worker.
- [x] Reconcile `delegate remove` command-layer background-job cleanup with the adapter-only cancel/remove boundary: remove/reroute the current `cleanup-active-delegate-background-jobs-before-remove!` / `terminalize-active-delegate-background-jobs!` side effect through the canonical `:psi.workflow/remove-run` dispatch/effects path (or explicitly document why any retained pre-remove cleanup is not a cancellation/remove side effect), so steps cover more than the direct `inflight-runs` `swap!`.

## Plan/steps inconsistency follow-ups (ψ, 2026-06-11)

- [x] Reconcile `plan.md`'s `remove-run` terminal/absent wording with D29/D34/D36b and Slice 3 steps: terminal remove drops an existing canonical record, while absent remove returns success/no-op with no canonical record found/removed and emits only the ordered stale-handle cleanup pair (`:runtime/cancel-inflight-run` then `:runtime/drop-inflight-run`).

## Implementation review follow-ups (ψ, 2026-06-11)

- [x] Make workflow step-entry advancement writes cancellation-safe: the `:step/enter` attempt append/start update must re-check run presence/`:cancelled` inside the `swap!` update fn (or equivalent CAS-safe helper) so a cancel racing after the pre-check cannot resurrect the run to `:running` or record a post-D31 attempt; add a regression test for cancel between the pre-check and attempt-start write.
  - Covered 2026-06-11: attempt-start now uses a CAS helper that re-checks stop state at commit; regression cancels between child-session creation and attempt-start write and asserts no attempt append/resurrection/turn execution.
- [x] Make delegate sub-run creation cancellation-safe: replace `delegate-step-runtime-result`'s parent pre-check + stale-state `create-run` + `reset!` with a guarded update that re-checks the parent run inside the state update before adding the child run, preserving the parent `:cancelled` state and creating no delegated sub-run after the D31 checkpoint; add a regression test for cancel racing between delegate pre-check and child-run creation.
  - Covered 2026-06-11: delegate creation now uses a CAS loop that re-checks parent liveness before child-run commit; regression cancels during child create-run and asserts no delegated run is added and parent remains `:cancelled`.

## Implementation review follow-ups (ψ pass 2, 2026-06-11)

- [x] Make post-entry ordinary statechart action writes cancellation-safe: `:step/record-result`, `:step/record-failure`, `:judge/record`, `:iteration/exhausted`, and any canonical run/progression write they call must re-check run presence/`:cancelled` inside the state update (or equivalent CAS-safe helper) so a cancel racing after lifecycle `stop-checkpoint` but before the action `swap!` cannot record ordinary results/failures or rewrite `:cancelled` to `:running`, `:completed`, or `:failed`; add regression tests for cancel between event admission and each representative write class.
- [x] Include judge child turns in the cancellation stop/abort contract: add stop checks before judge-session creation and after `execute-judge!`, ensure a cancel during judging does not queue/record ordinary judge output, and either extend guarded cancellation aborts to target an in-flight judge session or explicitly model judge execution so it cannot continue ordinary child-turn journal/session writes after the D31 checkpoint; add regression coverage for cancellation during a judged step's judge turn.

## Implementation review follow-ups (ψ pass 3, 2026-06-11)

- [x] Make invoke-step attempt-data recording cancellation-safe: the post-`invoke-step-runtime-result` `merge-latest-attempt-data` write must re-check run presence/`:cancelled` inside the state update (or equivalent CAS-safe helper) so a cancel racing after the post-invoke stop check cannot record ordinary `:effective-args`/attempt metadata after the D31 checkpoint; add a regression test for cancel between the post-invoke stop check and the attempt-data write.
  - Covered 2026-06-11: invoke attempt-data now uses the existing CAS live-run helper; regression cancels during the metadata write window and asserts no `:effective-args`, ordinary result, or downstream session spawn after cancellation.

## Implementation review follow-ups (ψ pass 4, 2026-06-11)

- [x] Make actor child-session creation cancellation-safe: if a D31 cancel checkpoint wins during/after `create-step-attempt-session!` but before the guarded attempt-start CAS attaches the attempt to the workflow run, the implementation must not leave an untracked ordinary workflow child session alive after cancellation; either attach/commit through a guarded protocol or immediately abort/cleanup the just-created session on failed live-run attachment. Add a regression for cancellation between actor child-session creation and attempt attachment.
  - Covered 2026-06-11: failed live-run attachment now aborts the just-created execution session through the workflow execution adapter; the existing step-entry race regression asserts the abort.
- [x] Make judge child-session creation cancellation-safe: if cancellation wins after judge child-session creation but before `attach-judge-session-if-live!` records `:judge-session-id` on the latest attempt, the just-created judge session must be aborted/cleaned up rather than left untracked and unaddressable by guarded cancellation aborts. Add a regression for cancellation between judge session creation and judge-session attachment.
  - Covered 2026-06-11: failed judge-session attachment now aborts the just-created judge session through the workflow execution adapter; `workflow-judge-test` covers cancellation between creation and attachment.

## Implementation review follow-ups (ψ pass 5, 2026-06-11)

- [x] Make ranked model fallback cancellation-safe: thread the workflow stop predicate into `execute-with-ranked-fallback!` (or equivalent) and re-check run presence/`:cancelled` before each fallback candidate turn after the first, so a cancel racing after one fallback-worthy actor failure cannot start another ordinary actor turn after the D31 checkpoint; add a regression for cancellation between fallback candidates.
  - Covered 2026-06-11: ranked fallback now receives the workflow stop predicate and checks it before each non-initial candidate turn; regression asserts cancellation between fallback-worthy failures starts no second actor turn, sets no fallback model, and records no ordinary pending result.
- [x] Make judge retry loops cancellation-safe: re-check the workflow stop predicate immediately before every structured-output retry turn and every no-match retry turn in `execute-judge!`, so cancellation after a judge response is routed to retry cannot start another ordinary judge turn after the D31 checkpoint; add regression coverage for cancellation between judge retry attempts.
  - Covered 2026-06-11: judge turns go through a live-checking helper used for initial and retry turns; regression coverage asserts no no-match retry or structured-output retry turn starts after the stop predicate trips.

## Implementation review follow-ups (ψ pass 6, 2026-06-11)

- [x] Make initial ordinary execution starts cancellation-safe: actor session steps, initial judge turns, and invoke deterministic operations must not start after a D31 cancel CAS that lands after the final pre-start stop check; either guard the start through a cancellation-safe protocol or make the guarded abort leave a durable per-session stop marker consumed by turn/operation start. Add regressions for cancellation between the final pre-start stop check and each of: initial actor turn start, initial judge turn start, and invoke-operation start.
  - Covered 2026-06-11: workflow-owned turn execution now checks canonical run cancellation/removal before invoking the prompt adapter; deterministic-operation runtime checks the invocation workflow run before calling the handler; the statechart ordinary-session path aborts a just-attached child session if the final pre-turn check trips. Added actor-turn, judge-turn, and invoke-operation start regressions.

## Implementation review follow-ups (ψ pass 7, 2026-06-11)

- [x] Update `doc/workflows.md` to reflect the new `delegate remove` / workflow removal contract: live top-level remove is canonical cancel-then-remove with dispatch-owned background-job terminalization and cancel-before-drop worker cleanup; live nested-sub-run remove aborts the child turn and lets the parent continue via cancelled/removed delegate failure semantics; terminal/absent remove is idempotent canonical-record cleanup (with stale-handle cleanup where applicable), not the old command-layer pre-cleanup/fail-if-cleanup-fails model.
  - Covered 2026-06-11: `doc/workflows.md` now describes canonical dispatch removal, live top-level cancel-then-remove, live nested sub-run parent-continuation semantics, and terminal/absent idempotent cleanup.

## Implementation review follow-ups (ψ pass 8, 2026-06-11)

- [ ] Make guarded judge abort effects no-op after the judge turn/result is complete: do not treat `:status :succeeded` alone as proof that a judge session is still live; add a judge-specific active/completed marker or active-turn/session liveness check so stale duplicate/concurrent cancellation aborts cannot abort an already-completed judge session, while in-flight judge sessions remain abortable. Add a regression where a guarded judge abort effect is executed after judge output/result is recorded and assert the abort executor no-ops.
