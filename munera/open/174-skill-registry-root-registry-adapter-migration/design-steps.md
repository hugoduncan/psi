# Design follow-up steps


- [x] Clarify where the root-registry-backed skill adapter lives and what APIs it exposes: whether `components/skill-registry` gains root-state/root-registry-aware APIs, agent-session owns the root-state adapter over the pure vector helpers, or another boundary owns hydration/read/write projections.
- [x] Clarify the exact hydration timing and owner for legacy/session-seeded `:skills` across new session, resume, fork, child session, scheduler-created session, and workflow child-session paths, including whether hydration is part of the same root-state update that creates the session or a required follow-up dispatch/effect.
