# 241 — Emit RPC events only for the focused session

## Goal

Restrict RPC event emission to the connection's currently **focused** session.
Events belonging to non-focused sessions should not be streamed to the Emacs UI.

## Why

Today `psi.rpc.events/emit-event!` gates emission only on:

1. topic membership in `event-topics`, and
2. connection-level topic subscription (`subscribed-topics`).

It does **not** consider `focus-session-id`. Each session runs its own progress
loop (`psi.rpc.session.streams/start-progress-loop!`) that stamps events with its
own `:session-id` and emits onto the single shared transport frame. As a result,
every background session streams full `assistant/delta`, `assistant/thinking-delta`,
`tool/start`, `tool/executing`, `tool/update`, `tool/result`, `session/updated`,
`footer/updated`, etc. to Emacs even when the user is viewing a different session.

Consequences:
- Continuous RPC bandwidth cost for hidden sessions with no server-side
  backpressure or focus-based throttling.
- The only available filter (topic subscription) is global to the connection,
  not per-session.

The Emacs UI already re-requests full session content on focus change: navigating
to a session sets focus and emits a rehydration bundle
(`emit/emit-navigation-result!` → `emit-session-rehydration!` →
`session/resumed` + `session/rehydrated`, plus `session/updated`,
`footer/updated`, `context/updated`). So a focused-only emission policy loses no
information the UI needs: on refocus it pulls the missed content from the backend.

## Scope

Change server-side emission so that **session-scoped** events are emitted only
when their `:session-id` matches the connection's `focus-session-id`.

### Session-scoped events (subject to focus gating)

Events whose payload carries a `:session-id` and that describe activity of one
specific session:

- `assistant/delta`, `assistant/thinking-delta`, `assistant/message`
- `tool/start`, `tool/executing`, `tool/update`, `tool/result`
- `session/updated`, `footer/updated`
- `session/resumed`, `session/rehydrated`

### Non-session-scoped / cross-session events (NOT gated)

Must continue to emit regardless of focus, so the UI's session tree / global
surfaces stay correct:

- `context/updated` (describes all sessions + active session)
- `ui/*` (dialog, widgets, widget-specs, status, notification, frontend-action)
- `command-result`, `error`
- handshake

## Constraints

- Emission remains a pure function of connection state + event; no new
  side-effect channels.
- Preserve existing topic-subscription semantics; focus gating is an additional
  filter layered on session-scoped events only.
- Do not gate emission that is part of the focus/navigation transition itself
  (e.g. the rehydration bundle emitted while switching to a session), since at
  that point the target session is/has become the focused session — the natural
  ordering (`set-focus-session-id!` before emitting the new session's snapshots)
  must be preserved so those emissions pass the focus check.
- `focus-session-id` may be `nil` (fresh connection before any focus). Define
  behaviour: when focus is `nil`, session-scoped events for the connection's
  initial/default session should still emit (avoid a dead first session).
- Behaviour-preserving for the single-session case (the common case): one session
  is always focused, everything emits as before.

## Open questions

- Where to place the focus gate: inside `emit-event!` (centralized, needs the
  event payload's `:session-id`), or at the per-session emitter boundary
  (`emit/make-request-emitter` / progress loop) where the session-id is known
  structurally? Centralizing in `emit-event!` couples it to payload shape;
  gating at the emitter boundary is more explicit but must cover every
  session-scoped emission path.
- Should non-focused sessions still emit a terminal `session/updated` on
  phase-completion (so the session tree shows "done" without refocus), or does
  `context/updated` already carry enough per-session phase for the tree? Decide
  which events are truly "session-scoped, focus-gated" vs "cross-session
  summary" — this partition is the crux of the design.
- Does any current Emacs client code assume it receives background-session deltas
  (e.g. to keep hidden buffers live)? Confirm the refocus-rehydration path fully
  reconstructs state, including in-flight streaming, so gating is lossless.

## Acceptance

- Session-scoped events for a non-focused session are not sent over RPC.
- Refocusing a session yields, via the existing navigation/rehydration path, the
  full current content of that session (no missing messages/tool calls).
- Cross-session events (`context/updated`, `ui/*`, `command-result`, `error`)
  continue to emit regardless of focus, keeping the session tree and global UI
  correct.
- Single-session behaviour is unchanged.
- Tests cover: (a) event for non-focused session suppressed, (b) same event for
  focused session emitted, (c) cross-session event emitted while a different
  session has activity, (d) focus switch causes rehydration of the newly focused
  session.
