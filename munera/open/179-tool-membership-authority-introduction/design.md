# 179 tool membership authority introduction

## Intent

Create the first implementation follow-on from task `178-registry-session-membership-unification` by introducing an authoritative session-owned tool membership or selection surface and beginning the architectural split between canonical tool definitions and derived session tool payloads.

## Problem

After tasks `168` and `178`, canonical tool definitions are registry-owned, but sessions still treat `:tool-defs` as the effective persisted tool surface. That leaves tools as the main capability-domain outlier relative to the emerging project rule:

- registries own canonical definitions
- sessions own membership or selection
- execution derives effective payloads from canonical definitions plus session membership or selection

Today there is no authoritative session field equivalent to `:skill-ids` or `:prompt-contribution-ids` for tools. As a result:

- lifecycle code persists concrete tool maps where it should ideally persist tool membership or selection
- workflow child-session shaping narrows concrete tool payloads directly
- prompt/system-prompt rebuild paths can treat embedded session `:tool-defs` as if they were authoritative
- future registry/session unification remains blocked on tools

## Scope

This task covers the first slice of tool-session convergence only.

It should:

- introduce the authoritative session field for tool membership or selection
- define the canonical derivation rule from that field to `:tool-defs`
- align direct session mutation/update seams so tool authority updates happen at the membership/selection layer first and `:tool-defs` becomes derived state second
- document any temporary compatibility behavior required while downstream lifecycle/workflow seams still consume persisted `:tool-defs`

It should not yet fully migrate every lifecycle and workflow seam. Those broader inheritance/narrowing changes belong to the later follow-on described by task `178` as follow-on B.

## Authority selected in this slice

This task selects `:tool-ids` as the authoritative persisted session tool field.

- `:tool-ids` is an ordered vector of exact tool names.
- The shape is intentionally membership-only, not a richer selection map.
- The current direct authority seam (`:session/set-active-tools`) already reduces incoming tool maps to exact tool names before persisting session state.
- Current prompt/workflow narrowing semantics are allowlist-style exact-name filtering over concrete tool payloads, not per-tool persisted configuration, so they do not require a richer session-owned authority shape yet.
- Broader workflow child-session narrowing remains deferred, but this slice still requires every authority-setting path to end by persisting `:tool-ids` so there is only one authoritative session surface.

## Derived compatibility fields

After this task, session tool authority is singular:

- `:tool-ids` is authoritative.
- `:tool-defs` is a derived execution/compatibility payload.
- `:active-tools` is not authoritative and is retained only as a derived compatibility projection for seams that still expect active membership as a set.

The derivation rule is:

1. Start from canonical tool-registry definitions.
2. Read session `:tool-ids` in order.
3. Materialize session `:tool-defs` by resolving each id against the canonical registry and preserving `:tool-ids` order.
4. Materialize compatibility `:active-tools` as the set view of those same ids.

If a temporary compatibility input still arrives as concrete `:tool-defs`, that input must be normalized immediately into `:tool-ids`; it must not remain an alternate persisted authority path.

## Interim child-session compatibility rule

This slice does not yet replace the workflow child-session/public contract fields that still accept `:tool-defs`, but it does define how they interact with the new authority field.

- When child-session creation receives no explicit tool override, child authority inherits the parent session `:tool-ids` first, and child `:tool-defs` is then derived from those inherited ids.
- When child/session compatibility inputs provide explicit `:tool-defs`, those tool defs must be normalized into child `:tool-ids` immediately; the concrete `:tool-defs` payload may still be persisted for compatibility, but only as a projection derived from the same chosen tool names.
- Prompt-component selection or workflow step tool filtering may still operate over the derived parent/child `:tool-defs` payload in this slice, but those filters do not create a second authority source. Any resulting child availability must still be representable as child `:tool-ids`.

## Desired outcome

After this task:

- sessions have an explicitly named authoritative tool membership or selection field
- `:tool-defs` is no longer described or treated as the authoritative session surface
- there is one documented derivation path from canonical tool registry definitions plus session tool membership/selection to session `:tool-defs`
- direct mutation and session-shaping seams that add or replace tool availability use the authoritative membership/selection field first, then derive `:tool-defs`
- any remaining persisted `:tool-defs` state is clearly classified as derived execution or compatibility payload

## Key design question

This task must decide the concrete authoritative session shape for tools.

Options include:

- `:tool-ids` as a plain ordered vector of tool names
- a richer tool-selection structure if the current system needs more than exact tool-name membership

The design should prefer the smallest authoritative shape that can still express the current child-session/workflow narrowing semantics without forcing concrete tool definition maps back into session authority.

## Constraints

- Do not reintroduce session-owned canonical tool definitions.
- Preserve the current root-backed tool-registry as the canonical tool definition owner.
- Keep `:tool-defs` available as derived execution/compatibility payload where current runtime seams still require it.
- Do not mix the later lifecycle/workflow inheritance migration into this task unless needed for coherence at the direct mutation/session-authority seam.
- Keep the session field naming and semantics explicit enough that later follow-on tasks can narrow tools by membership/selection first and materialize `:tool-defs` second.

## Acceptance criteria

- `design.md` identifies the new authoritative session tool membership/selection field and explains why that shape is sufficient.
- `design.md` specifies the derivation rule from canonical tool registry definitions plus tool membership/selection to `:tool-defs`.
- `design.md` identifies the direct mutation/session update seams that must switch from treating `:tool-defs` as authority to treating it as derived state.
- `design.md` explicitly classifies `:tool-defs` as derived execution/compatibility payload after this task.
- `design.md` clearly defers broader bootstrap/new/resume/fork/child-session inheritance and workflow narrowing migration to the next follow-on slice unless a minimal coherence change is required here.
- `design.md` leaves a precise enough contract that the next lifecycle-focused task can build on it without re-deciding tool authority.

## Relationship to prior tasks

This task is the direct implementation follow-on to task `178` follow-on A.

Relevant background:

- `164` registry semantics audit
- `165` root-registry target architecture
- `168` tool-registry root-registry migration
- `178` registry/session membership unification

## Notes

This task should stay narrowly focused on introducing tool membership authority and the derivation contract. It should not become a vague umbrella for all remaining tool lifecycle cleanup.
