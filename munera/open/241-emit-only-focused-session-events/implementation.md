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
