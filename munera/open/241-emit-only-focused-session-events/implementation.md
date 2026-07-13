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
