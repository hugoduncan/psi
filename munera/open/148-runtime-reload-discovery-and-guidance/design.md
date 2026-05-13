# 148 — runtime reload discovery and guidance

## Goal

Make `psi-tool` reload behavior reliable and unsurprising when developing psi itself.

## Why

A live reload attempt from this worktree failed because the running ψ instance had loaded source from a different checkout. That exposed a policy question.

For psi self-development, the authoritative target should be the session `worktree-path`, not the checkout the current runtime happened to be started from. If those differ, that is a mismatch to surface clearly, not an alternate reload target to prefer.

## Decision

Adopt worktree-authoritative self-reload semantics.

- session `worktree-path` remains the canonical target for psi self-reload
- runtime source provenance is not a public reload-targeting contract
- if a requested namespace resolves outside the target worktree, reload should fail clearly and explain that the runtime appears to be running from a different checkout
- docs and prompt guidance should reinforce worktree authority and mismatch diagnosis rather than suggesting fallback to runtime source root

## Acceptance

- `:psi.runtime/source-root` is removed from the public graph surface
- reload docs and prompt guidance describe worktree-authoritative reload behavior
- namespace reload mismatch errors explicitly explain that the loaded namespace source is outside the target worktree and that the running runtime may come from a different checkout
- focused proof covers the revised error guidance and the removal of the temporary attr/guidance

