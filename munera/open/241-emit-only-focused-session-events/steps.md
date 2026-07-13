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

- [x] **Legacy prompt-path `assistant/message` emissions bypass the focus gate
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
      (Resolved via (a): `handle-prompt-command-result!` now takes `session-id`
      and stamps `:session-id` on every emitted `assistant/message`, so the
      legacy path gates through `focus-allows?` identically to the streaming
      path. The gate is unchanged — no event-string special-casing. Caller
      `session/prompt.clj` passes the already-available `session-id`.)
- [x] Add a characterization test covering the chosen resolution of the item
      above: if (a), assert a legacy-prompt-path `assistant/message` for a
      non-focused session is suppressed and for the focused session emitted;
      if (b), assert it emits regardless of focus and record the rationale in
      the test. Current `rpc_events_test.clj` `assistant/*` coverage only
      exercises `:session-id`-stamped payloads, so neither branch of this
      asymmetry is currently pinned.
      (Added `emit-event-legacy-prompt-assistant-message-suppressed-for-non-focused-session-test`
      in `rpc_events_test.clj`, wiring `handle-prompt-command-result!` through
      `emit-event!`: focused-session feedback emits, non-focused-session
      feedback is suppressed by the structural gate.)

## Test review follow-ups (task-test-review, ψ)

- [x] **The load-bearing `handle-command!` trailing-snapshot fix
      (`commands.clj` L160, `(or (events/focus-session-id state) session-id)`)
      is only incidentally protected for the `/new` path.** implementation.md
      describes this fix as load-bearing across all focus-moving commands
      (`/new`, `/resume`, `/tree`): the trailing `session/updated` /
      `footer/updated` snapshot must be stamped with the *post-command* focus
      so it passes the gate, not the *pre-command* `session-id` (which the gate
      would suppress). Only the `/new` navigation test
      (`rpc_session_navigation_test.clj` L76-92) subscribes to `footer/updated`
      and would fail if L160 reverted to bare `session-id`. The `/resume <path>`
      and `/tree <session-id>`/`<prefix>` navigation tests do NOT subscribe to
      `footer/updated` or `session/updated`, so a regression of L160 for those
      paths (trailing snapshot silently gated for the newly-focused session)
      would go undetected. Add a characterization test asserting the trailing
      `footer/updated` (and/or `session/updated`) snapshot IS emitted after a
      `/resume` and after a `/tree`/`/tree <prefix>` focus switch — i.e. it is
      stamped with the new focus, not the stale pre-command session — so the
      L160 fix is pinned for every focus-moving command path, not just `/new`.
      (Added two characterization tests in `rpc_session_navigation_test.clj`:
      `/resume <path>` and `/tree <prefix>` now subscribe to
      `session/updated` + `footer/updated` and assert the trailing snapshot IS
      emitted stamped with the newly-focused session. Verified they pin the
      fix: reverting L160 to bare `session-id` fails 6 asserts across both new
      cases; restored fix → all pass.)
- [x] **The `tree-switch` legacy prompt-path feedback
      (`command_results.clj` `handle-prompt-command-result!` L64) is untested
      and its `:session-id` stamping is semantically ambiguous.** It emits
      `assistant/message` with `[session switch requested: <target-sid>]` in the
      TEXT but stamps the payload `:session-id` with the CURRENT (source)
      `session-id` (from the L39 arg), not the switch target. Under the focus
      gate this feedback therefore emits only when the *source* session is
      focused — which is the intended "feedback belongs to the current view"
      behaviour, but nothing pins it: there is no test that a `:tree-switch`
      cmd-result through `handle-prompt-command-result!` (a) emits its feedback
      for the focused source session and (b) carries the source `:session-id`
      (not the target). Add a characterization test so this cross-session-switch
      feedback classification does not silently drift (e.g. if a future edit
      stamps the target id, the feedback would be gated away when switching
      from a focused session). Covers a `handle-prompt-command-result!` case
      currently exercised by neither `rpc_events_test.clj` nor
      `rpc_command_results_test.clj`.
      (Added `emit-event-legacy-prompt-tree-switch-feedback-stamped-with-source-session-test`
      in `rpc_events_test.clj`, wiring `handle-prompt-command-result!` with a
      `:tree-switch` cmd-result through `emit-event!`: asserts (a) the payload
      `:session-id` is the SOURCE session (not the switch target, which appears
      only in the message text) and it emits while the source is focused, and
      (b) once focus moves to the target the source-stamped feedback is gated
      out — pinning that a future edit stamping the target id would be a
      behavioural change, not silent drift.)

## Test-shaper review follow-ups (ψ)

- [x] **`emit-event-single-session-connection-behaviour-preserved-test`
      asserts only `(= 6 (count @captured))`, which gives weak meaningful-failure
      signal and leaves `tool/*` focus-gating un-pinned.** The test drives six
      distinct event names (`session/updated`, `assistant/delta`, `tool/start`,
      `footer/updated`, `session/resumed`, `session/rehydrated`) but asserts only
      a total count. A regression that wrongly suppressed one focused-session
      event while double-emitting another would still count 6 and pass. This is
      also the ONLY place `tool/start` (and by proxy the `tool/*` design-listed
      session-scoped events) is exercised against the gate at all — and only via
      the count. Strengthen to assert the emitted event-name *set* equals the
      input set (or map event→emitted?), so each session-scoped event is
      individually pinned as emitted-when-focused. Behaviour-focused, not
      implementation-detail: it asserts observable per-event emission.
      (Done: the test now asserts `(= (set single-session-events)
      (set (map :event @captured)))` (plus the count), pinning each
      session-scoped event — including `tool/start` — as emitted-when-focused.
      Extracted a `session-scoped-event-data` helper + `single-session-events`
      vec, reused by the new suppression test below.)

- [x] **No test pins `tool/*` (or `session/updated`/`footer/updated`)
      SUPPRESSION for a non-focused session.** The only per-event suppression
      test (`emit-event-suppresses-session-scoped-event-for-non-focused-session-test`)
      uses `assistant/delta`. The structural gate keys on payload `:session-id`
      presence (not event name), so a single representative suppression case is
      economical and defensible — but the design explicitly enumerates
      `tool/*` and `session/updated` as focus-gated, and nothing asserts they
      are actually suppressed for a non-focused session. Add one representative
      suppression assertion for a `tool/*` (or `session/updated`) payload with a
      non-focused `:session-id`, closing the gap between the design enumeration
      and the test net. (If the strengthened single-session test above is made
      to also cover a non-focused variant, this can fold into it.)
      (Done: added `emit-event-suppresses-tool-start-for-non-focused-session-test`
      asserting a `tool/start` payload stamped with a non-focused `:session-id`
      is suppressed, closing the design-enumeration/test-net gap for `tool/*`.)

- [x] **`emit-event-legacy-prompt-tree-switch-feedback-...` pins the exact
      prose literal `"[session switch requested: target]"`, coupling the test to
      `command_results.clj`'s message-format detail.** The test's load-bearing
      contract is source-vs-target `:session-id` classification (payload
      `:session-id` = source; target appears only in text). Asserting the full
      formatted string additionally ties the test to prose wording that is not
      the behaviour under test — a robustness/behaviour-focus concern: a benign
      copy-edit of the feedback string would fail this test for the wrong
      reason. Prefer asserting the target id is *present in* the message text
      (e.g. `str/includes?`) while the payload `:session-id` is the source,
      rather than pinning the exact literal — keep the classification contract,
      drop the incidental prose coupling.
      (Done: the text assertion is now
      `(str/includes? … "target")` instead of the exact literal, keeping the
      source-vs-target classification contract while dropping the incidental
      prose coupling. Added `clojure.string` to the ns requires.)

## Test-shaper review follow-ups (ψ, pass 2)

- [x] **`emit-event-cross-session-event-emits-regardless-of-focus-test`
      (`rpc_events_test.clj` L54-63) asserts only `(= 1 (count @captured))`,
      giving weaker meaningful-failure signal than its sibling emit tests and
      inconsistent assertion style.** Every other positive-emission emit test
      (`…-emits-session-scoped-event-for-focused-session-test`,
      `…-session-switch-command-result-emits-…`,
      `…-legacy-prompt-assistant-message-…`) asserts both the count AND the
      captured frame's `:event` (and often payload key). This cross-session
      test — the acceptance-criterion (c) case for `context/updated`, the
      canonical never-gated event — asserts only the count, so a regression
      that emitted a *different* event (or dropped `context/updated` while an
      unrelated event leaked through) would still count 1 and pass. Strengthen
      to also assert `(= "context/updated" (:event (first @captured)))` (and,
      per the sibling tests, that the payload survives — e.g.
      `:active-session-id`), matching the consistent per-frame assertion style
      and closing the meaningful-failure gap for the acceptance-(c) event.
      (Done: the test now also asserts
      `(= "context/updated" (:event (first @captured)))` and
      `(= "s2" (get-in (first @captured) [:data :active-session-id]))`,
      matching the sibling per-frame assertion style and pinning the
      acceptance-(c) event by name and surviving payload key.)

- [x] **`emit-event-ui-and-command-result-and-error-emit-regardless-of-focus-test`
      (`rpc_events_test.clj` L191-204) asserts only `(= 3 (count @captured))`
      across three distinct never-gated event names (`ui/widget-specs-updated`,
      `command-result`, `error`).** Like the single-session test the prior
      test-shaper pass strengthened, a count-only check would pass if one of
      the three were wrongly suppressed while another double-emitted, and it
      does not pin *which* events survived. This is the ONLY coverage that the
      `ui/*`, `command-result` (`type "ok"`), and `error` topics emit
      regardless of focus — the design's non-session-scoped enumeration.
      Strengthen to assert the emitted event-name *set* equals
      `#{"ui/widget-specs-updated" "command-result" "error"}` (plus the count),
      individually pinning each never-gated event as emitted-when-a-different-
      session-is-implied, consistent with the strengthened single-session test.
      (Done: the test now also asserts
      `(= #{"ui/widget-specs-updated" "command-result" "error"}
      (set (map :event @captured)))`, individually pinning each never-gated
      event by name and consistent with the strengthened single-session test.)
