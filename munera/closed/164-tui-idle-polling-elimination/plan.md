# 164 — TUI idle polling elimination

The change should proceed in proof-first order.

`design-steps.md` is the canonical checklist for review-added design follow-ups on this task. `implementation.md` is the append-only execution log for decisions, discoveries, blockers, and verification notes.

First, inventory the behaviors that currently happen on synthetic idle polls and capture them in focused tests. Next, add a failing test that proves idle state currently self-reschedules poll work. Then change the update loop so only active/streaming states continue polling, while any idle-time maintenance that must remain gets an explicit event-driven trigger. Finish by rerunning the focused TUI proof set.

## Step 1: inventory and lock down current tick-provided behaviors
- Inspect the TUI update path around `poll-cmd`, `:agent-poll`, and `update-tick-state`.
- The current inventory from code inspection is:
  - UI snapshot refresh via `:ui-read-fn` in `update-tick-state`
  - notification dismiss-expired / dismiss-overflow dispatch in `update-tick-state`
  - extension command-name refresh in `update-tick-state`
  - spinner/progress refresh while streaming via `handle-agent-poll`
- The authoritative post-change trigger rule for extension command-name refresh is now fixed for this task: refresh only on explicit TUI event boundaries that already re-enter the update loop with fresh runtime-facing context (`:window-size`, `:agent-event`, `:external-message`, `:context-updated`, `:agent-result`, `:agent-error`, `:agent-aborted`), never from idle `:agent-poll` self-ticks.
- Add or tighten focused tests so these behaviors are covered explicitly before the implementation changes.
- Treat notification maintenance as timerless/event-driven in this task; do not preserve it via a replacement idle timer.
- Use the focused proof set named in `design.md` as the minimum acceptance boundary for this proof-first phase.

## Step 2: add a failing proof for idle self-tick
- Add a focused test that initializes idle TUI state and drives an idle `:agent-poll` update.
- Prove that the current implementation reschedules another poll command while idle.
- Prefer a test phrased in terms of observable command rescheduling rather than wall-clock CPU measurement.
- Confirm the test fails once written against the desired behavior.

## Step 3: remove idle self-polling while preserving active polling
- Change the TUI update loop so idle `:agent-poll` no longer perpetually reschedules the next poll.
- Preserve streaming poll/reschedule behavior for spinner/progress and completion delivery.
- If any former tick behavior must still happen while idle, move it onto an explicit event boundary instead of generic self-polling.
- Keep the rule obvious in code structure.

## Step 4: verify and shape
- Run the focused TUI tests for the pre-change behavior coverage and the new idle-self-tick proof.
- Add or adjust any narrow comments needed to make the trigger boundaries obvious.
- Confirm the final proof set demonstrates idle no longer self-ticks and streaming still does.

## Risks
- Accidentally dropping a behavior that was implicitly relying on idle ticks.
- Preserving too much of the old abstraction and leaving a subtler idle poll loop behind.
- Over-broadly changing refresh semantics outside the TUI's ownership.

## Decisions to record in implementation.md
- Which update behaviors were previously piggybacking on idle polling.
- Which of those behaviors remained, moved, or were eliminated.
- The final trigger rule for idle vs streaming polling.
