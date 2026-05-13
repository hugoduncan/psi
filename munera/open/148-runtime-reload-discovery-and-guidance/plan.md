Approach:
- remove the temporary runtime source-root public attr and restore the prior smaller graph surface
- keep the internal runtime-root helper only for extension install path resolution
- improve `reload-code` mismatch validation messaging so it points operators back to the session worktree and explains checkout mismatch clearly
- update psi-tool docs and system prompt examples to describe worktree-authoritative reload behavior
- add focused tests for the revised error text and for removal of the temporary attr/guidance

