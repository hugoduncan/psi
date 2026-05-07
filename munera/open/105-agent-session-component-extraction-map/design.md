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

Representative namespaces:

- `context.clj`
- `session_lifecycle.clj`
- `session_runtime.clj`
- `session_close.clj`
- `session_settings.clj`
- `child_session_state.clj`
- `bootstrap.clj`
- dispatch/statechart coordination namespaces that remain central rather than domain-specific

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
- potentially prompt-facing parts of `tool_defs.clj`
- possibly `message_text.clj` if it proves more prompt-text than session-owned

Reason it is coherent:

- these namespaces form one conceptual pipeline: assets -> selection -> expansion -> assembly -> provider-facing conversation projection
- this is a stronger first-cut component boundary than splitting skills/templates/prompt into several smaller components immediately

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

- `tool_execution.clj`
- `tool_batch.clj`
- `tool_plan.clj`
- `tool_path.clj`
- possibly execution-owned parts of `tool_output.clj`
- parser seam currently in `turn_runtime/tool_args.clj`

Reason it is coherent:

- tool execution is a real domain with runtime semantics distinct from session orchestration
- extraction candidate `104-tool-runtime-component-extraction` is a concrete child of this umbrella

#### 4. Turn

Responsibility:

- one agent turn lifecycle
- prepare -> execute -> record -> continue/finish flow
- active turn orchestration
- tool-use continuation semantics

Representative namespaces:

- `psi/turn.clj`
- `prompt_control.clj`
- `prompt_loop.clj`
- `prompt_turn.clj`
- `prompt_request.clj`
- `prompt_recording.clj`

Reason it is coherent:

- already one conceptual unit
- becomes cleaner after prompt composition / auth / tool seams are made explicit
- prior task `102` attempted too narrow a first cut without this wider map

#### 5. Workflow

Responsibility:

- workflow model
- workflow IR
- file authoring / parsing / loading / compilation
- runtime execution
- attempts / judging / routing / progression
- deterministic operation bridging

Representative namespaces:

- the `workflow_*` namespace family
- `deterministic_operations.clj`
- `deterministic_operation_registry.clj`

Reason it is coherent:

- already a subsystem rather than a helper cluster
- likely one of the strongest extraction candidates in the codebase

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
- follow-on architectural note: `psi.project-nrepl.config` currently carries copied project-config reading logic instead of depending on a lower shared config owner
- this preserved behavior in the child task, but it is a signal that config-reading concerns may themselves want a dedicated lower component or otherwise explicitly shared ownership
- accepted drift from the child task review: `project-repl/start` missing-config handling now returns a structured component result, so the `psi-tool` error contract may want a later follow-on if stricter tool-facing error semantics matter
- revisit later whether project/user/shared/local config resolution should be extracted as a complete component rather than recopied across subsystem boundaries

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

Reason it is coherent:

- clearly a subsystem with its own runtime concerns

#### 8. Scheduler

Responsibility:

- delayed work definition
- schedule runtime execution
- scheduler-facing tool surfaces

Representative namespaces:

- `scheduler.clj`
- `scheduler_runtime.clj`
- `psi_tool_scheduler.clj`
- scheduler-specific handlers / resolvers where ownership proves local

Reason it is coherent:

- small but distinct domain with clear runtime semantics

#### 9. Persistence / journal

Responsibility:

- session persistence
- journal read/write/load/listing
- compaction

Representative namespaces:

- `persistence.clj`
- `compaction.clj`
- `compaction_runtime.clj`

Reason it is coherent:

- storage/history concerns form a strong boundary from session orchestration

#### 10. Background jobs

Responsibility:

- background job runtime state
- background job lifecycle helpers

Representative namespaces:

- `background_jobs.clj`
- `background_job_runtime.clj`

Reason it is coherent:

- smaller than the major subsystems, but already a real local domain

## Extraction ordering guidance

Recommended first-cut extraction order:

1. OAuth / provider auth — landed via child task `106`
2. Project nREPL — landed via child task `107`
3. Workflow
4. Prompt composition / prompt assets
5. Tool runtime
6. Turn
7. Scheduler
8. Persistence / journal
9. Extensions runtime
10. Background jobs

Ordering rationale:

- start with bounded subsystems that already look component-shaped
- extract prompt composition before forcing a cleaner turn extraction
- treat tool runtime and turn as adjacent but distinct follow-on decompositions
- preserve `agent-session` as the orchestration/core layer rather than the home of every domain subsystem

## Relationship to existing tasks

- `104-tool-runtime-component-extraction` is a concrete child aligned with this umbrella
- `102-turn-preparation-component-extraction` is superseded by this umbrella because its narrow extraction target proved structurally premature without the broader component map
- follow-on extraction tasks should reference this umbrella when they carve out one of the named component candidates

## Acceptance

- an umbrella task exists for the `agent-session` decomposition map
- the umbrella records the coherent component candidates currently latent inside `agent-session`
- the umbrella distinguishes likely `agent-session` core ownership from extractable subsystem ownership
- the umbrella records an initial extraction ordering
- superseded narrow tasks can now be closed or re-scoped against this map instead of continuing from an under-specified boundary
