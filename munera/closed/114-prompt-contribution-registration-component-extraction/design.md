# 114 — Prompt contribution registration component extraction

## Goal

Extract extension-owned prompt contribution registration into a lower component so canonical contribution normalization, register/update/unregister semantics, and contribution query/order helpers no longer live primarily inside `agent-session` prompt handlers.

## Why

Prompt-related ownership is currently split across several different concerns:

- `prompt-assets` owns prompt templates, skills, and system-prompt assembly concerns
- `agent-session` owns prompt lifecycle orchestration and session side effects
- extension-owned prompt contribution registration currently lives inline in `dispatch-handlers/prompt-handlers.clj`

That last slice is registry-shaped and distinct from both prompt assets and turn orchestration.

Recent extractions suggest a clearer decomposition:

- lower registry-like components own canonical registration/query semantics
- higher layers keep orchestration and side effects

Prompt contributions look like the next candidate for that pattern.

## Problem

Current prompt contribution ownership is mixed together inside session handler code:

1. canonical contribution normalization by `ext-path` + `id`
2. register/update/unregister semantics
3. contribution ordering/filtering inputs used to rebuild the effective system prompt
4. system-prompt refresh side effects and session state updates

These do not all belong at the same layer.

The live current implementation seam appears in:

- `components/agent-session/src/psi/agent_session/dispatch_handlers/prompt_handlers.clj`
  - `normalize-prompt-contribution`
  - `merge-prompt-contribution-patch`
  - `:session/register-prompt-contribution`
  - `:session/update-prompt-contribution`
  - `:session/unregister-prompt-contribution`
- `components/agent-session/src/psi/agent_session/mutations/prompts.clj`
  - extension-facing mutation seams

Without an explicit lower owner:

- prompt contribution semantics remain embedded in handler code that also owns session side effects
- the boundary between contribution registry logic and prompt-refresh orchestration stays blurred
- future prompt follow-on work risks mixing contribution registration with broader prompt-assets or turn concerns

## Intent

Create one explicit lower-level component for extension-owned prompt contribution registration semantics while preserving current prompt-refresh orchestration in `agent-session`.

This component should own:

- canonical contribution normalization
- canonical patch/merge semantics for updates
- register/update/unregister semantics over a session's contribution collection
- contribution identity rules (`ext-path` + `id`)
- ordered listing/query helpers that are purely about registered contributions
- explicit result reporting for register/update/unregister operations

This component should not own:

- system-prompt assembly itself
- contribution filtering rules that belong to `system-prompt` composition semantics, except to the extent that pure ordering/query helpers are needed
- prompt lifecycle orchestration
- turn execution
- extension mutation/API surfaces as a whole
- prompt template registration
- general prompt-assets discovery or rendering concerns

## Proposed boundary

### First-cut boundary decision

The first cut should be a **pure component over a session's vector of registered prompt contribution maps**, not a new long-lived runtime registry object.

That means:

- session data remains the owner of stored `:prompt-contributions`
- the new component owns pure collection operations and canonical shaping over that stored vector
- `agent-session` continues to decide when those operations are applied and when system-prompt rebuild side effects follow

This is analogous to the chosen first-cut shape for `112-skill-registration-component-extraction` rather than the extension-registry-backed `tool-registry` extraction.

### Canonical registered contribution shape

The extracted component should preserve the current stored contribution shape as an explicit canonical map rather than preserving arbitrary extra input keys.

The canonical stored contribution shape includes:

- `:id`
- `:ext-path`
- `:section`
- `:content`
- `:priority`
- `:enabled`
- `:created-at`
- `:updated-at`

First-cut normalization should preserve current behavior:

- contribution identity uses the normalized string forms of `:ext-path` and `:id`
- `:ext-path` and `:id` are required contribution identity fields for the registry boundary
- ids and ext-paths are coerced to strings
- no trimming or extra canonicalization beyond that string coercion should be introduced in this task
- content is coerced to string with default empty string
- section is coerced to string when present
- priority defaulting/coercion is preserved
- enabled defaulting is preserved
- timestamps are set on create/update as they are today
- unknown input keys are not part of the first-cut canonical stored contribution shape unless focused live-code review proves an already-established contract that must be preserved

First-cut validation rule:

- because this task is intended to preserve current behavior first, required identity fields should be validated in the smallest way compatible with that goal
- implementation must make explicit whether blank string `:ext-path` or `:id` values are accepted after coercion or rejected at the registry boundary
- preferred first-cut default: reject missing identity fields with structured `ex-info`; only preserve a looser contract if focused live-code review shows current behavior depends on it, and record that explicitly in `implementation.md`

### New component responsibility

A new `prompt-registry` component should own pure contribution-registration semantics for contribution vectors:

- normalize contribution create payloads
- normalize/merge update patches
- register or replace by `ext-path` + `id`
- update by `ext-path` + `id`
- unregister by `ext-path` + `id`
- contribution lookup/listing helpers where they are purely about the registered collection
- explicit result reporting for register/update/unregister operations

Registry API/result contract note:

- the first cut should make the register/update/unregister result contracts explicit in code/tests rather than leaving them implicit
- register should make explicit whether it returns the normalized stored contribution, post-operation count, and replacement-vs-first-registration information in addition to `:registered?`
- update should make explicit whether missing contributions return only `:updated? false` + count or also return lookup detail
- unregister should make explicit whether it returns only `:removed?` + count or also returns removed contribution detail
- lookup helpers should be nil-returning rather than exception-throwing for missing contributions unless focused live-code review reveals an already-established stricter contract

Representative namespace shape:

- `psi.prompt-registry.contributions`

### Responsibilities that should remain outside the new component

#### `prompt-assets`

Should remain the owner of:

- prompt templates
- skills and prompt asset discovery/parsing
- system-prompt assembly semantics
- contribution filtering/application semantics that are conceptually part of composed prompt generation rather than registration

Representative existing owners:

- `components/prompt-assets/src/psi/prompt_assets/system_prompt.clj`
- `components/prompt-assets/src/psi/prompt_assets/prompt_templates.clj`
- `components/prompt-assets/src/psi/prompt_assets/skills.clj`

#### `agent-session`

Should remain the owner of:

- session dispatch/mutation entrypoints
- storing the session's current contribution collection in session data
- system-prompt refresh side effects after changed contributions
- prompt component selection and effective-prompt rebuilding
- higher-level prompt orchestration

Representative existing owners:

- `dispatch_handlers/prompt_handlers.clj`
- `mutations/prompts.clj`

## Main design decisions

### 1. Separate contribution registration from prompt assembly

This extraction should sharpen a specific boundary:

- `prompt-registry` owns contribution registration state semantics
- `prompt-assets.system-prompt` owns how contributions affect final prompt composition
- `agent-session` owns when prompt rebuild happens and how session state/effects are applied

### 2. Keep prompt templates out of this task

Prompt templates are also prompt-related, but they are not the same boundary.

This task should stay focused on extension-owned prompt contributions and should not absorb `:session/register-prompt-template` in the first cut.

### 3. Preserve behavior exactly before considering shape cleanup

The extracted component should preserve current behavior first:

- register by replacing any existing contribution with the same `ext-path` + `id`
- update returns `updated? false` when no matching contribution exists
- unregister returns `removed? false` when no matching contribution exists
- count/reporting remains compatible with the current higher-level mutation surfaces

Replacement/timestamp note:

- implementation must make explicit whether register-as-replacement resets `:created-at` as part of rebuilding the canonical stored contribution or preserves the previous created timestamp
- implementation must also make explicit whether updates always advance `:updated-at` whenever a matching contribution is patched
- because this task is preserving current behavior first, those timestamp semantics should be verified against live code/tests and then frozen intentionally in the extracted component

Patch contract note:

- update patch semantics should be made explicit and preserved in the first cut
- patchable fields should be limited to the current contribution payload fields that the live code already supports changing, such as `:section`, `:content`, `:priority`, and `:enabled`
- identity fields `:ext-path` and `:id` are not patchable through update in this task
- `:created-at` should not be patchable
- unknown patch keys should be ignored rather than silently widening the canonical stored shape, unless focused live-code review proves a different established contract

Count/order note:

- implementation must verify and record the current exact count/reporting behavior in register/update/unregister operations before migration
- implementation must also make explicit which ordering helpers belong to registry semantics versus higher-level prompt composition semantics
- preferred first-cut split: registry owns raw collection lookup and stable collection operations over registered contributions, while prompt-composition-specific filtering/application remains above the boundary
- if any sorted listing helper moves downward, its exact ordering rule must be documented and proven rather than implied

If implementation finds an obvious count/reporting bug in the current handlers, record it explicitly rather than quietly changing behavior.

### 4. Keep system-prompt effects in `agent-session`

The lower component should be pure.

It should return the next contribution collection and any contribution-level result details. `agent-session` should remain responsible for:

- rebuilding the effective prompt
- writing the next session state
- emitting runtime prompt-update effects

## Current likely extraction points

Primary current ownership seam:

- `components/agent-session/src/psi/agent_session/dispatch_handlers/prompt_handlers.clj`

Higher-level mutation seam that should remain above the boundary:

- `components/agent-session/src/psi/agent_session/mutations/prompts.clj`

Likely affected consumer/test surfaces:

- query graph / mutation tests proving register/update/unregister prompt contribution behavior
- prompt lifecycle tests that rely on prompt contribution refresh behavior
- any introspection or resolver tests that inspect stored prompt contributions

## Suggested implementation shape

1. create `components/prompt-registry/`
2. add a small canonical namespace for pure prompt-contribution collection operations
3. move or re-express normalization and patch/merge helpers there
4. add focused component-local tests that prove register/update/unregister semantics directly
   - include explicit proofs for:
     - identity by normalized `ext-path` + `id`
     - replacement behavior on re-register
     - update miss and unregister miss behavior
     - invalid identity-field behavior
     - timestamp semantics preserved from the current implementation
     - patch behavior for supported and ignored keys
5. make prompt handlers delegate contribution collection semantics downward
6. keep prompt-refresh/system-prompt rebuild ownership in `agent-session`
7. keep mutation surfaces and query/read paths stable unless a trivial delegation sharpened ownership naturally

## Acceptance

- a new lower component exists for prompt contribution registration semantics
- the first cut is a pure component over registered prompt-contribution collections rather than a new long-lived stateful runtime registry
- canonical normalize/register/update/unregister behavior for prompt contributions is owned by the new component rather than inline in `agent-session` handlers
- the first-cut contribution contract is explicit and preserved:
  - contribution identity is normalized `ext-path` + `id`
  - register replaces existing contributions by that identity
  - update/unregister miss behavior is explicit and preserved
  - invalid identity-field behavior is explicit and proven
  - patchable vs non-patchable fields are explicit and preserved
  - timestamp semantics are explicit and preserved
  - count/reporting behavior is explicit and preserved
- `agent-session` keeps system-prompt rebuild and prompt-refresh side-effect ownership
- `prompt-assets` keeps system-prompt composition ownership
- prompt template registration remains outside this task
- focused component-local tests cover prompt contribution registration behavior
- existing user-visible prompt contribution behavior remains unchanged
- task `105-agent-session-component-extraction-map` can reference this as a concrete child extraction/refinement of the remaining prompt boundary

## Related work

- `105-agent-session-component-extraction-map` is the umbrella component map
- `112-skill-registration-component-extraction` is the closest analogue for a pure component over a session-local registered collection
- `111-tool-registration-component-extraction` is the parallel extension-registration pattern on the extension-registry-backed side
- this task is intentionally narrower than a broader prompt-assets or turn extraction
