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
      carries `:session-id` (would be wrongly gated); raise if found.
      (Corrected after review: the `session_switch` cross-session
      `command-result` DID carry a bare `:session-id`, so it was wrongly
      gated. Fixed by renaming its key to `:target-session-id` in
      `command_results.clj` — see "Review follow-ups" below. `error` and all
      `ui/*` payloads carry no bare `:session-id`; `context-updated-payload`
      carries `:active-session-id`, not a bare `:session-id`.)
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

## Review follow-ups (implementation review)

- [x] **Cross-session `command-result` DOES carry a bare `:session-id` — the
      Slice-2 audit "No finding" claim is incorrect.**
      `command-results/handle-command-result!`
      (`components/rpc/src/psi/rpc/session/command_results.clj` L122-124) emits
      `{:type "session_switch" :session-id (:session-id cmd-result)}` for
      `:tree-switch`/`:session-switch` results. The structural gate treats any
      payload with `:session-id` as session-scoped, so this cross-session
      `command-result` is silently **suppressed** whenever its target
      `:session-id` ≠ the connection's effective focus. This is exactly the
      latent-coupling hazard the design flagged ("keep cross-session payloads
      free of a bare `:session-id`; raise if found, do not special-case
      silently"). Focus is NOT moved to the target before this emission for the
      dispatched-command path (`handle-new-session-command-result!` only
      re-focuses `:new-session`), so the switch notification can be dropped.
      Resolve per the design guardrail: rename the key on this cross-session
      payload (e.g. `:switched-session-id` / `:target-session-id`) so the
      structural rule and the intended never-gated classification agree — do
      not special-case the event string in the gate.
      (Fixed: `command_results.clj` now emits `:target-session-id` instead of a
      bare `:session-id`; `psi-events.el` reads `:target-session-id`. The gate
      is unchanged — no event-string special-casing.)
- [x] Add a characterization test asserting a `session_switch` `command-result`
      whose `:session-id` differs from the current focus is still emitted
      (protects the intended cross-session classification against the structural
      gate; acceptance criterion (c) currently only covers `context/updated`,
      which has no bare `:session-id`, so it does not catch this case).
      (Added `emit-event-session-switch-command-result-emits-for-non-focused-target-test`
      in `rpc_events_test.clj`.)
- [x] Correct the two now-inaccurate claims that `command-result` always emits
      regardless of focus: the Slice-2 audit note in this file (L44-46) and the
      CHANGELOG `[Unreleased]` entry (`command-result … continue to emit
      regardless of focus`). Both are false while the `session_switch` payload
      carries a bare `:session-id`; update them alongside the fix above.

## Review follow-ups (implementation review — pass 2, ψ)

- [ ] **Legacy prompt-path `assistant/message` emissions bypass the focus gate
      because they omit `:session-id`.** The design lists `assistant/message`
      among the session-scoped, focus-gated events, but
      `command-results/handle-prompt-command-result!`
      (`components/rpc/src/psi/rpc/session/command_results.clj` L30-82, reached
      via the RPC `"prompt"` op in `session/prompt.clj` L63) emits its
      slash-command feedback as `{:role … :content …}` with **no**
      `:session-id`. Under the structural rule (`focus-allows?` returns `true`
      when the payload lacks `:session-id`) these `assistant/message` events are
      therefore **never gated**, unlike the streaming path
      (`emit/emit-assistant-message!` / `emit-assistant-text!`, which stamp
      `:session-id` and ARE gated). This is defensible under the design's
      "structural rule is authoritative" resolution, but it is an undocumented
      behavioural asymmetry within a single event type and a latent-coupling
      hazard: if the legacy prompt op is ever driven concurrently across
      sessions on one connection, a non-focused session's slash-command
      `assistant/message` would leak to the focused view. Resolve by either
      (a) stamping the current session's `:session-id` on the legacy-path
      `assistant/message` payloads so they gate consistently with the streaming
      path, or (b) documenting in design.md that legacy-prompt-path
      `assistant/message` feedback is intentionally never gated (and why),
      updating the design's session-scoped enumeration to note the split.
      Prefer (a) unless a concrete reason to exempt the legacy path is
      identified — do not special-case the event string in the gate.
- [ ] Add a characterization test covering the chosen resolution of the item
      above: if (a), assert a legacy-prompt-path `assistant/message` for a
      non-focused session is suppressed and for the focused session emitted;
      if (b), assert it emits regardless of focus and record the rationale in
      the test. Current `rpc_events_test.clj` `assistant/*` coverage only
      exercises `:session-id`-stamped payloads, so neither branch of this
      asymmetry is currently pinned.
