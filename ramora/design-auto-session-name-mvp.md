# Design: Auto Session Name MVP

**Status:** Proposal
**Scope:** minimal extension + minimal runtime seams

## Purpose

Build the smallest useful vertical slice for `auto-session-name`.

This MVP does **not** rename sessions yet. It proves the eventing and scheduling
substrate needed by the future extension by:
- loading an `auto-session-name` extension
- counting completed turns per session
- every `N` completed turns, scheduling a delayed extension event
- when that delayed event fires, showing a transient UI notification

This establishes the extension lifecycle shape before adding transcript
sanitization, helper-session inference, model selection, and rename mutation.

## Why this MVP

The full feature needs several moving parts. The riskiest early uncertainty is
not title generation itself, but whether the extension/runtime boundary can
cleanly support:
- observing completed turns
- scheduling deferred extension work
- targeting that work at the originating session
- emitting UI-visible feedback without mutating conversation state

This MVP proves those pieces first.

Update: the current implementation has moved beyond this original MVP. It now
includes transcript sanitization, helper-session inference, shared-model
selection through `psi.ai.model-selection`, and source-session rename mutation.
Treat this document as historical design context rather than a description of
current behavior.

## User-visible behavior

When the extension is loaded:
- it behaves quietly until prompt-lifecycle turns complete
- after every `N` completed turns for a session, it schedules a delayed internal
  extension event
- when that scheduled event fires, it shows a transient notification such as:
  - `auto-session-name: rename checkpoint for session <id>`

Default values:
- `N = 2`
- delay = small fixed debounce delay (for MVP)

## Non-goals

This MVP does not:
- infer titles
- create helper sessions
- choose helper models
- sanitize transcripts
- set session names
- persist extension-specific turn counters across restart

## Definitions

### Completed turn

A completed turn is one source-session assistant turn that has fully completed
through the shared prompt lifecycle and reached `prompt-finish`.

### Rename checkpoint

A rename checkpoint is the scheduled deferred extension event emitted every `N`
completed turns. In the MVP, its only effect is a transient UI notification.

## Proposed runtime seams

The MVP needs two small capability additions.

### 1. Completed-turn extension event

When a prompt lifecycle turn finishes, the runtime should dispatch an extension
event:
- event name: `session_turn_finished`
- payload includes at least:
  - `:session-id`
  - `:turn-id`

This gives extensions a canonical semantic hook for completed-turn logic.

### 2. Extension-facing delayed event scheduling

Extensions need a way to request:
- after `delay-ms`, dispatch extension event `X` with payload `Y`

For MVP, a new extension mutation should schedule a delayed extension-dispatch.

Conceptually:
- extension calls a mutation like `psi.extension/schedule-event`
- runtime sleeps on a daemon thread
- runtime later dispatches `:notify/extension-dispatch`
- the event reaches extension handlers through the normal extension registry

Payload should be opaque extension data.

## Extension behavior

The extension maintains in-memory state:
- per-session completed-turn counts for eligible source sessions only
- configured threshold `N`
- configured delay

Current automatic naming/counting is limited to top-level user-interactive
source sessions. Delegated workflow sessions, workflow-step sessions, nested
workflow sessions, and auto-session-name helper sessions are ineligible and do
not increment counters or produce rename checkpoints, even when they contain
visible user or assistant messages.

Behavior:
1. observe `session_turn_finished`
2. ignore the event unless the source session is an eligible top-level
   user-interactive session
3. increment the eligible source-session turn counter
4. when count is a multiple of `N`, request a scheduled extension event
5. when the scheduled event fires, first re-check eligibility; ineligible
   checkpoint events silently no-op

If UI is absent:
- log instead of notify, or no-op

## Scheduling policy

For MVP:
- fixed small debounce delay is sufficient
- exact delay value is not user-facing architecture; it just proves deferred
  extension execution
- scheduled event payload should include the original `session-id` and current
  turn count

## Failure behavior

If scheduling fails:
- no session state changes
- extension should not crash the session
- no transcript mutation

If notification fails because UI is absent:
- fall back to log or no-op

## Acceptance criteria

- prompt lifecycle completion emits a canonical extension event
- an extension can request a delayed extension event through runtime mutation
- `auto-session-name` extension registers both handlers on init
- after every 2 completed turns in an eligible top-level user-interactive
  source session, a delayed event is scheduled
- delegated workflow, workflow-step, nested workflow, and helper sessions do not
  count toward rename checkpoints
- when the delayed event fires for an eligible session, the extension shows a
  transient UI notification
- when the delayed event fires for an ineligible session, it silently no-ops
- no source-session transcript mutation occurs
- no session renaming occurs yet

## Planned follow-on after MVP

Once this MVP is proven, later slices can add:
- transcript sanitization
- helper-session execution
- model-selection hierarchy usage
- title validation
- session renaming
- manual-vs-auto naming policy
