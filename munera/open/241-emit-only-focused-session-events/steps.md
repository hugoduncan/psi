# Steps — 241 Emit RPC events only for the focused session

## Slice 1 — State: default session id

- [x] Add `:default-session-id` to the `:connection` map in
      `psi.rpc.state/make-rpc-state`, populated from the existing
      `session-id` option.
- [x] Preserve `:default-session-id` in
      `psi.rpc.state/initialize-transport-state!` (merge default `nil`,
      existing value wins).
- [x] Add reader `psi.rpc.state/default-session-id`.
- [x] Tests: `make-rpc-state` stores it; `initialize-transport-state!` does
      not clobber it.
- [x] Verify setup equivalence (plan Key decision 1a): assert the construction
      `:default-session-id` equals `default-session-id-in` for the connection's
      initial ctx, so the frozen snapshot matches the live first-listed session
      at setup. Frozen-vs-live divergence over the connection lifetime is out of
      scope by invariant (nil-focus window precedes any explicit focus; focus is
      never cleared back to `nil`) — see plan Key decision 1b / design
      Constraints "Frozen vs live default". (`make-rpc-state` seeds
      `:default-session-id` from the same `session-id` used to seed
      `:focus-session-id`, which is the connection's construction-time initial
      session — equal to `default-session-id-in` at that instant by
      construction; no separate runtime assertion needed beyond the existing
      `make-rpc-state` unit test.)

## Slice 2 — Gate: focus filtering in emit-event!

- [x] Add private `focus-allows?` in `psi.rpc.events`: event passes iff
      payload lacks `:session-id`, or `(:session-id data)` equals
      `(or (rpc.state/focus-session-id state) (rpc.state/default-session-id state))`.
- [x] Wire `focus-allows?` into `emit-event!` alongside `topic-subscribed?`,
      before payload validation (gated events are silently dropped, no error
      frame).
- [x] Test (a): session-scoped event (e.g. `assistant/delta` with
      `:session-id` of a non-focused session) is not emitted.
- [x] Test (b): same event for the focused session is emitted.
- [x] Test: nil `focus-session-id` — events for the default session emit,
      events for another session are suppressed.
- [x] Test (c): `context/updated` (no `:session-id` in payload) emits while
      focus is on a different session.
- [x] Test: `ui/*`, `command-result`, `error` emit regardless of focus.
- [x] Audit all emission sites to confirm no cross-session event payload
      carries `:session-id` (would be wrongly gated); raise if found. (No
      finding — `context-updated-payload`, `command-result`, `error`, and all
      `ui/*` payloads carry no bare `:session-id` key.)
- [x] Run `bb test --focus psi.rpc-events-test` and lint changed files.

## Slice 3 — Integration: navigation/rehydration ordering

- [x] Verify (read, do not change) `emit-navigation-result!` calls
      `set-focus-session-id!` before emitting the rehydration bundle.
- [x] Test (d): focus switch to session B emits `session/resumed` /
      `session/rehydrated` / `session/updated` / `footer/updated` for B
      (bundle not suppressed by the gate). (Covered by the pre-existing
      `/tree <prefix>` navigation test in `rpc_session_navigation_test.clj`,
      which exercises the real `emit-navigation-result!` path end to end and
      now runs under the focus gate.)
- [x] Test: after focusing B, session-scoped events stamped with A's
      `:session-id` (progress-loop style emission through `emit-event!`) are
      suppressed while B's emit.
- [x] Test: single-session connection — all previously emitted events still
      emit (behaviour-preserving common case).

## Slice 4 — Verification & docs

- [x] Audit emacs-ui client code for assumptions about receiving
      background-session deltas; record findings in implementation.md
      (design's open question).
- [x] Run full `bb test`. (RPC-scoped suites pass; unrelated pre-existing
      flakiness in `turn-runtime`/streaming/retry suites observed on this
      worktree independent of this change — see implementation.md.)
- [x] Add CHANGELOG `[Unreleased]` entry (Changed: RPC streams session-scoped
      events only for the focused session; refocus rehydrates).
- [x] Check `doc/architecture.md` projection-delivery section; update if it
      describes RPC delivery gating. (No existing section describes RPC
      per-connection delivery gating in that level of detail; no update
      needed.)
