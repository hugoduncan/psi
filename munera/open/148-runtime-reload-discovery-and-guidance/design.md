# 148 — runtime reload discovery and guidance

## Goal

Make it easy for ψ to discover the correct reload target for the running runtime and to use `psi-tool` reload surfaces reliably without trial and error.

## Why

A live reload attempt from this worktree failed because the running ψ instance had loaded source from a different checkout. The current reload API is explicit, but the discoverability of the correct `worktree-path` is weak: session worktree and runtime source root can diverge.

## Acceptance

- a root-queryable runtime attr exposes the effective source root/worktree to use for code reloads when running from source
- docs and prompt guidance show the discovery-first reload workflow
- focused proof covers the new attr and guidance text

