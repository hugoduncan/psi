Approach:
- Finish the remaining prompt-path and persisted-header compatibility removals.
- Strengthen proof around canonical-only contracts before deleting any final branches.
- End with full verification and state update.

Likely steps:
1. converge remaining shared-session prompt semantics into request preparation
2. finish persisted header shape collapse around `:worktree-path`
3. strengthen canonical-only RPC tests where still partial
4. run full verification commands
5. update state/docs as needed

Risks:
- deleting a compatibility path still used by an untested caller
- conflating prompt convergence with unrelated runtime behavior changes
