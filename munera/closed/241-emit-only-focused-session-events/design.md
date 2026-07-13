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

### Where the gate lives (resolved)

Focus gating is homed inside `emit-event!` (`psi.rpc.events`), at RPC's single
subscriber-aware fanout/delivery boundary, alongside the existing
`topic-subscribed?` gate. This is consistent with the projection-delivery rule
in `doc/architecture.md`, which makes RPC the one point that recomputes
delivery from canonical state plus connection-local focus. Focus is
transport-scoped, RPC-owned connection state; the gate is *not* pushed into
per-session emitter call-sites (`make-request-emitter` / progress loop), which
would fragment fanout policy across emission sites and duplicate the
session-scoped/cross-session partition.

"Session-scoped" is derived **structurally**: an event is session-scoped iff its
emitted payload carries a `:session-id` key. This avoids maintaining a second
hand-curated event set. (Note: several session-scoped events carry `:session-id`
in their runtime payload even though `required-event-payload-keys` does not list
it — e.g. `session/rehydrated`, `assistant/*`, `tool/*` are stamped with
`:session-id` at emission. The gate reads the actual payload, not the required
key set.)

### Session-scoped events (subject to focus gating)

Events whose payload carries a `:session-id` and that describe activity of one
specific session:

- `assistant/delta`, `assistant/thinking-delta`, `assistant/message`
- `tool/start`, `tool/executing`, `tool/update`, `tool/result`
- `session/updated`, `footer/updated`
- `session/resumed`, `session/rehydrated`

`session/resumed` and `session/rehydrated` **are** in the focus-gated set
(their payloads carry `:session-id`, so they are session-scoped by the
structural rule). There is no contradiction with treating them as the
navigation/rehydration bundle: their *only* emission path is
`emit-navigation-result!`, which calls `set-focus-session-id!` to the target
session **before** emitting the bundle (`emit.clj`). By that ordering the target
session is already the focused session, so these events always pass the focus
check. They are gated in principle, but never suppressed in practice because
they are only ever emitted for the just-focused session. There is no
non-focused emission path for them to suppress.

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
- `focus-session-id` may be `nil` (fresh connection before any focus). Resolved
  behaviour: when focus is `nil`, the effective focus is the connection's
  default session — `default-session-id-in` (`psi.rpc.transport`), i.e. the
  first-listed session. Session-scoped events whose `:session-id` equals that
  default session still emit; session-scoped events for any *other* session are
  suppressed, exactly as they would be under an explicit focus. This is
  intended: a fresh connection is viewing its first/default session, so only
  that session's activity is relevant, and background sessions stay gated even
  before an explicit focus is set. (Refocusing later rehydrates any suppressed
  session losslessly via the navigation path.)
  - **Frozen vs live default (resolved).** The nil-focus default is resolved as
    the connection's *construction-time* default session — the `session-id`
    passed to `make-rpc-state`, which equals `default-session-id-in` (the live
    first-listed session) *at connection setup* because both derive from the
    same initial session bootstrapped for the connection. The gate reads this
    frozen construction-time value (stored as connection-local
    `:default-session-id`), **not** a per-emission recomputation of the live
    `default-session-id-in`. This is intentional and safe under the invariant
    that the nil-focus window exists only *before any explicit focus is set*,
    and `focus-session-id` is only ever advanced to explicit sessions (never
    cleared back to `nil`). Consequently the frozen default never needs to
    track later session-set changes (a session closing, or a new session being
    inserted first): once focus is explicit, the frozen default no longer
    governs emission. The frozen value is therefore stable-but-authoritative
    for its whole window of relevance, not stale.
- Behaviour-preserving for the single-session case (the common case): one session
  is always focused, everything emits as before.

## Resolved decisions

- **Focus-gate placement** (was: where to place the gate). Resolved: inside
  `emit-event!`, at RPC's fanout/delivery boundary, with "session-scoped"
  derived structurally from the presence of `:session-id` in the emitted
  payload. See "Where the gate lives" under Scope.

- **`session/updated` partition** (was: the crux — should non-focused sessions
  emit a terminal `session/updated`?). Resolved: `session/updated` is a
  session-scoped, focus-gated event. A non-focused session does **not** emit a
  terminal `session/updated` on phase-completion. Per-session phase for the
  session tree is carried by `context/updated` (payload
  `#{:active-session-id :sessions}`), which is cross-session and always emits;
  its `:sessions` entries reflect each session's current phase from canonical
  state, so the tree shows a non-focused session's "done" status without
  requiring its own `session/updated`. On refocus, the navigation path re-emits
  that session's `session/updated`/`footer/updated` with its full current state.
  This keeps the session-scoped vs cross-session partition clean: per-session
  streaming/status detail is focus-gated; the global tree summary is not.

## Open questions

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
