Approach:
- keep the temporary runtime source-root attr removed and the graph surface small
- keep the internal runtime-root helper only for extension install path resolution
- replace namespace reload mismatch rejection with target-source resolution under the requested/session worktree
- load namespace targets from worktree-resolved source files rather than relying on currently loaded source provenance as a hard gate
- surface loaded-source-path vs target-source-path mismatch as a warning in reload output
- update psi-tool docs and system prompt examples to describe worktree-authoritative reload with warning-only mismatch diagnostics
- add focused tests for target-source resolution, mismatch warning behavior, and removal of the temporary attr/guidance

