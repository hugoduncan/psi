# Design follow-up steps


- [x] Clarify where the root-registry-backed skill adapter lives and what APIs it exposes: whether `components/skill-registry` gains root-state/root-registry-aware APIs, agent-session owns the root-state adapter over the pure vector helpers, or another boundary owns hydration/read/write projections.
- [x] Clarify the exact hydration timing and owner for legacy/session-seeded `:skills` across new session, resume, fork, child session, scheduler-created session, and workflow child-session paths, including whether hydration is part of the same root-state update that creates the session or a required follow-up dispatch/effect.
- [x] Clarify the contract for non-bootstrap session creation paths that are still supplied concrete skill maps when some definitions are not yet present in root-registry: whether those paths are allowed to register missing definitions synchronously as part of the same root-state update, must reject unregistered skills, or require prior normalization by an earlier bootstrap/setup step.
- [ ] Add an explicit implementation step to replace the current startup `agent_session/bootstrap.clj` `:session/register-skill` loop with direct root-registry skill-definition hydration before any session membership is created, so task steps match the bootstrap ownership split required by the design and plan.
