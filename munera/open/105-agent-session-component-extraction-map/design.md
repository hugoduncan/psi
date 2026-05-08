# 105 — Agent-session component extraction map

## Goal

Create an umbrella refactoring task that defines the coherent component extractions currently latent inside `agent-session`, so follow-on structural work proceeds from a consistent component map instead of isolated namespace-by-namespace moves.

## Why

Recent extraction work around turn runtime exposed a broader structural reality:

- several coherent subsystems already exist inside `components/agent-session/`
- some earlier candidate extractions, especially `102-turn-preparation-component-extraction`, become awkward or half-complete when pursued in isolation
- the right next move is to establish the map of extractable components just above `session-state`, then sequence concrete child extraction tasks against that map

This task is intended to be the architectural umbrella for that decomposition.

## Problem

`agent-session` currently mixes:

- true session orchestration ownership
- turn lifecycle ownership
- prompt asset/composition ownership
- tool runtime ownership
- workflow runtime ownership
- OAuth/provider-auth ownership
- extension runtime ownership
- scheduler ownership
- project nREPL ownership
- persistence/journal ownership

Without an explicit component map, narrow extraction tasks risk:

- creating intermediate half-boundaries
- relocating namespaces without clarifying ownership
- choosing poor extraction order
- duplicating boundary decisions across tasks

## Intent

Define the coherent extractable components that sit structurally above `session-state` and currently reside inside `agent-session`.

This umbrella should:

- name the candidate components
- state the conceptual responsibility of each
- identify the most likely current namespace clusters belonging to each
- state which areas are likely to remain as the `agent-session` core
- propose an initial extraction order
- provide a framing reference for follow-on task creation, refinement, or supersession

This umbrella should not:

- perform the extractions itself
- force every candidate component to be extracted immediately
- require final dependency purity for all components up front
- redesign runtime semantics beyond clarifying ownership and ordering

## Candidate component map

### Core that likely remains in `agent-session`

The probable remaining `agent-session` core is:

- session lifecycle
- context assembly / runtime wiring
- dispatch coordination
- statechart coordination
- canonical session orchestration
- cross-domain command / resolver / mutation entrypoints that are still aggregating multiple lower components rather than owning a single subsystem

Representative namespaces:

- `bootstrap.clj`
- `context.clj`
- `context_index.clj`
- `core.clj`
- `runtime.clj`
- `session_lifecycle.clj`
- `session_runtime.clj`
- `session_close.clj`
- `session_settings.clj`
- `child_session_state.clj`
- `commands.clj`
- `resolvers.clj`
- `mutations.clj`
- dispatch/statechart coordination namespaces that remain central rather than domain-specific:
  - `dispatch.clj`
  - `dispatch_effects.clj`
  - `dispatch_handlers.clj`
  - `dispatch_schema.clj`
  - `statechart.clj`
  - `state_accessors.clj`

Boundary note after reviewing the current namespace surface:

- `commands.clj`, `resolvers.clj`, and `mutations.clj` should currently be treated as orchestration/aggregation surfaces, not as evidence that `agent-session` should continue owning every subsystem they route into
- domain-specific sub-entrypoints already indicate future downward ownership shifts, for example `mutations/canonical_workflows.clj`, `mutations/extensions.clj`, `mutations/prompts.clj`, `mutations/tools.clj`, `resolvers/extensions.clj`, `resolvers/scheduler.clj`, and `resolvers/workflows.clj`
- the residual `agent-session` core should get smaller as more domain components become authoritative owners, but it should remain the session-oriented composition layer rather than disappearing entirely

### Extractable component candidates

#### 1. Prompt composition / prompt assets

Responsibility:

- prompt templates
- skills as prompt-expansion assets
- prompt contribution filtering / ordering
- system-prompt assembly
- tool surface projection into prompts
- provider-facing conversation rendering

Representative namespaces:

- `prompt_templates.clj`
- `skills.clj`
- `system_prompt.clj`
- `conversation.clj`
- prompt-definition/projection parts of `tool_defs.clj`
- possibly `message_text.clj` if it proves more prompt-text than session-owned
- likely prompt-facing config inputs were previously reached through `project_preferences.clj`, `user_config.clj`, and `config_resolution.clj`; those config owners have now been extracted into `components/shared-config/` and should continue to be treated as a lower shared substrate rather than prompt-owned logic

Reason it is coherent:

- these namespaces form one conceptual pipeline: assets -> selection -> expansion -> assembly -> provider-facing conversation projection
- this is a stronger first-cut component boundary than splitting skills/templates/prompt into several smaller components immediately

Boundary note after reviewing the current namespace surface:

- prompt composition and turn orchestration should stay distinct: prompt assets decide what to send, while turn owns when/how a single agent turn is prepared, executed, recorded, and continued
- `tool_defs.clj` appears split-brain: prompt-facing tool schema projection likely belongs with prompt composition, while runtime execution ownership belongs with the tool runtime component
- skill registration now also appears split from broader prompt ownership: discovery/parsing/invocation belongs with prompt assets, while pure registered-skill collection semantics can sit in a narrower lower `skill-registry` child component
- `message_text.clj` remains ambiguous and should be treated as a review point during a concrete extraction rather than forced into prompt ownership prematurely
- config resolution remains a cross-cutting concern: prompt composition consumes config, but config loading itself now has an explicit lower owner in `components/shared-config/` rather than belonging inside the prompt component

#### 2. OAuth / provider auth

Responsibility:

- OAuth flows
- token storage
- provider registry/config linkage for auth
- API key and provider request auth resolution

Representative namespaces:

- `oauth/core.clj`
- `oauth/store.clj`
- `oauth/providers.clj`
- `oauth/pkce.clj`
- `oauth/callback_server.clj`
- `provider_auth.clj`

Reason it is coherent:

- already strongly bounded
- conceptually independent from session orchestration
- likely one of the cleanest extractions available now

Follow-on result from child task `106-provider-auth-component-extraction`:

- this extraction is now landed as `components/provider-auth/`
- authoritative namespaces now live under `psi.provider-auth.*`
- higher-level `app-runtime`, `rpc`, and `agent-session` consumers now depend downward on the extracted auth component
- the extraction confirmed this candidate was correctly identified as a low-ambiguity first move

#### 3. Tool runtime

Responsibility:

- tool argument parsing
- tool execution/runtime helpers
- batch execution / ordering / serialization
- tool result normalization and generic tool-runtime event shaping

Representative namespaces:

- `tools.clj`
- `tool_runtime_adapter.clj`
- `tool_plan.clj`
- `tool_path.clj`
- execution-owned parts of `tool_output.clj`
- post-execution shaping in `post_tool.clj`
- runtime-facing parts of `tool_defs.clj`

Reason it is coherent:

- tool execution is a real domain with runtime semantics distinct from session orchestration
- tool ownership is now more clearly a runtime-and-shaping cluster than the earlier narrower `tool_execution.clj` naming implied
- extracted turn code under `components/agent-session/src/psi/turn/` makes the seam clearer: turn should depend downward on tool runtime capability, not own tool runtime itself

Boundary note after reviewing the current namespace surface:

- the previously envisioned `104-tool-runtime-component-extraction` child is now closed, but the underlying domain boundary still appears real in the current source tree
- `post_tool.clj` looks like tool-domain behavior rather than generic turn orchestration and should be considered with the tool component first
- `tool_output.clj` and `tool_defs.clj` are likely split-ownership files whose prompt-facing and runtime-facing concerns may need extraction-time decomposition rather than whole-file moves
- `tool_path.clj` may prove lower-level and reusable across prompt, turn, and workflow flows, but its semantics still align more closely with tool ownership than with session orchestration

#### 4. Turn

Responsibility:

- one agent turn lifecycle
- prepare -> execute -> record -> continue/finish flow
- active turn orchestration
- tool-use continuation semantics

Representative namespaces:

- `prompt_control.clj`
- `prompt_loop.clj`
- `prompt_turn.clj`
- `prompt_request.clj`
- `prompt_recording.clj`
- prompt-lifecycle dispatch handlers:
  - `dispatch_handlers/prompt_handlers.clj`
  - `dispatch_handlers/prompt_lifecycle.clj`
- extracted lower turn surface already present under `components/agent-session/src/psi/turn/`:
  - `psi.turn.handlers`

Reason it is coherent:

- already one conceptual unit
- becomes cleaner after prompt composition / auth / tool seams are made explicit
- the current tree now shows an important intermediate state: some turn ownership has already moved out of `psi.agent-session.*`, but higher-level prompt/turn orchestration still remains mixed into `agent-session`
- prior task `102` attempted too narrow a first cut without this wider map

Boundary note after reviewing the current namespace surface:

- turn is not the same thing as prompt composition: prompt composition should be a lower dependency that turn consumes
- turn is also not the same thing as session orchestration: `agent-session` should remain the owner of multi-session/session-lifecycle concerns even after a broader turn extraction
- the live presence of `psi.turn.handlers` indicates that turn extraction is already partially underway; follow-on task design should account for this current split state instead of pretending turn is wholly unextracted
- landed task `100-turn-statechart-component-extraction` should now be understood as a narrower low-level turn child under this umbrella, not as a substitute for the broader turn boundary

#### 5. Workflow

Responsibility:

- workflow model
- workflow IR
- file authoring / parsing / loading / compilation
- runtime execution
- attempts / judging / routing / progression
- deterministic operation bridging

Representative namespaces:

- the `workflow_*` namespace family, including model / IR / authoring / compiler / runtime / progression / statechart namespaces
- `workflows.clj`
- `workflow_mutations.clj`
- `psi_tool_workflow.clj`
- `mutations/canonical_workflows.clj`
- `resolvers/workflows.clj`
- `deterministic_operations.clj`
- `deterministic_operation_registry.clj`

Reason it is coherent:

- already a subsystem rather than a helper cluster
- likely one of the strongest extraction candidates in the codebase
- the current source tree shows the workflow family is already broad enough to justify component extraction even before every entrypoint and adapter seam is perfectly isolated

Boundary note after reviewing the current namespace surface:

- this is the clearest remaining extraction candidate by namespace mass and conceptual cohesion
- the workflow domain already spans authoring, loading, compilation, execution, judging, routing, progression, and deterministic-operation bridging; keeping all of that under `agent-session` increasingly looks like historical placement rather than present ownership
- `psi_tool_workflow.clj`, `workflow_mutations.clj`, `mutations/canonical_workflows.clj`, and `resolvers/workflows.clj` are likely adapter/entrypoint seams that may remain briefly in `agent-session` during migration, but their owned logic should flow downward toward an extracted workflow component

#### 6. Project nREPL

Responsibility:

- managed project REPL lifecycle
- attach/start/stop/eval
- runtime connection ownership
- REPL command/config surfaces

Representative namespaces:

- the `project_nrepl_*` namespace family

Reason it is coherent:

- already strongly bounded as a technical subsystem
- likely a relatively low-ambiguity extraction

Follow-on result from child task `107-project-nrepl-component-extraction`:

- this extraction is now landed as `components/project-nrepl/`
- authoritative namespaces now live under `psi.project-nrepl.*`
- higher-level `agent-session` command/context/psi-tool/resolver consumers now depend downward on the extracted project-nREPL component
- the extraction confirmed this candidate was correctly identified as a low-ambiguity early move
- follow-on architectural note from the original extraction has now been resolved by landed task `109-shared-config-resolution-component-extraction`
- shared file-backed config ownership now lives in `components/shared-config/` under `psi.shared-config.*`
- `psi.project-nrepl.config` now depends downward on that shared substrate instead of carrying copied project/user config reading logic
- accepted drift from the child task review still stands: `project-repl/start` missing-config handling now returns a structured component result, so the `psi-tool` error contract may want a later follow-on if stricter tool-facing error semantics matter

#### 7. Extensions runtime

Responsibility:

- extension loading
- registration
- manifests
- extension runtime API surface
- extension delivery/query/ui runtime bridging

Representative namespaces:

- `extensions.clj`
- `extension_runtime.clj`
- `extension_installs.clj`
- `extensions/api.clj`
- `extensions/loader.clj`
- `extensions/runtime_delivery.clj`
- `extensions/runtime_eql.clj`
- `extensions/runtime_fns.clj`
- `extensions/runtime_ui.clj`
- likely extension-facing entrypoint seams:
  - `mutations/extensions.clj`
  - `resolvers/extensions.clj`

Reason it is coherent:

- clearly a subsystem with its own runtime concerns
- the namespace tree already looks component-shaped: loader, runtime, API, delivery, EQL, callable fns, and UI are all explicit sub-concerns inside one domain

Boundary note after reviewing the current namespace surface:

- extension runtime is broad enough to be its own component, but it may be lower in extraction order than workflow/prompt/tool because it is also a dependency surface for many other domains
- this domain likely benefits from preserving `agent-session` as a temporary higher-level composition host for entrypoints while moving the underlying runtime/loader/API ownership downward first

#### 8. Scheduler

Responsibility:

- delayed work definition
- schedule runtime execution
- scheduler-facing tool surfaces

Representative namespaces:

- `scheduler.clj`
- `scheduler_runtime.clj`
- `psi_tool_scheduler.clj`
- scheduler-specific entrypoint seams:
  - `dispatch_handlers/scheduler.clj`
  - `resolvers/scheduler.clj`

Reason it is coherent:

- small but distinct domain with clear runtime semantics

Boundary note after reviewing the current namespace surface:

- scheduler still looks like a valid extracted component, but the namespace count suggests it is a mid-to-late extraction rather than one of the first three moves
- the explicit existence of scheduler-specific dispatch handler and resolver seams is a good sign: the runtime logic can likely move downward while `agent-session` keeps temporary orchestration entrypoints

#### 9. Persistence / journal

Responsibility:

- session persistence
- journal read/write/load/listing
- compaction

Representative namespaces:

- `persistence.clj`
- `compaction.clj`
- `compaction_runtime.clj`
- journal-touching entrypoint seams are also visible in dispatch/prompt/session code, but those call sites should not be mistaken for persistence ownership

Reason it is coherent:

- storage/history concerns form a strong boundary from session orchestration

Boundary note after reviewing the current namespace surface:

- persistence/journal ownership still looks real, but it is tightly coupled to session identity and lifecycle, so extraction order should remain later than workflow/prompt/tool
- this domain may eventually want an extracted storage/history component while still leaving session-lifecycle policy in `agent-session`

#### 10. Background jobs

Responsibility:

- background job runtime state
- background job lifecycle helpers

Representative namespaces:

- `background_jobs.clj`
- `background_job_runtime.clj`

Reason it is coherent:

- smaller than the major subsystems, but already a real local domain

Boundary note after reviewing the current namespace surface:

- background jobs still looks extractable, but the current tree suggests it is better treated as a supporting runtime substrate than as a near-term extraction priority
- unless background-job logic starts growing independently, it may reasonably wait behind workflow, prompt, tool, and extension moves

## Extraction ordering guidance

Recommended first-cut extraction order after reviewing the live namespace surface:

1. OAuth / provider auth — landed via child task `106`
2. Project nREPL — landed via child task `107`
3. Workflow
4. Prompt composition / prompt assets
5. Tool runtime
6. Turn
7. Extensions runtime
8. Scheduler
9. Persistence / journal
10. Background jobs

Ordering rationale:

- start with bounded subsystems that already look component-shaped
- the current source tree makes workflow the strongest remaining extraction candidate by both size and cohesion
- extract prompt composition before forcing a cleaner turn extraction
- treat tool runtime and turn as adjacent but distinct follow-on decompositions
- move extensions runtime ahead of scheduler/persistence/background jobs because its current namespace family is already explicitly substructured and component-like
- preserve `agent-session` as the orchestration/core layer rather than the home of every domain subsystem

## Current namespace-surface review summary

Reviewing the current `components/agent-session/src/psi/agent_session/` tree sharpened several points:

- the workflow family is now the clearest remaining extraction candidate
- turn extraction is already partial because `components/agent-session/src/psi/turn/handlers.clj` exists, so future work should assume an in-progress split rather than a wholly internal domain
- prompt composition, tool runtime, and turn are three adjacent but distinct boundaries and should not be collapsed into a single vague "prompt runtime" move
- config reading/resolution had emerged as a repeated cross-cutting concern from child task `107`; landed task `109` has now resolved that pressure by extracting an explicit lower shared-config owner instead of tolerating repeated copied logic
- top-level `commands.clj`, `mutations.clj`, and `resolvers.clj` should be treated as aggregator seams in the residual `agent-session` core, not as proof that underlying subsystem ownership belongs there permanently

## Relationship to existing tasks

- landed child tasks `106-provider-auth-component-extraction` and `107-project-nrepl-component-extraction` validate the umbrella's early bounded-subsystem ordering
- landed child task `104-tool-runtime-component-extraction` confirmed the domain boundary was useful, even though tool-related ownership still remains distributed enough that the umbrella continues to track the broader tool-runtime shape
- open child task `112-skill-registration-component-extraction` sharpens the prompt/skills boundary by extracting pure registered-skill collection semantics into a lower `skill-registry` component while leaving discovery/parsing/invocation in `prompt-assets.skills`
- landed task `100-turn-statechart-component-extraction` should be treated as a narrow low-level turn child aligned with this umbrella, not as a replacement for a broader turn-component decision
- `102-turn-preparation-component-extraction` is superseded by this umbrella because its narrow extraction target proved structurally premature without the broader component map
- follow-on extraction tasks should reference this umbrella when they carve out one of the named component candidates

## Acceptance

- an umbrella task exists for the `agent-session` decomposition map
- the umbrella records the coherent component candidates currently latent inside `agent-session`
- the umbrella distinguishes likely `agent-session` core ownership from extractable subsystem ownership
- the umbrella records an initial extraction ordering
- superseded narrow tasks can now be closed or re-scoped against this map instead of continuing from an under-specified boundary
