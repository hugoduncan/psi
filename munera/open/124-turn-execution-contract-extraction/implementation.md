2026-05-07

Task created from workflow component-extraction review.

Creation rationale:
- a lower actor/judge execution contract is the key enabling seam for a clean workflow runtime extraction
- the current workflow runtime still depends too directly on higher session/turn orchestration details
- bounded callers should consume canonical execution results directly rather than reconstructing semantic meaning from journal/transcript state
- isolating execution behind a lower contract should reduce workflow-runtime coupling without forcing a full turn redesign in one task

Initial boundary notes:
- the extracted boundary may expand an existing lower turn-runtime component or introduce a small workflow-facing execution boundary
- the contract must support both actor and judge workflow steps
- workflow routing/progression remains outside this task
- mutations, resolvers, and `psi-tool` stay above the boundary
- persistence/journal behavior should remain intact but should not be the semantic result contract for bounded callers
