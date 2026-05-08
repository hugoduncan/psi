2026-05-08

Task created to capture the next turn-boundary extraction step after the current review of `psi.turn` and `turn-runtime`.

Creation rationale:
- review of `psi.turn` against the dispatch-ownership conclusion showed that `psi.turn` still mixes lower prepared-turn work with dispatch-owning session orchestration
- review of the current extracted `turn-runtime` component showed that it is already the strongest lower turn boundary in the system, but narrower than the meaningful lower prepared-turn component
- the right next move is to expand the existing `turn-runtime` component rather than revive a sibling `turn-preparation` component design

Key design decision recorded in `design.md`:
- this task extends `turn-runtime`; it does not create a new sibling lower component
- lower prepared-turn request assembly and response-recording should move toward `components/turn-runtime/`
- dispatch invocation, session queue mutation, interrupt orchestration, and other `:session/...` entrypoints remain owned by `agent-session`
- `psi.turn` should become clearer as a higher session-owned facade rather than a half-extracted lower component

Relationship to prior tasks recorded:
- `101` is treated as the seed of the correct lower component boundary
- `103` is treated as the ownership repair prerequisite that made the lower component real
- `102` is treated as historical background only; the task explicitly avoids reviving a sibling `turn-preparation` component framing
- `105` remains the umbrella architectural reference for this task

Design review follow-up notes added after initial task creation:
- the most important ambiguity was the missing normalized-input contract for lower request construction
- the task now explicitly states that lower `turn-runtime` request assembly must consume a fully normalized input map and must not perform session-state lookup, journal reads, auth resolution, skill/template expansion, command-registry lookup, or prompt-contribution selection policy
- the task now also clarifies that prompt-asset selection/ordering, auth/provider-request-option resolution, and journal/session reads needed to produce normalized inputs remain above the boundary in `agent-session`
- the task now records expected lower helper candidates (`prepared-request-state-summary`, `prepared-request-query-text`, `execution-usage-tokens`) and expected higher helper candidates (follow-up batching and dispatch/effect choreography)
- the task now adds a direct-consumer rule for any production namespace that needs helper-level access to `psi.turn-runtime.request` or `psi.turn-runtime.recording`
- the task now adds a test-movement rule to reduce unnecessary churn when proving the expanded boundary
