# 164 — TUI idle polling elimination

## Notes
- Task created from investigation of persistent idle CPU use in TUI sessions.
- Initial root-cause hypothesis: idle `poll-cmd` reschedules synthetic `:agent-poll` indefinitely, and each wakeup runs `update-tick-state` work.
- Record here which behaviors were previously coupled to idle polling, which were preserved, and how their triggers changed.
- 2026-05-21 ambiguity review: actionable feedback found. The task currently requires follow-up items to be added to `design-steps.md`, but the task artifacts do not themselves declare that review/follow-up surface, so the canonical place for ambiguity follow-ups is implicit rather than explicit. The design also leaves two execution-shaping ambiguities open: it does not choose the authoritative idle trigger rule for notification-dismiss maintenance after idle self-polling is removed, and it does not define the minimum authoritative focused proof set / exact verification targets for the pre-change tick-provided behaviors, leaving completion open to materially different interpretations.
- 2026-05-21 ambiguity follow-up execution:
  - Declared `design-steps.md` as the canonical design-review follow-up checklist and `implementation.md` as the append-only execution log in both `design.md` and `plan.md`.
  - Resolved notification-dismiss maintenance to remain timerless and event-driven in this task; no replacement idle timer should be introduced solely for notification cleanup after idle self-polling removal.
  - Recorded the current tick inventory from code inspection: `update-tick-state` performs UI snapshot refresh, notification dismiss-expired / dismiss-overflow dispatch, and extension command-name refresh; streaming spinner/progress polling remains in `handle-agent-poll`.
  - Pinned the minimum authoritative proof boundary to focused TUI tests: `psi.tui.app-update-runtime-test`, `psi.tui.notification-render-test`, and `psi.tui.app-view-runtime-test` only if streaming rendering assertions require it.
  - Pinned the focused verification command baseline to `clojure -M:test --focus psi.tui.app-update-runtime-test --focus psi.tui.notification-render-test`, optionally extended with `--focus psi.tui.app-view-runtime-test` when view assertions are part of the proof.
- 2026-05-21 inconsistency review: actionable feedback found. `design.md` and `plan.md` already require post-change idle behavior to be driven by explicit event boundaries, but `design.md` still leaves extension command-name refresh framed as an open implementation question (`known invalidation events` vs generic tick) rather than an authoritative trigger rule. That leaves the accepted target behavior materially more specific for notification maintenance than for command-name refresh, even though both are listed together in acceptance and trigger rules.
- 2026-05-21 inconsistency follow-up execution:
  - Re-read the current trigger surfaces in `components/tui/src/psi/tui/app.clj`: `update-tick-state` currently runs before every handled message, while `handle-agent-message` enumerates the explicit non-poll message boundaries already available to carry post-change refresh work.
  - Chose and recorded the authoritative extension command-name refresh boundaries for this task in both `design.md` and `plan.md`: `:window-size`, `:agent-event`, `:external-message`, `:context-updated`, `:agent-result`, `:agent-error`, and `:agent-aborted`.
  - Recorded the complementary exclusion rule: idle `:agent-poll` self-ticks are not a valid refresh trigger for extension command names after polling elimination.
  - Left `steps.md` unchanged because the user asked to execute only newly added `design-steps.md` follow-ups, not implementation checklist items.
- 2026-05-21 implementation pass:
  - Added focused runtime tests proving explicit refresh-boundary behavior for UI snapshot refresh and extension command-name refresh, plus post-change proof that idle `:agent-poll` no longer reschedules follow-up poll work.
  - Tightened the existing context-widget refresh proof to use explicit `:window-size` boundaries instead of idle poll ticks, matching the task's post-change trigger rule.
  - Refactored `components/tui/src/psi/tui/app.clj` so UI snapshot refresh, notification maintenance, and extension command-name refresh only run on explicit refresh-boundary messages.
  - Removed idle `:agent-poll` self-rescheduling; streaming `:agent-poll` still delegates to `handle-agent-poll` and continues returning poll commands for spinner/progress refresh.
  - Verification: `clojure -M:test --focus psi.tui.app-update-runtime-test --focus psi.tui.notification-render-test` ✅
- 2026-05-21 implementation review: actionable feedback found. `components/tui/test/psi/tui/app_update_runtime_test.clj` still contains `idle-agent-poll-refreshes-ui-snapshot-test`, but the implementation now intentionally forbids idle `:agent-poll` from refreshing UI-facing state. The focused suite passes only because the test never seeds a changed UI snapshot before asserting widget presence, so it no longer proves the pre-change idle-poll behavior required by acceptance and now encodes the opposite regime unclearly.
- 2026-05-21 review follow-up execution:
  - Replaced `idle-agent-poll-refreshes-ui-snapshot-test` with `explicit-refresh-boundary-refreshes-ui-snapshot-test`, which mutates the authoritative UI source and proves refresh occurs on `:window-size`, an allowed explicit refresh boundary.
  - This resolves the stale proof shape by removing the last focused test that still implied idle `:agent-poll` refreshes UI-facing state after polling elimination.
  - Verification: `clojure -M:test --focus psi.tui.app-update-runtime-test --focus psi.tui.notification-render-test` ✅ (24 tests, 85 assertions).
