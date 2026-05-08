# 112 — Skill registration component extraction

## Goal

Extract session-local skill registration into a lower component so registered-skill ownership, validation, lookup, and deduplication no longer live primarily inside `agent-session` dispatch handlers.

## Why

Recent tool work clarified a useful pattern:

- `tool-registry` owns registered tool definition state semantics
- `tool-runtime` owns execution semantics
- higher layers such as `agent-session` keep orchestration and side effects

Skills currently have a similar split, but only partially:

- `components/prompt-assets/src/psi/prompt_assets/skills.clj` already owns skill discovery, parsing, validation, and invocation helpers
- `agent-session` still owns session-local registration behavior via `:session/register-skill`
- session-local skill deduplication currently happens inline in orchestration code rather than through a lower authoritative owner

A lower skill-registration owner would make this boundary clearer and align it with the tool registration shape that just landed.

## Problem

Skill-related ownership is currently mixed across layers:

- prompt-asset concerns live in `prompt-assets`
- session orchestration concerns live in `agent-session`
- but registry-style concerns such as register-by-name, deduplication, ordered listing, and skill lookup remain embedded in `agent-session`

This creates several costs:

- session mutation handlers own domain logic that should likely be lower
- session `:skills` is treated as a raw vector rather than through a canonical registry surface
- downstream consumers depend on ad hoc registered-skill semantics
- the emerging component map from `105` lacks the same registry split for skills that now exists for tools

## Intent

Create a lower component that owns registered-skill semantics while preserving existing prompt-asset and session-orchestration responsibilities.

This task should:

- define the extracted skill-registration component boundary
- move canonical register/get/list/dedup semantics into that component
- keep `prompt-assets.skills` as the owner of skill file parsing, discovery, validation, and invocation helpers
- keep `agent-session` as the owner of session orchestration side effects such as refreshing the system prompt after a changed skill set
- leave user-visible skill behavior unchanged

This task should not:

- redesign how `/skill:name` expansion works
- move skill file discovery/parsing into the new component
- redesign system-prompt composition
- broaden into a full prompt-assets re-extraction

## Proposed boundary

### First-cut boundary decision

The first cut should be a **pure component over vectors of registered skill maps**, not a long-lived stateful registry object.

That means:

- session data remains the owner of stored `:skills`
- the new component owns pure collection operations over that stored skill vector
- `agent-session` continues to decide when those operations are applied and when side effects follow

This uses the `tool-registry` extraction as a conceptual analogue, but not as a requirement to reproduce the same stateful architecture.

### Canonical registered skill shape

For this extraction, a registered skill is a map with at least:

- `:name` — required string key used for identity and lookup

The first cut should preserve the existing stored shape of session skills and accept already-constructed skill maps from current callers.

Expected commonly present fields include:

- `:description`
- `:file-path`
- `:base-dir`
- `:source`
- `:disable-model-invocation`
- `:lambda-description`

But this task should not force a broader canonicalization pass unless a small, low-risk shape-preserving helper clearly simplifies the extraction.

Extension- or runtime-provided skills may therefore remain partial maps, so long as they satisfy the first-cut registration contract.

### New component responsibility

A new `skill-registry` component should own pure registry-style semantics for already-constructed skill maps:

- minimal skill registration validation
- registration by name
- duplicate handling according to the chosen first-cut policy
- ordered listing
- lookup by name
- helper queries such as skill names and counts

Representative namespace shape:

- `psi.skill-registry.registry`
- possibly `psi.skill-registry.defs` only if a separate canonical skill-map normalization layer proves necessary

### Responsibilities that should remain outside the new component

#### `prompt-assets`

Should remain the owner of:

- `SKILL.md` parsing
- frontmatter validation
- filesystem discovery
- progressive disclosure rules
- skill invocation expansion helpers
- prompt-facing enrichment utilities such as full-content loading, prompt disclosure, and other prompt-oriented shaping
- prompt-facing helpers that are about discovered-skill meaning rather than registered-skill collection semantics

Representative existing owner:

- `components/prompt-assets/src/psi/prompt_assets/skills.clj`

Likely retained helpers include:

- discovery/parsing/validation helpers
- invocation expansion helpers
- prompt-facing enrichment helpers

#### `agent-session`

Should remain the owner of:

- session dispatch/mutation entrypoints
- storing the session's current registered skill collection in session data
- side effects triggered by changed session skills
- orchestration decisions such as when to refresh the system prompt
- read-path aggregation where commands/resolvers are still acting as higher-level session entrypoints

Representative existing owners:

- `mutations/prompts.clj`
- `dispatch_handlers/session_mutations.clj`
- selected session/runtime consumers

Read-path note:

- this task should primarily extract write-path/registration ownership
- trivial read-path delegation to the new component is in scope where it clarifies ownership
- broader command/resolver cleanup is not required unless it falls out naturally from the extraction

## Current likely extraction points

The current inline registration seam appears at:

- mutation entrypoint:
  - `components/agent-session/src/psi/agent_session/mutations/prompts.clj`
- dispatch handler:
  - `components/agent-session/src/psi/agent_session/dispatch_handlers/session_mutations.clj`
    - `:session/register-skill`

Current lower skill-domain owner already exists at:

- `components/prompt-assets/src/psi/prompt_assets/skills.clj`

Current consumers of registered session skills include:

- request preparation / skill invocation expansion
- system-prompt filtering and child-session shaping
- workflow/session shaping surfaces that carry `:skills`
- discovery resolvers and commands that list or inspect skills

## Main design decisions

The first cut should settle the registry contract explicitly rather than leaving it implicit.

### Duplicate/update policy

The initial canonical policy should preserve current behavior:

- registration adds a skill only when its `:name` is not already present
- duplicate registration of an existing skill name is ignored
- duplicate registration does not replace, merge, or reorder the existing entry
- the registration API should report `:added?` and `:changed?` so `agent-session` can keep prompt-refresh orchestration unchanged

This keeps the extraction behaviorally neutral and leaves replacement semantics as a possible later follow-on if real pressure appears.

### Ordering policy

The initial canonical ordering policy should be:

- registered skills preserve first-registration order
- ignored duplicate registration leaves ordering unchanged
- list operations reflect stored session order

### Validation policy

The first cut should enforce only minimal registration validation at the registry boundary:

- `:name` must be present
- `:name` must be a non-blank string

Discovery-specific validation rules from `prompt-assets.skills` should remain discovery-owned and should not be broadened into the registry component in this task.

This keeps one coherent rule:

- discovered skills may be held to stricter naming/content expectations
- the registry component accepts already-constructed runtime skill maps so long as they satisfy the minimal registration contract

### Lookup/query ownership

The first cut should move only clearly registry-shaped query helpers into the new component, such as:

- lookup by name
- ordered listing
- skill names
- count
- result reporting that includes `:added?` and `:changed?` for registration operations

Prompt-facing enrichment, summary, and invocation-oriented helpers should remain in `prompt-assets.skills` unless they are obviously just collection queries over registered skills.

The current `:session/register-skill` behavior appears to be:

- add only if the skill `:name` is not already present
- preserve existing order
- refresh the system prompt only when the session skill set changes

The new component should preserve that behavior as the canonical initial policy.

## Suggested implementation shape

A first-cut extraction should be narrow and analogous to the landed tool registration split:

1. create `components/skill-registry/`
2. add a small canonical registry namespace for pure registered-skill operations
3. move or re-express the current dedup/register-by-name logic there
4. add focused component-local tests that prove the registry contract directly
5. make the `:session/register-skill` handler delegate to the new lower component
6. keep session state shape stable unless a stronger normalization change is clearly beneficial
7. keep higher-level `agent-session` tests for orchestration side effects such as prompt refresh
8. only clean up read-path helpers where the ownership shift is obvious and low-risk

## Acceptance

- a new lower component exists for skill registration semantics
- the first cut is a pure component over registered skill collections rather than a new long-lived stateful runtime registry
- canonical register/get/list/dedup behavior for session skills is owned by the new component rather than inline in `agent-session`
- the first-cut registration policy is explicit and preserved:
  - add only when `:name` is absent
  - ignore duplicates
  - preserve first-registration order
  - report `:added?` and `:changed?`
- `prompt-assets.skills` remains the owner of skill discovery/parsing/invocation concerns and those concerns do not migrate into the new component
- `agent-session` keeps orchestration ownership and any prompt-refresh side effects
- focused component-local tests cover the registry behavior
- existing user-visible skill behavior remains unchanged
- task `105-agent-session-component-extraction-map` can reference this as a concrete child extraction/refinement of the prompt/skills boundary

## Related work

- `105-agent-session-component-extraction-map` is the umbrella component map
- recent tool registration extraction provides the closest boundary analogue
- `components/tool-registry/` is the reference shape to compare against
- this task is intentionally narrower than a broader prompt-assets or turn extraction
