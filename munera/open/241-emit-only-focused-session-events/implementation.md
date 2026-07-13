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

## Design-review session outcome (3-turn batch: architecture + ambiguity + inconsistency)

- This review batch added NO new design-steps. All four pre-existing design-steps were already resolved into design.md by the earlier batch; nothing new to address from these three passes. Implementation can proceed against the current design as-is.
- Latent coupling to guard when implementing the structural gate (not a design defect, but keep true): the "not-gated" classification of `command-result`/`error`/`context/updated`/`ui/*` holds ONLY while those payloads carry no bare `:session-id`. If a future emission stamps one of them with `:session-id`, the structural gate would silently suppress it for non-focused sessions. Keep cross-session payloads free of a bare `:session-id` key (use `:active-session-id` etc. as `context/updated` does), or the structural rule and the intended classification will diverge. A characterization test asserting these cross-session events still emit while a non-focused session is active (acceptance criterion c) protects this.
- Structural-gate implementation must read the ACTUAL emitted payload, not `required-event-payload-keys` — session-scoped events (`assistant/*`, `tool/*`, `session/rehydrated`) are stamped with `:session-id` at emission despite not listing it in `required-event-payload-keys` (see events.clj / emit.clj notes above).
