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

## Direct mutation seams

### `:session/set-active-tools`

Already identified. This handler becomes authority-first: normalize incoming tool maps → derive `:tool-ids` → persist `:tool-ids` → derive/persist compatibility `:active-tools` and `:tool-defs`.

### `:session/add-tool`

This handler (`session_mutations.clj:511`) is a direct tool-authority mutation seam. Currently it appends a tool to the runtime tool set via `:runtime/agent-set-tools` effect but never updates session `:tool-defs`, `:active-tools`, or (future) `:tool-ids`. In this slice, `:session/add-tool` must also derive and persist `:tool-ids` (and derived `:tool-defs`/`:active-tools`) so that adding a tool through this path does not bypass the new authority field. The handler must update session state with the new tool's name appended to `:tool-ids`, then derive `:tool-defs` and `:active-tools` from the updated `:tool-ids`.

## Authority-feeding seams

Two functions merge runtime+registry tools and dispatch `:session/set-active-tools`, making them indirect but critical authority-feeding paths:

- `refresh-active-tools-in!` in `extension_runtime.clj:112` — called after manifest extension activation to refresh the session tool set.
- `refresh-active-tools!` in `workflow/bootstrap.clj:28` — called during workflow bootstrap to refresh the session tool set.

Both already flow through `:session/set-active-tools`, so once that handler is authority-first, these paths inherit correct authority behavior. They do not need separate authority logic, but they must be named as seams requiring verification during implementation to confirm they produce correct `:tool-ids` after the handler migration.

## `:active-tools` persistence status

`:active-tools` is **ephemeral runtime state**, not a persisted schema field:

- It does not appear in `agent-session-schema` in `session_state/model.clj`.
- It is not included in the `select-keys` baseline field-copy sets for any lifecycle path (new-session, resume-session, fork-session) in `session_state/init.clj`.
- It is only written into the session map by the `:session/set-active-tools` handler at runtime.
- After session resume, `:active-tools` will be nil until the next `:session/set-active-tools` dispatch.

This slice does **not** add `:active-tools` to the session schema. It remains ephemeral and derived. The `:turn/active-tools` field in `prompt_request.clj` (line 279) reads from `:active-tools` and may be nil after resume; this is acceptable because tool bootstrap on resume will dispatch `:session/set-active-tools` before the first prompt, re-populating the derived field. This behavior should be documented but does not require a schema change.

## Session lifecycle paths requiring `:tool-ids`

Three lifecycle paths in `session_state/init.clj` copy specific field sets from baseline session data into new session state. All three must include `:tool-ids` in their `select-keys` sets:

1. **`initialize-new-session-state`** — copies from current session data into a new root session. Currently copies `:tool-defs` but not `:tool-ids`.
2. **`initialize-resumed-session-state`** — copies from current session data into a resumed session. Currently copies `:tool-defs` but not `:tool-ids`.
3. **`initialize-forked-session-state`** — copies from parent session data into a forked session. Currently copies `:tool-defs` but not `:tool-ids`.

All three must add `:tool-ids` to their `select-keys` baseline so that authority survives lifecycle transitions. The `model/initial-session` default must also include `:tool-ids []` so the field is always present.

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
