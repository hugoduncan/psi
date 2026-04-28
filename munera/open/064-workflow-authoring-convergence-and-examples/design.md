# 064 — Workflow authoring convergence and examples

## Goal

Converge docs, examples, and proving workflows on the new session-first workflow authoring model.

## Context

Task 059 is the umbrella. This task extracts Phase 5 so the final authoring story is coherent and reflected in real examples.

## Scope

In scope:
- update workflow docs/examples
- converge docs/examples on the settled authoring surface from tasks `060`–`063`:
  - multi-step steps use author-facing `:name`
  - source selection uses `{:from ...}` under `:session :input` / `:session :reference`
  - structured extraction uses `:projection ...`
  - session-shaping overrides are peer keys in `:session`
  - preloaded message/transcript context uses `:session :preload`
- revisit modular GitHub workflow examples such as `gh-bug-triage-modular`
- decide the long-term role of any prompt-binding convenience relative to `:session`
- add any final validation/error-shaping needed for author clarity

Out of scope:
- inventing new runtime capabilities unrelated to convergence/documentation/example adoption

## Convergence targets

Task `064` should ensure the final authoring story is described consistently across docs, examples, and proving workflows.

That consistency should include at least:
- `{:step "<step-name>" ...}` always refers to step `:name`, not delegated `:workflow` name
- `$INPUT` remains owned by `:session :input`
- `$ORIGINAL` remains owned by `:session :reference`
- `:session :preload` shapes child-session context but does not implicitly populate `$INPUT` or `$ORIGINAL`
- prompt-binding convenience, if retained, is documented as subordinate to the `:session`-first model rather than as a competing primary abstraction

## Acceptance

- [ ] Docs/examples describe the session-first authoring model clearly and consistently with tasks `060`–`063`
- [ ] At least one modular workflow example demonstrates the new surface well
- [ ] Any retained prompt-binding convenience is documented with a clear subordinate role relative to `:session`
- [ ] Final validation/error shaping needed for author clarity is landed
