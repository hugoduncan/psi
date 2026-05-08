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
