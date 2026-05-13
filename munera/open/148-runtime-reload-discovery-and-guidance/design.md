# 148 — runtime reload discovery and guidance

## Goal

Make `psi-tool` reload behavior reliable and unsurprising when developing psi itself.

## Why

A live reload attempt from this worktree failed because the running ψ instance had loaded source from a different checkout. That exposed an implementation gap, not a policy reason to reject reload.

For psi self-development, the authoritative target should be the session `worktree-path`, and psi should reload the running process from source files in that worktree even when the currently loaded namespace source path points elsewhere. Path mismatch is still useful diagnostic information, but it should be surfaced as a warning rather than used to block reload.

## Decision

Adopt worktree-authoritative self-reload execution semantics.

- session `worktree-path` remains the canonical target for psi self-reload
- runtime source provenance is not a public reload-targeting contract
- namespace reload resolves the target namespace source file from the requested/session worktree and loads that file into the running process
- when the previously loaded namespace source path differs from the target worktree source path, reload surfaces a warning describing the mismatch and both paths
- docs and prompt guidance should reinforce worktree authority and mismatch diagnosis rather than suggesting fallback to runtime source root or restart as the normal fix

## Acceptance

- `:psi.runtime/source-root` is removed from the public graph surface
- reload docs and prompt guidance describe worktree-authoritative reload behavior
- namespace reload from a mismatched loaded-source path succeeds when the namespace source can be resolved under the target worktree
- reload output includes a warning describing loaded-source-path vs target-source-path mismatch when they differ
- focused proof covers target-source resolution, mismatch warning behavior, and the removal of the temporary attr/guidance

