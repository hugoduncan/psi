# 164 — TUI idle polling elimination

## Intent
Remove the TUI's self-sustaining idle polling loop so an otherwise idle TUI session becomes truly idle, without regressing any user-visible update behavior that the current polling loop happens to provide.

## Context
Current investigation shows that the TUI keeps scheduling `poll-cmd` even while the session phase is `:idle`. Each idle wakeup synthesizes `:agent-poll`, runs the general update path, refreshes UI snapshot state, dispatches notification-dismiss maintenance, refreshes extension command names, and then schedules the next poll again. This appears to be the most likely cause of the persistent idle CPU load.

The current polling loop is doing two different jobs:

1. a legitimate streaming/progress refresh job
2. an accidental idle maintenance/self-tick job

Before changing that behavior, we need explicit proof for the update semantics currently piggybacking on the idle poll path, so the polling removal does not silently drop needed refresh behavior.

## Scope
- Identify the TUI update behaviors currently exercised on every synthetic idle poll.
- Add focused tests that explicitly cover those behaviors before changing the implementation.
- Add a failing test proving the current idle self-tick / self-reschedule behavior.
- Change the TUI so idle state no longer continuously self-polls.
- Preserve the needed streaming/progress polling behavior.
- Preserve any non-streaming update behavior that should still occur, but make its trigger explicit rather than relying on ambient idle polling.
- Use `design-steps.md` as the canonical review follow-up checklist for design-review actions on this task.
- Use `implementation.md` as the append-only record for design decisions, discoveries, blockers, and verification notes while the task is active.

## Out of scope
- General TUI rendering redesign unrelated to idle polling.
- Broad app-runtime or session-runtime notification redesign unless required to preserve current TUI behavior.
- Changing streaming spinner/progress semantics beyond what is necessary to stop idle self-ticking.
- Performance work outside the idle polling root cause.

## Acceptance
1. There is a focused failing test that demonstrates the current idle TUI self-tick/self-reschedule behavior before the implementation change.
2. There are focused tests covering the update behaviors currently provided by the polling path before the polling change lands.
3. After the change, idle TUI state no longer continuously reschedules synthetic poll work.
4. Streaming state still polls as needed for spinner/progress updates and completion delivery.
5. Any UI snapshot refresh, notification-dismiss maintenance, or command-name refresh behavior that should remain is covered by explicit tests and is triggered by an explicit event path rather than by perpetual idle polling.
6. The resulting focused TUI tests pass.

## Design constraints
- Prefer one obvious rule: polling is for active/streaming work, not for idle maintenance.
- Preserve behavior by making triggers explicit, not by keeping a hidden background tick.
- Prove the pre-change semantics before removing the mechanism that currently provides them.
- Keep the change narrowly scoped to the TUI polling/update loop unless a deeper root cause is required.

## Trigger rules
- Polling is authoritative only for active streaming/progress refresh while the TUI is in `:streaming`.
- Idle TUI state must not schedule synthetic follow-up polls just to maintain itself.
- UI snapshot refresh and extension command-name refresh that should remain after this change must happen on explicit incoming events already handled by the TUI update path, not on ambient idle ticks.
- Notification dismiss-expired / dismiss-overflow maintenance should remain timerless and event-driven only in this task. After idle self-polling removal, dismissal maintenance should run when the TUI is already processing an explicit event boundary that refreshes UI-facing state (for example window-size, agent/event, external-message, context-updated, result, error, or abort handling), but this task should not introduce a new idle timer solely for notification cleanup.

## Proof requirements
The minimum authoritative pre-change focused proof set must pin the current tick-provided behaviors to concrete targets before implementation changes land.

Required focused targets:
- idle self-reschedule proof: `components/tui/test/psi/tui/app_update_runtime_test.clj`
- UI snapshot refresh proof: `components/tui/test/psi/tui/app_update_runtime_test.clj`
- notification maintenance proof: `components/tui/test/psi/tui/notification_render_test.clj`
- extension command-name refresh proof: `components/tui/test/psi/tui/app_update_runtime_test.clj` or a new adjacent focused TUI runtime test namespace if needed
- streaming spinner/progress polling proof: `components/tui/test/psi/tui/app_update_runtime_test.clj` and/or `components/tui/test/psi/tui/app_view_runtime_test.clj`

Required verification commands:
- focused runtime/update proof set: `clojure -M:test --focus psi.tui.app-update-runtime-test --focus psi.tui.notification-render-test`
- if streaming rendering assertions are shaped in the view suite, extend the focused run with `--focus psi.tui.app-view-runtime-test`

## Questions to resolve during implementation
- Which specific update behaviors truly need a periodic trigger, and which are just incidental side effects of the idle loop?
- Should extension command-name refresh happen only on known invalidation events rather than on each generic tick?
- Is `update-tick-state` still the right abstraction once idle self-polling is removed, or should idle and streaming refresh paths split?
