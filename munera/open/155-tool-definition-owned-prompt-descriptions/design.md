# 155 — Tool-definition-owned prompt descriptions

## Goal

Move tool prompt descriptions back onto canonical tool definition maps and extend that tool-definition contract so a tool can provide both prose and lambda descriptions through one uniform mechanism shared by built-in and extension-contributed tools.

## Why

The current system prompt assembly hard-codes built-in tool descriptions in `psi.prompt-assets.system-prompt/tool-description-pairs` instead of reading them from the canonical tool definition maps.

That leaves the prompt surface split across two owners:

- tool identity, schema, and execution metadata live on tool definition maps
- built-in prompt description text lives in system-prompt assembly code
- extension-contributed tools already rely on tool-definition-provided description fields, but only partially and with different behavior from built-ins

This is the wrong ownership shape. The prompt-visible description of a tool is part of the tool’s canonical definition and should travel with that definition regardless of whether the tool is built in or contributed by an extension.

## Problem

Today prompt description behavior is inconsistent:

- built-in tools use hard-coded descriptions in `psi.prompt-assets.system-prompt`
- extension tools use description data carried on the tool definition map
- lambda-mode prompt rendering has a dedicated built-in lookup table, while extension tools only get lambda wording if a separate `:lambda-description` field happens to be present
- system-prompt assembly currently knows specific built-in tool names and their prompt text directly, instead of projecting descriptions from the same canonical source used elsewhere

This creates drift risk:

- changing a built-in tool definition can leave its prompt description behind
- built-in and extension tools do not follow one uniform prompt-description contract
- prompt assembly owns tool-specific content that should belong to the tool surface
- any future built-in tool can accidentally repeat the split by adding prompt text in prompt assembly instead of on the tool definition

## Intent

Create a single uniform description mechanism for tool definitions.

After this task:

- every prompt-visible tool description comes from the tool definition map
- built-in tools and extension-contributed tools follow the same prompt-description contract
- tool definitions can supply prose and lambda descriptions directly
- system-prompt assembly formats tool definitions, but no longer owns built-in tool-specific description text
- prompt rendering behavior is determined by tool-definition data plus a single shared fallback rule

## Current implementation facts to preserve during refinement

Current source inspection shows:

- canonical tool normalization already carries both `:description` and optional `:lambda-description` in `psi.tool-registry.defs/normalize-tool-def`
- extension tool registration already preserves `:lambda-description` when present
- prompt assembly still has a built-in-only override table in `psi.prompt-assets.system-prompt/tool-description-pairs`
- prompt assembly currently renders built-ins by tool name lookup and renders extension tools from passed maps
- the current system-prompt tests already prove one desired fallback rule for extension tools: lambda mode falls back to prose when `:lambda-description` is absent
- session state currently stores `:tool-defs` as `[:vector :map]` rather than a stronger explicit tool schema, so the canonical description contract is real in normalization but not yet fully enforced at the stored-session schema boundary

This task should refine the ownership and projection path first, and only strengthen schemas where needed to make that ownership coherent.

## In scope

- defining the canonical prompt-description contract on tool definition maps
- deciding the exact field names and fallback rules for prose and lambda descriptions
- making the built-in tool definition owner authoritative for prompt descriptions
- migrating built-in tool definitions so their prompt descriptions live on tool definition maps
- updating system-prompt assembly to read descriptions from tool definitions instead of from hard-coded built-in tables
- ensuring extension-contributed tools use the same description projection path as built-ins
- tightening tool-definition normalization and projection tests to prove the uniform contract
- updating prompt-assembly tests to prove identical built-in vs extension behavior under the same rules
- updating docs or task notes needed to reflect the canonical ownership change

## Out of scope

- redesigning tool execution semantics
- redesigning tool registration as a whole beyond the minimum needed for uniform description ownership
- changing provider-facing tool payload contracts, except where canonical tool-definition shaping must continue to preserve the same existing provider behavior
- broad prompt-assembly redesign unrelated to tool description ownership
- inventing a richer prompt copy DSL for tools

## Preferred target shape

Preferred final shape:

- canonical tool definition maps carry prompt description data
- the canonical tool-definition contract supports at least:
  - `:description` for prose-mode prompt rendering
  - `:lambda-description` for lambda-mode prompt rendering
- system-prompt assembly renders tool definitions directly and chooses the appropriate description by mode
- built-in tools and extension tools both use the same rendering path
- there is no built-in-only description lookup table in `psi.prompt-assets.system-prompt`

### Preferred contract shape

Prefer the existing explicit flat fields rather than introducing a nested prompt-description map:

- `:description` — canonical prose description
- `:lambda-description` — optional lambda-mode description override

Reasons to prefer this shape:

- it already exists in the canonical normalization path
- extension registration already preserves it
- tests already refer to it
- it is the smallest change that restores correct ownership without broadening into a schema redesign
- it keeps provider-facing `:description` behavior unchanged while adding an agent-prompt-specific lambda override

Avoid replacing this with a nested map such as `:prompt-description {:prose ... :lambda ...}` unless implementation uncovers a concrete conflict that cannot be solved cleanly with the existing flat fields. A nested map would broaden the slice and force more migration churn for little gain.

### Required fallback rule

Use one uniform fallback rule for all tools:

- prose mode renders `:description`
- lambda mode renders `:lambda-description` when present and non-blank
- otherwise lambda mode falls back to `:description`

This fallback must apply identically to:

- built-in tools
- extension-contributed tools
- any future built-in registration path that contributes tool definitions

No built-in-only fallback table or name-based override is allowed in the final state.

## Ownership decision

Tool prompt descriptions belong to the canonical tool definition owner, not to prompt assembly.

That means:

- `psi.tool-registry.defs` owns the canonical shape and normalization rules for tool descriptions
- the authoritative built-in tool definition owner must supply built-in `:description` and `:lambda-description` values on its tool maps
- extension-contributed tool maps must continue to supply those same fields through the same canonical normalization path
- `psi.prompt-assets.system-prompt` may select which description to render for a given mode, but it must not own tool-specific wording for individual built-ins

## Built-in owner requirement

Before implementation changes prompt assembly, it must identify the authoritative built-in tool definition owner(s) for at least:

- `read`
- `bash`
- `edit`
- `write`
- `psi-tool`

The moved description text should live with those canonical built-in tool definition maps, not in a second prompt-only built-in table.

If the current built-in tool definitions are assembled from multiple owners, the task must record which owner is authoritative for each built-in tool and why that placement is the stable long-term home for the prompt description.

### Settled built-in owner map

Source inspection settled the authoritative built-in owners as follows:

- `read` → `components/agent-session/src/psi/agent_session/tools.clj` `read-tool`
  - rationale: this map already owns the built-in tool's canonical identity, label, prose description, parameters, and request-format metadata; registration and cwd-scoped wrappers project from it rather than defining a separate prompt surface elsewhere.
- `bash` → `components/agent-session/src/psi/agent_session/tools.clj` `bash-tool`
  - rationale: same ownership shape as `read`; the canonical built-in definition already lives here and downstream registration reads from this map.
- `edit` → `components/agent-session/src/psi/agent_session/tools.clj` `edit-tool`
  - rationale: same canonical built-in definition owner used by the built-in registration path and scoped wrappers.
- `write` → `components/agent-session/src/psi/agent_session/tools.clj` `write-tool`
  - rationale: same canonical built-in definition owner used by the built-in registration path and scoped wrappers.
- `psi-tool` → `components/agent-session/src/psi/agent_session/psi_tool.clj` `psi-tool`
  - rationale: this namespace owns the canonical psi-tool contract map and its action surface; `psi.agent-session.tools` only re-exports it as an alias and is not the authoritative owner.

## Prompt assembly requirement

Prompt assembly should stop operating on a split model of:

- built-in tool names
- extension tool description maps

and instead operate on one canonical model:

- normalized tool definition maps

That means implementation must inspect all prompt-rendering call paths that currently pass only tool names and decide the smallest clean change that lets prompt assembly render from tool definitions rather than from a built-in name table.

The preferred outcome is:

- system-prompt assembly receives tool definition maps
- filtering/selection still happens by tool name where appropriate
- rendering happens from the selected normalized tool definition maps

## Schema and projection guidance

This task does not require a full schema redesign, but it should explicitly review these boundaries:

- `psi.tool-registry.defs/normalize-tool-def`
- any tool registry storage shapes that preserve canonical tool defs
- any agent-session/session-state storage shapes carrying `:tool-defs`
- any introspection or query projections that expose tool definitions

Preferred first-cut rule:

- keep provider-facing projections unchanged where possible
- ensure canonical normalized tool defs preserve both `:description` and optional `:lambda-description`
- strengthen explicit schema/projection surfaces only where the lack of a declared tool schema would leave the new ownership ambiguous or untested

If implementation chooses to strengthen the stored tool schema, it should do so narrowly and in the same field shape rather than using this task to redesign the whole session-state model.

## Task artifact roles

For this task, review and follow-up surfaces are explicit:

- `implementation.md` is the append-only review, decision, and discovery log used during refinement and implementation
- `design-steps.md` is the actionable ambiguity follow-up surface created by ambiguity review passes
- `steps.md` remains the implementation execution checklist and must not be used to hide ambiguity-review follow-up work

Any ambiguity review that adds actionable design follow-up must record terse notes in `implementation.md` and add concrete unchecked items to `design-steps.md`.

## Design constraints

- keep canonical ownership with the tool definition map, not with prompt assembly
- prefer the smallest contract change that makes ownership unambiguous
- do not keep a permanent split where built-in tools are special-cased in prompt assembly code
- preserve the existing distinction between prompt assembly and tool registration/execution ownership
- keep prompt rendering deterministic
- preserve current user-visible wording where practical; the main goal is ownership convergence, not copy rewriting
- do not introduce a second parallel description contract for built-ins

## Required inventory before mechanism choice

Before finalizing the implementation shape, inspect and classify the current description surfaces:

- built-in tool definition owner(s)
- extension tool definition owner(s)
- canonical normalization points for tool defs
- prompt-rendering call paths that currently operate on names vs full tool maps
- existing tests for built-in descriptions
- existing tests for extension `:lambda-description` behavior
- any query/introspection or compaction surfaces that preserve tool-def data

The mechanism choice should be based on that inventory rather than guessed from only the main system-prompt function.

## Key design questions

1. Which namespace(s) are the authoritative built-in tool definition owners for the five current built-ins?
2. Can prompt assembly be switched to definition-driven rendering without broadening beyond a small function-signature adjustment?
3. Is the existing `:description` + optional `:lambda-description` contract already sufficient once ownership is corrected?
4. Should the stored `:tool-defs` schema be tightened in this task, or is test-backed normalization sufficient for this slice?
5. Are any current projections dropping `:lambda-description` in a way that would hide the canonical contract from relevant runtime surfaces?
6. Which focused proofs best demonstrate that built-in and extension tools now render identically under the same rules?

## Acceptance

- a focused task exists for removing hard-coded built-in tool prompt descriptions from system-prompt assembly
- the task states that prompt descriptions belong on canonical tool definition maps
- the task explicitly prefers the existing flat contract:
  - `:description`
  - optional `:lambda-description`
- the task requires the uniform fallback rule:
  - prose → `:description`
  - lambda → `:lambda-description` or fallback to `:description`
- the task requires one shared rendering path for built-in and extension-contributed tools
- the task requires prompt assembly to render from normalized tool definition maps rather than from a built-in name table
- the task requires identifying the authoritative built-in tool definition owner(s) that will receive the moved descriptions
- scope boundaries keep this slice focused on description ownership and projection, not a broader tool-runtime redesign
