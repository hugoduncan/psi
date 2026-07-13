# Implementation notes

- architectural review added 1 new design step (focus-gate placement should sit at RPC's fanout/delivery boundary per doc/architecture.md projection-delivery rule; core design otherwise a strong fit — focus is transport-scoped RPC-owned state, no app-runtime convergence obligation).
- ambiguity review added 2 new design steps (nil-focus default-session fallback underspecified for multi-session case; undecided session/updated terminal-phase partition — the design's stated crux).
- inconsistency review added 1 new design step (session/resumed + session/rehydrated classified both as focus-gated and as never-gated transition-bundle events).
