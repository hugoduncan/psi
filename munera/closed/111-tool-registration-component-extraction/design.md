# 111 — Tool registration component extraction

## Goal

Extract the canonical tool-definition and extension tool-registration slice out of `components/agent-session/` into its own lower component, so the authoritative owner of tool definition normalization, tool-name validation, extension tool registration, canonical projection helpers, and registered-tool queries no longer lives inside the residual `agent-session` orchestration layer.

## Why

Task `105-agent-session-component-extraction-map` identified tool-related ownership as a coherent extraction area, and task `104-tool-runtime-component-extraction` already extracted lower-level execution mechanics into `components/tool-runtime/`.

That leaves a distinct remaining tool-shaped boundary around registration and catalog ownership:

- canonical tool definition normalization currently lives in `psi.agent-session.tool-defs`
- canonical tool-name validation and extension tool registration currently live in `psi.agent-session.extensions`
- extension-facing registration entrypoints currently route through `psi.agent-session.mutations.extensions` and `psi.agent-session.extensions.api`
- multiple higher-level consumers project those registered or session-stored tool definitions into session runtime, workflow step shaping, prompt requests, and provider-facing conversation payloads

Without an explicit extracted owner, `agent-session` still mixes:

- tool runtime/execution ownership that has already moved downward
- tool definition/catalog ownership that still remains local
- extension registration orchestration and session composition seams

This keeps a clean tool boundary from emerging and leaves `tool-defs.clj` split between prompt/runtime/provider/extension concerns under a historical placement rather than a deliberate component.

## Problem

Current ownership is split across at least three concerns:

1. canonical tool-definition shape and normalization
2. extension tool registration and registry storage
3. higher-level session/extension mutation and API entrypoints that invoke registration

The main current implementation surfaces are:

- `components/agent-session/src/psi/agent_session/tool_defs.clj`
- `components/agent-session/src/psi/agent_session/extensions.clj` (`valid-tool-name?`, `register-tool-in!`, registered-tool queries)
- `components/agent-session/src/psi/agent_session/mutations/extensions.clj` (`psi.extension/register-tool`)
- `components/agent-session/src/psi/agent_session/extensions/api.clj` (`:register-tool`)

The live consumer gravity confirms that tool-definition ownership is real and shared, not prompt-local and not execution-local:

- `session_runtime.clj` projects canonical tool defs into agent-core runtime tool lists
- `dispatch_effects.clj` projects canonical tool defs into live agent state
- `conversation.clj` projects canonical tool defs into provider-facing request payloads
- `workflow_step_prep.clj` normalizes workflow-declared tool defs into canonical session-facing tool defs
- `dispatch_handlers/session_mutations.clj` and `dispatch_handlers/scheduler.clj` normalize incoming tool maps for session mutation flows

Without an explicit extracted component, these responsibilities remain blurred:

- `agent-session` appears to own canonical tool-definition modeling
- extension registration logic sits in a broader extensions namespace rather than a tool-specific owner
- future tool follow-on work risks mixing registration/catalog concerns back together with runtime execution concerns already extracted by `104`

## Intent

Create one explicit lower-level component for tool registration and canonical tool-definition ownership.

This component should own:

- canonical tool-definition shape
- parameter parsing / normalization for canonical tool defs
- canonical projection helpers for agent-core and provider-facing tool payloads
- canonical tool-name validation rules used by extension tool registration
- extension tool registration into extension-registry state
- registered-tool listing/query helpers tied to extension-registry ownership

This component should not own:

- tool execution/runtime mechanics already extracted to `components/tool-runtime/`
- prompt lifecycle orchestration
- turn orchestration
- session dispatch orchestration
- post-tool processor registration/ownership
- tool output telemetry/storage policy
- UI tool renderer registration
- extension API surface as a whole
- extension command/flag/shortcut/handler registration unrelated to tools

## Boundary

This task is intentionally narrower than a general “tool component” extraction.

### In scope for extraction

Authoritative first-cut ownership should move below `agent-session` for:

- current `psi.agent-session.tool-defs`
- the tool-name validation helper currently in `psi.agent-session.extensions`
- the extension-tool registration helper currently in `psi.agent-session.extensions`
- registered-tool lookup/listing helpers currently in `psi.agent-session.extensions` where they are specifically about tool defs rather than the broader extension registry surface

Expected first-cut extracted namespace family:

- `psi.tool-registry.defs`
- `psi.tool-registry.registry`

Expected component path:

- `components/tool-registry/`

### Expected higher-level seams that remain above the boundary

These should remain outside the extracted component in the first cut:

- `psi.agent-session.mutations.extensions/register-tool`
- `psi.agent-session.extensions.api` `:register-tool`
- session bootstrap / session mutation flows that choose the active session tool set
- child-session shaping that chooses inherited or filtered `:tool-defs`
- workflow/session authoring flows that choose which tool defs apply to a child or workflow step

Boundary rule:

- the extracted component owns registered tool definitions and their canonical shape
- `agent-session` continues to own session policy for which tool defs are active in a given session

That distinction keeps registration/catalog ownership separate from session configuration policy.

## Proposed target shape

Chosen first-cut target:

- component path: `components/tool-registry/`
- authoritative namespace family: `psi.tool-registry.*`

Naming decision for this task:

- keep `tool-registry` as the component name for this first cut
- although canonical tool defs are consumed outside pure extension-registration flows, the extraction center of gravity is still registered-tool ownership plus canonical tool-def modeling
- renaming to something broader such as `tool-catalog` is explicitly out of scope for this slice unless implementation finds a concrete blocker

Proposed first-cut authoritative namespaces:

- `psi.tool-registry.defs`
  - canonical tool definition normalization
  - canonical tool parameter parsing
  - agent-core projection helpers
  - provider-facing projection helpers

- `psi.tool-registry.registry`
  - canonical tool-name validation
  - extension tool registration into registry state
  - registered-tool listing/query helpers

First-cut registry-shape decision:

- `psi.tool-registry.*` may operate directly on the current extension-registry state shape for tool-specific operations in this slice
- this task does not require or imply a broader generic extension-registry helper extraction first
- `psi.tool-registry.*` must not depend on `psi.agent-session.extensions` helper internals at completion; if tool-specific logic needs registry-state access, it should own that logic directly or through a very small extracted helper that remains within this task's bounded scope

The exact final split may adjust during implementation, but completion requires one obvious extracted owner below `agent-session` for both canonical tool-def ownership and extension tool registration.

## Current live source/consumer inventory

These are the minimum known direct current surfaces at task creation time and must be reevaluated during implementation.

### Current authoritative sources likely to move or split

- `components/agent-session/src/psi/agent_session/tool_defs.clj`
- `components/agent-session/src/psi/agent_session/extensions.clj`

### Current higher-level production consumers of canonical tool defs

- `components/agent-session/src/psi/agent_session/session_runtime.clj`
- `components/agent-session/src/psi/agent_session/dispatch_effects.clj`
- `components/agent-session/src/psi/agent_session/conversation.clj`
- `components/agent-session/src/psi/agent_session/workflow_step_prep.clj`
- `components/agent-session/src/psi/agent_session/dispatch_handlers/session_mutations.clj`
- `components/agent-session/src/psi/agent_session/dispatch_handlers/scheduler.clj`
- `components/agent-session/src/psi/agent_session/bootstrap.clj`
- `components/agent-session/src/psi/agent_session/psi_tool.clj`
- `components/agent-session/src/psi/agent_session/tool_plan.clj`
- `components/agent-session/src/psi/agent_session/resolvers/extensions.clj`
- `components/ai/src/psi/ai/conversation.clj`

### Current higher-level registration entrypoints that should become adapters/seams

- `components/agent-session/src/psi/agent_session/mutations/extensions.clj`
- `components/agent-session/src/psi/agent_session/extensions/api.clj`
- any extension resolver/query surface that reads tool names from the extension registry

### Current test surfaces likely affected

- `components/agent-session/test/psi/agent_session/tool_defs_test.clj`
- the tool registration portions of `components/agent-session/test/psi/agent_session/extensions_test.clj`
- any tests proving resolver/mutation/API behavior around registered tool names

## Design decisions for the first cut

### 1. Separate tool registration from session active-tool policy

This task extracts the owner of registered tool definitions.

It does not redesign:

- `:tool-defs` storage in session data
- child-session inheritance/filtering semantics
- workflow step tool-selection policy

Those remain higher-level consumers of the extracted canonical tool-definition substrate.

### 2. Keep extension mutation/API seams above the component initially

`psi.extension/register-tool` and extension API `:register-tool` are orchestration/adaptation surfaces.

In the first cut they should call downward into the extracted tool-registration component rather than moving wholesale unless implementation proves a cleaner, still-bounded split.

### 3. Do not merge this with tool runtime

`104` already established `components/tool-runtime/` for execution mechanics.

This task must preserve that split:

- `tool-runtime` owns execution
- `tool-registry` owns canonical tool definitions and registration

### 4. Do not widen into a general extension-registry extraction

Only the tool-specific registration/catalog slice should move.

Command, flag, shortcut, and generic handler registration remain outside this task.

### 5. Preserve registered-tool query semantics explicitly

The extracted component must preserve the current tool-query behavior as an intentional contract:

- `tool-names-in` returns the registered tool-name set across all extensions
- `all-tools-in` returns one tool definition per tool name across all extensions
- when more than one extension registers the same tool name, first registration wins for `all-tools-in`

### 6. Preserve rich canonical tool-definition maps

Canonical tool-definition normalization should continue to preserve internal runtime-oriented fields present in the current shape, including fields such as:

- `:execute`
- `:source`
- `:ext-path`

Projection helpers remain the boundary that strip or ignore non-portable/runtime-only fields for agent-core or provider-facing payloads.

### 7. Keep tool-name validation scoped to extension registration in this slice

The extracted component should preserve the current validation scope:

- canonical kebab-case tool-name validation is enforced for extension tool registration
- this task does not broaden validation into a new global rule over every canonicalization path or every session/workflow-provided tool map unless implementation reveals a concrete existing invariant that already requires that broader behavior

### 8. Make test ownership explicit

Expected first-cut test ownership:

- move pure canonical tool-def normalization/projection tests into `components/tool-registry/test/`
- move pure tool-name validation and extension tool-registration/query behavior tests into `components/tool-registry/test/`
- keep mutation/API/resolver/integration proofs under higher-level components where they verify orchestration seams rather than the extracted lower owner

## Acceptance

- a separate `tool-registry` component exists
- the authoritative canonical tool-definition implementation no longer lives under `psi.agent-session.*`
- the authoritative extension tool-registration helper no longer lives under `psi.agent-session.*`
- higher-level production consumers compile against the extracted `psi.tool-registry.*` namespaces
- extension mutation/API entrypoints still behave the same while depending downward on the extracted component
- no new component cycle is introduced
- no tool execution/runtime mechanics are pulled into this component
- no session tool-selection policy is redesigned as part of this extraction

## Concrete done criteria

- task records the chosen component path explicitly as `components/tool-registry/`
- task records the authoritative namespace family explicitly as `psi.tool-registry.*`
- canonical tool-definition normalization has one authoritative home below `agent-session`
- canonical tool-name validation used by extension tool registration has one authoritative home below `agent-session`
- canonical extension tool registration has one authoritative home below `agent-session`
- registered-tool listing/query helpers specifically about extension tools move below `agent-session` or are delegated there from a thin upper seam
- all direct production consumers listed in the source/consumer inventory are updated to depend on the extracted component where relevant
- the tool-registration portions of the extension mutation/API surfaces call downward into the extracted component
- extracted component-owned tests live under `components/tool-registry/test/psi/tool_registry/`
- top-level project test configuration explicitly includes `components/tool-registry/test` in `tests.edn` wherever component test paths are enumerated for the standard root test runner
- focused tests prove both the extracted component and at least one higher-level registration consumer path
- no compatibility shim remains authoritative at completion

## Suggested migration sequence

1. create `components/tool-registry/` and add repo/component deps
2. move or split `tool_defs.clj` into the extracted component as the authoritative canonical tool-def owner
3. move or split tool-specific registration helpers out of `extensions.clj` into the extracted component
4. update direct production consumers to require `psi.tool-registry.*`
5. keep `mutations/extensions.clj` and `extensions/api.clj` as thin higher-level adapter seams unless implementation reveals a better bounded split
6. move or add focused tests under `components/tool-registry/test/`
7. remove temporary forwarding shims if any were used during migration
8. run focused verification and record the final ownership split in `implementation.md`

## Verification intent

Minimum proof should cover both component-owned logic and a higher-level consumer path.

Component-owned proof should include:

- canonical tool-def normalization behavior
- tool-name validation behavior at extension registration
- extension tool registration behavior
- registered-tool query/listing behavior, including explicit proof that `all-tools-in` remains first-registration-wins
- agent-core/provider projection behavior
- preservation of rich internal canonical maps while external projections omit or ignore runtime-only fields as intended
- proof that validation remains enforced at extension registration without unintentionally broadening into a new global validation rule across unrelated tool-definition normalization paths

Higher-level consuming proof should include at least one of:

- extension mutation/API registration path still registers a tool correctly
- resolver/query path still reports registered tool names correctly
- session runtime/provider projection path still consumes canonical tool defs correctly

Representative focused verification surfaces after migration:

- extracted component-owned tests under `components/tool-registry/test/psi/tool_registry/`
- one or more higher-level tests remaining under `agent-session`, such as:
  - tool registration portions of `extensions_test.clj`
  - mutation/API registration tests
  - any focused consumer proving session/provider projection still works

## Risks

- incomplete consumer migration is the main risk because canonical tool defs are used across runtime, scheduler, workflow, and provider projection paths
- moving too much of `extensions.clj` would widen the task into a general extension-registry extraction
- moving too little would leave split authority between `agent-session` and the extracted component
- mixing session tool-selection policy into the extracted component would blur the boundary immediately
- re-merging execution concerns from `tool-runtime` into this extraction would undo the clearer split created by `104`

## Related work

- `105-agent-session-component-extraction-map` is the umbrella architectural map for this extraction
- `104-tool-runtime-component-extraction` extracted lower-level tool execution/runtime mechanics into `components/tool-runtime/`
- `110-prompt-assets-component-extraction` is a model for a narrow component extraction with explicit non-member adjacent namespaces
- a later follow-on may revisit broader tool-domain extraction (`post_tool`, `tool_output`, `tool_path`, or UI/tool projection seams), but that is explicitly outside this first-cut registration slice
