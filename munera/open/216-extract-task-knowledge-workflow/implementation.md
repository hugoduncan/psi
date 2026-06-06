# Implementation notes

- 2026-06-06 architecture-fit review: ACTIONABLE_FEEDBACK. AR1: the design explicitly bypasses mementum's human approval gate for `memories`/`knowledge`, conflicting with AGENTS.md's MemoryArtifacts boundary (`mementum governs ... approval_gate`). Fit requires either a narrow protocol/meta amendment that authorizes this autonomous artifact-extraction path, or changing the workflow to emit approval-ready candidates instead of raw autonomous writes.
