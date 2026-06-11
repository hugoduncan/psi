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

- [ ] Add a read-path stop predicate equivalent to `(or (nil? run) (= :cancelled (:status run)))` for workflow execution checkpoints.
- [ ] Insert the stop predicate before starting each workflow step attempt.
- [ ] Insert the stop predicate before creating/delegating a sub-run.
- [ ] Insert the stop predicate before spawning an ordinary child agent session for a workflow step.
- [ ] Insert the stop predicate after child-turn or delegate waits return, before advancing to the next workflow state.
- [ ] Make the `send-and-drain`/blocking wait path interrupt-aware so `InterruptedException` or interrupted status returns control to the cooperative checkpoint cleanly.
- [ ] Ensure a stopped run exits without starting further ordinary workflow advancement while leaving cancellation-control effects/writes allowed.
- [ ] Add controlled tests that record the D31 cancel checkpoint and assert no step attempt/session/sub-run starts after it for a cancelled top-level run.
- [ ] Add a test showing a top-level worker parked in a wait is woken by `future-cancel(true)` and terminates cleanly.

## Slice 5 — Delegate result and nested-run semantics

- [x] Update `delegate-step-runtime-result` so a missing delegate run maps to the same failed-step result as `:cancelled` (message may say cancelled or removed).
- [x] Keep present non-terminal delegate statuses on the existing anomaly/default path; only run absence is folded into cancelled semantics.
- [ ] Add a direct nested sub-run cancel test proving no `:runtime/cancel-inflight-run` worker effect is emitted, the child attempt is aborted, the sub-run reaches `:cancelled`, and the parent continues via a failed delegate step.
- [ ] Add a direct live nested sub-run remove test proving cancel-then-remove drops the sub-run record, run absence maps to cancelled failure, and the parent continues.
- [x] Add a top-down parent cancel test proving descendant cascade-set runs reach `:cancelled`, guarded child aborts target `:execution-session-id`, and only the single top-level worker cancel effect is emitted.

## Slice 6 — Background job terminalization and public-surface cleanup

- [x] Extend `maybe-mark-workflow-jobs-terminal!` (or its workflow status predicate) so `:cancelled` runs terminalize background jobs with `:outcome :cancelled`.
- [ ] Add tests proving cancel-without-remove terminalizes the job as cancelled while the run record remains present.
- [ ] Add tests proving live remove terminalizes the job before the re-entrant remove drops the run record.
- [x] Remove or reroute any remaining command-layer or mutation-layer cancel/remove side effects: direct `inflight-runs` cleanup and the current `delegate remove` active-background-job cleanup (`cleanup-active-delegate-background-jobs-before-remove!` / `terminalize-active-delegate-background-jobs!`) must be removed/routed through canonical `:psi.workflow/remove-run` dispatch/effects, or any retained pre-remove cleanup must be explicitly documented as not being a cancellation/remove side effect.
- [x] Document the existing re-entrant `:runtime/dispatch-event` sequencing in `doc/architecture.md` dispatch sequencing/runtime effects guidance.
- [x] Add a CHANGELOG `[Unreleased]` entry for the user-visible fix: cancelling/removing delegated workflows now stops in-flight execution and avoids orphaned workflow workers.

## Slice 7 — Acceptance test net and gates

- [ ] Add or update tests for acceptance criterion 1: top-level cancel stops post-checkpoint attempts and reaches cancelled terminal state with terminal job.
- [x] Add or update tests for acceptance criterion 2: live top-level remove cancels the top-level future and drops the handle via effects.
- [ ] Add or update tests for acceptance criterion 3: no forbidden ordinary workflow side effects are initiated after the D31 checkpoint in a nullable/controlled harness.
- [x] Add or update tests for acceptance criterion 4: top-down nested propagation uses guarded child aborts and one top-level future cancel only.
  - Covered 2026-06-11: parent cancel cascades `:cancelled` to live descendants, emits guarded aborts for cascade-set live attempts, skips terminal descendants, and emits exactly one top-level worker cancel.
- [ ] Add or update tests for acceptance criterion 5: live nested remove aborts child turn, emits no worker cancel, and parent continues.
- [ ] Add or update tests for acceptance criterion 6: direct nested cancel produces failed delegate-step semantics and parent continuation.
- [x] Add or update tests for acceptance criterion 7: removed/absent delegate run maps to cancelled failed-step result.
- [x] Add or update tests for acceptance criterion 8: sequential terminal requests emit no canonical cancellation/cascade effects while terminal remove still removes records/cleans handles.
- [x] Add or update tests for acceptance criterion 10: D29 public result contracts and ordered handle cleanup for terminal/absent remove.
- [ ] Add comments or review notes covering out-of-test-scope criteria 9 and 9a, tying the code to D22.2 and D27.
- [x] Run focused workflow-runtime tests affected by cooperative checkpoints and delegate result semantics.
- [x] Run focused agent-session dispatch/effect/mutation/workflow tests affected by cancel/remove routing and effects.
- [ ] Run `bb test`.
- [x] Run `clj-kondo --lint components` (or the project-standard lint target if narrower/faster lint tasks are available).
- [ ] Fix any failing tests or lint findings without weakening the cancellation contract.

## Plan/steps ambiguity follow-ups (ψ, 2026-06-11)

- [x] Qualify the Slice 3 `:runtime/cancel-inflight-run` emission step with the D35 split: **canonical cancellation/cascade** emits worker cancel only for top-level cancel / live top-level remove, while **runtime-handle cleanup** may also emit it for terminal top-level remove (D38) and absent stale-handle cleanup (D36b) before `:runtime/drop-inflight-run`; direct/terminal nested sub-run remove must still emit no worker cancel and must not infer a parent/top-level worker.
- [x] Reconcile `delegate remove` command-layer background-job cleanup with the adapter-only cancel/remove boundary: remove/reroute the current `cleanup-active-delegate-background-jobs-before-remove!` / `terminalize-active-delegate-background-jobs!` side effect through the canonical `:psi.workflow/remove-run` dispatch/effects path (or explicitly document why any retained pre-remove cleanup is not a cancellation/remove side effect), so steps cover more than the direct `inflight-runs` `swap!`.

## Plan/steps inconsistency follow-ups (ψ, 2026-06-11)

- [x] Reconcile `plan.md`'s `remove-run` terminal/absent wording with D29/D34/D36b and Slice 3 steps: terminal remove drops an existing canonical record, while absent remove returns success/no-op with no canonical record found/removed and emits only the ordered stale-handle cleanup pair (`:runtime/cancel-inflight-run` then `:runtime/drop-inflight-run`).
