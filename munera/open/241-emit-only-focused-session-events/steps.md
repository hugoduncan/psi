# Steps — 241 Emit RPC events only for the focused session

## Slice 1 — State: default session id

- [ ] Add `:default-session-id` to the `:connection` map in
      `psi.rpc.state/make-rpc-state`, populated from the existing
      `session-id` option.
- [ ] Preserve `:default-session-id` in
      `psi.rpc.state/initialize-transport-state!` (merge default `nil`,
      existing value wins).
- [ ] Add reader `psi.rpc.state/default-session-id`.
- [ ] Tests: `make-rpc-state` stores it; `initialize-transport-state!` does
      not clobber it.

## Slice 2 — Gate: focus filtering in emit-event!

- [ ] Add private `focus-allows?` in `psi.rpc.events`: event passes iff
      payload lacks `:session-id`, or `(:session-id data)` equals
      `(or (rpc.state/focus-session-id state) (rpc.state/default-session-id state))`.
- [ ] Wire `focus-allows?` into `emit-event!` alongside `topic-subscribed?`,
      before payload validation (gated events are silently dropped, no error
      frame).
- [ ] Test (a): session-scoped event (e.g. `assistant/delta` with
      `:session-id` of a non-focused session) is not emitted.
- [ ] Test (b): same event for the focused session is emitted.
- [ ] Test: nil `focus-session-id` — events for the default session emit,
      events for another session are suppressed.
- [ ] Test (c): `context/updated` (no `:session-id` in payload) emits while
      focus is on a different session.
- [ ] Test: `ui/*`, `command-result`, `error` emit regardless of focus.
- [ ] Audit all emission sites to confirm no cross-session event payload
      carries `:session-id` (would be wrongly gated); raise if found.
- [ ] Run `bb test --focus psi.rpc-events-test` and lint changed files.

## Slice 3 — Integration: navigation/rehydration ordering

- [ ] Verify (read, do not change) `emit-navigation-result!` calls
      `set-focus-session-id!` before emitting the rehydration bundle.
- [ ] Test (d): focus switch to session B emits `session/resumed` /
      `session/rehydrated` / `session/updated` / `footer/updated` for B
      (bundle not suppressed by the gate).
- [ ] Test: after focusing B, session-scoped events stamped with A's
      `:session-id` (progress-loop style emission through `emit-event!`) are
      suppressed while B's emit.
- [ ] Test: single-session connection — all previously emitted events still
      emit (behaviour-preserving common case).

## Slice 4 — Verification & docs

- [ ] Audit emacs-ui client code for assumptions about receiving
      background-session deltas; record findings in implementation.md
      (design's open question).
- [ ] Run full `bb test`.
- [ ] Add CHANGELOG `[Unreleased]` entry (Changed: RPC streams session-scoped
      events only for the focused session; refocus rehydrates).
- [ ] Check `doc/architecture.md` projection-delivery section; update if it
      describes RPC delivery gating.
