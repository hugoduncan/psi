# Implementation notes

- architectural review added 1 new design step (focus-gate placement should sit at RPC's fanout/delivery boundary per doc/architecture.md projection-delivery rule; core design otherwise a strong fit — focus is transport-scoped RPC-owned state, no app-runtime convergence obligation).
- ambiguity review added 2 new design steps (nil-focus default-session fallback underspecified for multi-session case; undecided session/updated terminal-phase partition — the design's stated crux).
- inconsistency review added 1 new design step (session/resumed + session/rehydrated classified both as focus-gated and as never-gated transition-bundle events).

## Notes for addressing the design-steps

Principles to maintain:
- Keep focus gating a pure function of connection state + event (design Constraint); no new side-effect channels.
- Home the gate at RPC's single fanout/delivery boundary, consistent with the projection-delivery rule in doc/architecture.md and the existing `topic-subscribed?` gate. Focus is transport-scoped RPC-local state — do not push this policy into app-runtime.
- Prefer deriving "session-scoped" structurally (presence of `:session-id`) over a second hand-curated event set.

Relevant project files:
- `components/rpc/src/psi/rpc/events.clj` — `event-topics`, `required-event-payload-keys` (per-event `:session-id` mapping), `emit-event!` (hosts the existing `topic-subscribed?` gate), `context-updated-payload`.
- `components/rpc/src/psi/rpc/state.clj` — `focus-session-id` / `set-focus-session-id!` (connection-local focus).
- `components/rpc/src/psi/rpc/transport.clj` — `default-session-id-in` (= first listed session; relevant to the nil-focus fallback design-step).
- `components/rpc/src/psi/rpc/session/emit.clj`, `.../session/ops.clj`, `.../session/commands.clj`, `.../session/navigation.clj` — set-focus ordering + rehydration bundle emission paths (relevant to the resumed/rehydrated classification design-step).

## Design-follow-up pass (batch: architectural + ambiguity + inconsistency reviews)

All 4 design-steps resolved into design.md:

- **Focus-gate placement** → gate homed in `emit-event!` at RPC fanout boundary; "session-scoped" derived structurally from `:session-id` presence in the emitted payload.
- **nil-focus fallback** → effective focus = `default-session-id-in` (first-listed session); only that session's events emit, others suppressed.
- **`session/updated` partition** (the crux) → `session/updated` is focus-gated; non-focused sessions do NOT emit terminal `session/updated`; per-session phase for the tree is carried by cross-session `context/updated` (`:sessions`).
- **resumed/rehydrated classification** → they ARE in the focus-gated set (payloads carry `:session-id`) but their sole emission path (`emit-navigation-result!`) sets focus BEFORE emitting, so they always pass the gate — no non-focused path to suppress. No contradiction.

### Discovered facts an implementer will need

- `required-event-payload-keys` (events.clj) does NOT list `:session-id` for `session/rehydrated`, `assistant/*`, `tool/*`, but those payloads ARE stamped with `:session-id` at runtime (see `emit.clj`: `emit-session-rehydrated!` L43 adds `:session-id`; `make-request-emitter`/progress loop stamp session-id). The structural gate must read the actual emitted payload, not `required-event-payload-keys`.
- `emit-navigation-result!` (emit.clj L93-99) ordering: `set-focus-session-id!` → rehydration bundle → session/updated → footer/updated → context/updated. This ordering is load-bearing for the resumed/rehydrated always-pass guarantee; do not reorder.
- `context/updated` payload = `#{:active-session-id :sessions}` — carries per-session phase, and is the cross-session (never-gated) channel the tree relies on when a session's own `session/updated` is suppressed.

No SCOPE_QUESTION items in this batch. No items left unchecked/blocked.

## Architecture review pass (design-review session, turn 1)

- no architectural review feedback — design is a strong fit: gate homed at RPC fanout boundary per doc/architecture.md projection-delivery rule; focus is transport-scoped RPC-owned fallback state; structural `:session-id` derivation honors single-source principle; emission stays a pure function of connection state + event. Prior architectural design-step already resolved.

## Ambiguity review pass (design-review session, turn 2)

- no ambiguity review feedback — the two crux ambiguities (nil-focus multi-session fallback; session/updated terminal-phase partition) plus resumed/rehydrated classification were already resolved into design.md by the prior batch. Remaining open question (Emacs client background-delta assumption) is a losslessness verification pinned by acceptance criterion (d), not an unresolved design-spec ambiguity. All design statements now single-interpretation.

## Inconsistency review pass (design-review session, turn 3)

- no inconsistency review feedback — verified the structural gate (`:session-id` presence) and the explicit non-gated enumeration agree in current code: `emit-command-result!`, `error`, and `context/updated` payloads carry no bare `:session-id`, so the enumeration faithfully describes the structural outcome (not a competing rule). resumed/rehydrated dual-classification already reconciled by prior pass; acceptance cross-session list is a subset of Scope's non-gated list; single-session-preserving claim consistent with nil-focus default-session fallback.

## Plan-review session, turn 1 (plan/steps ambiguity review)

- plan ambiguity review added 1 new design step: plan Key decision 1 freezes `:default-session-id` at construction, but design defines the nil-focus fallback as the live `default-session-id-in` (first-listed session); frozen-vs-live equivalence unverified at setup and divergence-on-session-set-change unspecified.

## Notes for the frozen-vs-live default-session-id design-step

Principles to maintain:
- Keep effective-focus resolution a pure function of connection state + event (no ctx threading into `emit-event!`), per design Constraint — but do not let that purity goal silently reinterpret the design's `default-session-id-in` (live first-listed session) as a frozen snapshot without an explicit, documented decision.
- If choosing "frozen is acceptable", state the invariant that makes it safe (e.g. the connection's default session is stable for the connection's lifetime); if not, the gate must resolve the live first-listed session, which reintroduces a ctx dependency to reconcile against the purity constraint.

Task facts an implementer will need:
- `make-rpc-state` (state.clj L11) already initializes `:focus-session-id` to the passed `session-id` (NOT nil), so the nil-focus branch only triggers when the construction `session-id` is nil or focus is later cleared — verify when that actually happens before relying on the fallback.
- Construction `session-id` originates from `session-ctx-factory` (runtime.clj `start-runtime!`, ~L96); `default-session-id-in` (transport.clj L95) = `(some-> (ss/list-context-sessions-in ctx) first :session-id)`. Equivalence of these two at setup is the unverified assertion.
- Relevant files: `components/rpc/src/psi/rpc/state.clj` (make-rpc-state, initialize-transport-state!, focus-session-id reader), `components/rpc/src/psi/rpc/transport.clj` (default-session-id-in), `components/rpc/src/psi/rpc/runtime.clj` (session-ctx-factory session-id source).

## Plan-review session (re-run), turn 1 (plan/steps ambiguity review)

- no ambiguity review feedback — the frozen-vs-live `:default-session-id` ambiguity (only plan/steps ambiguity previously surfaced) is already an `[x]` design-step and reconciled across design (Constraints "Frozen vs live default"), plan (Key decision 1a/1b), and steps (Slice 1 verify step). Verified code grounding: `make-rpc-state` seeds `:focus-session-id` from `session-id`, `initialize-transport-state!` merge keeps existing connection, `emit-event!` hosts the `topic-subscribed?` gate, `default-session-id-in` exists. Plan/steps effective-focus formula, gate placement, silent-suppression, and slice order are single-interpretation.

## Plan-review session (re-run), turn 2 (plan/steps inconsistency review)

- no inconsistency review feedback — verified design/plan/steps agree on effective-focus formula, gate placement in `emit-event!`, silent gate-before-validation, `:default-session-id` preservation in `initialize-transport-state!`, set-focus-before-rehydration ordering, and acceptance tests (a)-(d). The design-prose (`default-session-id-in`) vs plan (frozen `:default-session-id`) wording is already reconciled by design Constraints "Frozen vs live default" and the turn-1 ambiguity design-step; not re-filed.

## Plan-review session, turn 2 (plan/steps inconsistency review)

- no inconsistency review feedback — plan slice order, effective-focus formula, gate placement, silent-suppression semantics, initialize-transport-state! preservation, acceptance tests, and rehydration ordering all agree across design/plan/steps/design-steps. The design↔plan frozen-vs-live `default-session-id` divergence is already captured by the turn-1 ambiguity design-step; not re-filed here.

## Design-review session outcome (3-turn batch: architecture + ambiguity + inconsistency)

- This review batch added NO new design-steps. All four pre-existing design-steps were already resolved into design.md by the earlier batch; nothing new to address from these three passes. Implementation can proceed against the current design as-is.
- Latent coupling to guard when implementing the structural gate (not a design defect, but keep true): the "not-gated" classification of `command-result`/`error`/`context/updated`/`ui/*` holds ONLY while those payloads carry no bare `:session-id`. If a future emission stamps one of them with `:session-id`, the structural gate would silently suppress it for non-focused sessions. Keep cross-session payloads free of a bare `:session-id` key (use `:active-session-id` etc. as `context/updated` does), or the structural rule and the intended classification will diverge. A characterization test asserting these cross-session events still emit while a non-focused session is active (acceptance criterion c) protects this.
- Structural-gate implementation must read the ACTUAL emitted payload, not `required-event-payload-keys` — session-scoped events (`assistant/*`, `tool/*`, `session/rehydrated`) are stamped with `:session-id` at emission despite not listing it in `required-event-payload-keys` (see events.clj / emit.clj notes above).

## Implementation pass — all 4 slices

Implemented and tested end to end in one pass (design/plan/steps had no
remaining open design-steps to gate execution):

- `psi.rpc.state`: added `:default-session-id` to the `:connection` map,
  seeded from `session-id` in `make-rpc-state`, preserved (not clobbered) by
  `initialize-transport-state!`'s merge, with a new reader
  `rpc.state/default-session-id`.
- `psi.rpc.events`: added private `focus-allows?` — structural gate, event
  passes iff its payload lacks `:session-id`, or `(:session-id data)` equals
  `(or (focus-session-id state) (default-session-id state))`. Wired into
  `emit-event!` alongside the existing `topic-subscribed?` check, before
  payload validation — gated events are silently dropped (no error frame),
  matching the existing unsubscribed-topic behaviour.
- **Discovered and fixed a stale-session-id bug exposed by the gate**:
  `psi.rpc.session.commands/handle-command!` unconditionally called
  `emit-command-snapshots!` (→ `session/updated` + `footer/updated`) with the
  *pre-command* `session-id`, even for commands that move RPC focus mid-call
  (`/new`, `/resume`, `/tree`). Before this task that was harmless (everything
  emitted regardless of focus); under the new gate it silently suppressed the
  trailing snapshot because the old session is no longer focused. Fixed by
  emitting the trailing snapshot for `(or (events/focus-session-id state)
  session-id)` — the *currently*-focused session post-command — instead of the
  stale pre-command session id. This is a load-bearing fix, not a workaround:
  the trailing snapshot's purpose is to describe "the session the user is now
  looking at", which is exactly the post-command focus.
- Tests added: `psi.rpc-invariants-test` (default-session-id seeding +
  preservation), `psi.rpc-events-test` (6 new `emit-event!` focus-gate tests:
  non-focused suppressed, focused emitted, nil-focus default fallback,
  cross-session `context/updated` unaffected, post-refocus suppression of the
  stale session, single-session behaviour-preserving, `ui/*`/`command-result`/
  `error` always emit). Acceptance test (d) — focus switch emits the full
  rehydration bundle for the newly focused session — is covered by the
  pre-existing `/tree <prefix>` navigation test in
  `rpc_session_navigation_test.clj`, which already exercises the real
  `emit-navigation-result!` path end to end and continued to pass unmodified
  under the gate (confirms `set-focus-session-id!` → bundle ordering holds).
- Emacs-ui audit (design's open question): grepped `psi-events.el` and related
  dispatch code for session-id-conditional handling of `assistant/*`/`tool/*`
  deltas tied to buffer liveness; found none — event handlers dispatch by
  `:session-id` in the payload to route to the right buffer, with no
  assumption of receiving background-session traffic to "keep buffers warm".
  No client-side change needed; the design's rehydration-on-refocus path is
  the sole mechanism for populating a session's buffer.
- Full `bb test` run showed unrelated pre-existing failures/timeouts in
  `turn-runtime`/streaming/retry test namespaces on this worktree,
  reproducible independent of this change (spot-checked by re-running the
  affected rpc-scoped and prompt-scoped namespaces individually — all green).
  Not investigated further; out of scope for this task.

## Plan-follow-up pass (batch: plan-review turns 1+2)

Executed the single attributed follow-up: the "Plan ambiguity review follow-ups" item in design-steps.md (added by plan-review turn 1 `c5d0e0754`; turn 2 `cc7949409` added no new item). Note: this batch's review follow-ups land in design-steps.md, not steps.md — the `git diff baseline..HEAD -- steps.md` is empty; the attributed added checklist line lives in design-steps.md. Baseline = parent of `c5d0e0754` = `7a37d8e46` (plan/steps creation).

Resolution recorded (frozen-vs-live `:default-session-id`):
- **Decision: frozen is intentional and safe**, not stale. Justification homed in design Constraints ("Frozen vs live default") and plan Key decision 1 (a)+(b).
- Verified from code that the nil-focus branch is a narrow pre-first-focus window: `make-rpc-state` (state.clj L21) initializes `:focus-session-id` to the construction `session-id` (NOT nil), and no code path calls `set-focus-session-id!` with nil (grep: all callers pass a concrete sid — commands.clj L42, ops.clj L232, emit.clj L94). So the frozen default only governs the window before any explicit focus, during which session-set divergence from the live `default-session-id-in` is immaterial.
- Added a Slice-1 verification step in steps.md asserting setup equivalence (`:default-session-id` == `default-session-id-in` at construction) — closes plan point (a)'s previously-unverified assertion.
- Implementer note: `initialize-transport-state!` (state.clj) merges `:focus-session-id nil` as a default only when `:connection` is absent (existing connection wins). Ensure `:default-session-id` is likewise added to that default merge (plan Slice 1) so a re-initialized connection does not lose the frozen default.

## Implementation review pass

- added 3 steps: the `session_switch` `command-result` carries a bare `:session-id`, so the structural gate silently suppresses it for non-focused targets — the Slice-2 "No finding" audit claim and the CHANGELOG "command-result … regardless of focus" note are both inaccurate.

## Review follow-up pass (ψ)

- addressed 3 review steps: renamed the `session_switch` cross-session `command-result` key from `:session-id` to `:target-session-id` (`command_results.clj`) and updated the emacs-ui consumer (`psi-events.el`) + its dispatch test; the structural focus gate is unchanged (no event-string special-case). Added `emit-event-session-switch-command-result-emits-for-non-focused-target-test` (`rpc_events_test.clj`) proving a non-focused-target switch still emits. Corrected the Slice-2 audit note (steps.md) and the CHANGELOG `[Unreleased]` entry.
- Verified: `bb test --focus psi.rpc-events-test` (16 tests) and `psi.rpc-command-results-test` (3 tests) pass; lint clean. The emacs `psi-dispatch-command-test.el` session-switch test passes; the two failures there (`psi-resume-explicit-path-command-clears-transcript-via-rehydrate-events`, `psi-session-rehydrated-event-replays-messages-without-get-messages`) are pre-existing (reproduce with my changes stashed) and unrelated to this change.

## Implementation review pass 2 (ψ)

- added 2 steps: legacy prompt-path `assistant/message` (`handle-prompt-command-result!`, reached via the RPC `"prompt"` op) omits `:session-id`, so the structural gate never gates it — an undocumented in-event-type asymmetry vs the `:session-id`-stamped streaming path, plus missing test coverage for either branch of that asymmetry.

## Review follow-up pass 2 (ψ)

- addressed 2 review steps: resolution (a) — `handle-prompt-command-result!` now takes `session-id` and stamps `:session-id` on every legacy-prompt-path `assistant/message`, so it gates through `focus-allows?` identically to the streaming path (no in-event-type asymmetry, no gate special-casing). Caller `session/prompt.clj` passes the already-in-scope `session-id`; existing `rpc_command_results_test.clj` call updated for the new arity. Added characterization test `emit-event-legacy-prompt-assistant-message-suppressed-for-non-focused-session-test` (`rpc_events_test.clj`) proving focused-session legacy feedback emits and non-focused is suppressed. `bb test --focus psi.rpc-events-test` (17 tests) and `psi.rpc-command-results-test` (3 tests) pass; lint/repair clean.

## Implementation review pass 3 (ψ)

- no new steps. Verified end-to-end against code: gate homed in `emit-event!` per design (structural `:session-id` rule, effective focus = focus-or-frozen-default); all cross-session payloads (`error` at session.clj/prompt.clj, every `command-result` variant, `context/updated`, `ui/*`) carry no bare `:session-id` (`:tree-rename` embeds the id only in its message string; `session_switch` uses `:target-session-id`); `state.clj` seeds/preserves/reads `:default-session-id` as claimed; CHANGELOG accurate. Tests pass: rpc-events-test (17), rpc-invariants-test (6), rpc-session-navigation-test (3, covers acceptance (d) via real `emit-navigation-result!` under the gate). Both real latent-coupling hazards were already caught and fixed by prior review passes.

## Test review pass (task-test-review, ψ)

- added 2 steps: the load-bearing `handle-command!` L160 trailing-snapshot fix
  is only protected for `/new` (`/resume` + `/tree` focus-switch snapshot
  emission unpinned); `tree-switch` legacy prompt-path feedback
  (`handle-prompt-command-result!` L64) untested and its source-vs-target
  `:session-id` stamping unpinned.

## Test-review follow-up pass (ψ)

- addressed 2 test-review steps (pure test additions; no src change):
  - L160 trailing-snapshot fix now pinned for `/resume` and `/tree` focus
    switches. Added two `rpc_session_navigation_test.clj` cases subscribing to
    `session/updated` + `footer/updated` and asserting the trailing snapshot IS
    emitted stamped with the newly-focused (resumed/child) session. Verified
    they are load-bearing: reverting L160 to bare `session-id` fails 6 asserts
    across both cases; restored fix → all pass.
  - `:tree-switch` legacy prompt-path feedback classification pinned. Added
    `emit-event-legacy-prompt-tree-switch-feedback-stamped-with-source-session-test`
    in `rpc_events_test.clj`: (a) payload `:session-id` is the SOURCE session
    (target id only in the message text) and emits while source is focused;
    (b) suppressed once focus moves to the target — so a future edit stamping
    the target id is a behavioural change, not silent drift.
  - `bb test --focus psi.rpc-events-test` (18 tests) and
    `psi.rpc-session-navigation-test` (3 tests) pass; lint clean on both files.

## Test review pass 2 (task-test-review, ψ)

- no new steps. Independently verified: structural focus gate, cross-session
  payload classification, `:default-session-id` state, and all design
  acceptance criteria (a)-(d) are covered by real-dependency tests (no
  mocks/stubs in the focus-gate/navigation suites; the only `with-redefs` are
  pre-existing footer-projection tests). rpc-events-test (18) +
  rpc-invariants/navigation/command-results (12) all green. Prior test-review
  follow-ups (L160 pinning, tree-switch feedback classification) confirmed
  present and load-bearing.

## Test-shaper review pass (ψ)

- added 3 steps: single-session test asserts count-only (weak
  meaningful-failure signal, leaves `tool/*` gating un-pinned); no test pins
  `tool/*`/`session/updated` suppression for a non-focused session; tree-switch
  feedback test pins an exact prose literal, coupling to message-format detail
  rather than the source-vs-target classification contract.

- addressed 3 test-shaper review steps (rpc_events_test.clj): strengthened
  single-session test to assert emitted event-name set == input set (pins each
  session-scoped event incl. `tool/start` as emitted-when-focused); added
  `emit-event-suppresses-tool-start-for-non-focused-session-test` (pins `tool/*`
  suppression); replaced exact prose literal in the tree-switch feedback test
  with `str/includes? … "target"` (keeps source-vs-target classification,
  drops prose coupling). Extracted `session-scoped-event-data` helper +
  `single-session-events` vec. `bb test --focus psi.rpc-events-test` → 19 pass;
  clj-kondo clean.

## Test-shaper review pass 2 (ψ)

- added 2 steps: two positive-emission emit tests (`cross-session-event-emits`
  acceptance-(c) and `ui-and-command-result-and-error-emit`) still assert
  count-only, inconsistent with the per-frame `:event` assertion style the
  prior test-shaper pass established and leaving the never-gated event names
  un-pinned.

- addressed 2 test-shaper pass-2 review steps: strengthened
  `emit-event-cross-session-event-emits-regardless-of-focus-test` (now
  asserts `:event` = `context/updated` and surviving `:active-session-id`)
  and `emit-event-ui-and-command-result-and-error-emit-regardless-of-focus-test`
  (now asserts emitted event-name set `#{ui/widget-specs-updated command-result
  error}`), closing the count-only meaningful-failure gaps for the never-gated
  events. `psi.rpc-events-test` green (19 tests, 62 asserts); lint clean.

## Test-shaper review pass 3 (ψ)

- added 2 steps: the `emit-event!` focus-gate tests silently rely on
  `topic-subscribed?`'s empty-subscriptions default-open behaviour (incidental
  setup hiding a cross-gate dependency); and no test pins the two gates as
  independent/conjunctive (a focused-session event on an unsubscribed topic is
  not proven suppressed).

## Test-shaper follow-up pass 3 (ψ)

- addressed 2 test-shaper pass-3 review steps (pure test additions/refactor;
  no src change):
  - Made the focus-gate tests' topic-subscription precondition explicit via a
    new `make-focus-gate-state` helper that subscribes every event topic
    (`subscribe-topics! rpc.events/event-topics`); routed all `emit-event!`
    focus-gate tests through it. Removes the silent reliance on
    `topic-subscribed?`'s empty-subs default-open, so a future change to that
    default no longer reinterprets what these tests prove.
  - Added `emit-event-focus-and-subscription-gates-are-independent-test`
    pinning both directions of two-gate independence: a focused-session event
    on an UNSUBSCRIBED topic is dropped by the subscription gate; a
    subscribed-topic event for a NON-focused session is dropped by the focus
    gate — neither gate short-circuits the other.
  - `bb test --focus psi.rpc-events-test` → 20 tests, 64 asserts, all pass;
    clj-kondo clean, clj-paren-repair clean.
