2026-05-07

Task created to extract canonical session-facing persistence into a lower component.

Creation rationale:
- `psi.agent-session.persistence` already behaves like a lower shared subsystem rather than session orchestration logic
- it depends only on `session-journal.store`, `session-state.model`, and `session-state.state`
- `psi.turn` still depends directly on `psi.agent-session.persistence`, so this extraction is a useful dependency-reduction move on the path toward a broader `psi.turn` extraction
- the current split of ownership is incomplete: persistence semantics live in `agent-session.persistence`, but persistence-specific state paths still live in `session-state.state`, and top-level/child session initialization still reconstructs persistence state maps manually

Concrete extraction mapping recorded in `design.md`:
- move the current public/session-facing `psi.agent-session.persistence` surface into a new lower `session-persistence` component
- move persistence-specific path ownership for journal and flush-state out of `psi.session-state.state`
- move canonical persistence subtree initialization helpers out of `session-runtime` and `child-session-state`
- keep `agent-session.dispatch-effects` as an adapter seam if useful, but make it delegate downward to the extracted owner

Key boundary decision:
- this task is not just a file move for helpers
- the new component should also own the canonical persistence subtree paths and initialization constructors, otherwise the persistence boundary remains structurally split

Expected architectural payoff:
- lower shared journal/persistence API no longer owned by `agent-session`
- `psi.turn` can depend on lower persistence ownership instead of reaching back up into `agent-session`
- persistence subtree shape becomes explicit and centrally owned
- top-level and child session initialization stop manually rebuilding persistence maps
