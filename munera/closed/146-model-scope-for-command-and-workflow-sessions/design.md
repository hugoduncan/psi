Goal: Expose explicit model-setting scope selection to users and make workflow-owned model changes transient by default, so workflow-authored model selection never mutates user or project configuration while interactive model changes can intentionally target session, project, or user scope.

## Why

The runtime already supports scoped model mutation semantics at the lower `:session/set-model` event:
- `:session` → runtime-only / transient
- `:project` → persisted to project-local preferences
- `:user` → persisted to user config

But that capability is not consistently exposed at the command and workflow surfaces.

Today:
- `/model` only supports showing the current model or setting `<provider> <model-id>` with the existing default persistence behavior
- RPC `set_model` similarly does not expose optional scope selection
- workflow runtime fallback/session model switching currently routes through the ordinary model-set path and risks touching persisted config unless the caller explicitly marks the update as session-scoped

This creates two problems:
1. users cannot intentionally choose whether a model change is transient, project-local, or user-global from the primary command surface
2. workflow-owned child-session model selection can accidentally mutate persisted preferences, even though workflow model choice should be ephemeral and local to the created execution session

## Scope

This task introduces one explicit user-facing scope surface for model setting and aligns workflow-owned model changes to the transient/session scope.

In scope:
- extend `/model` to accept an optional third argument: `session`, `project`, or `user`
- extend the programmatic/RPC model-set surfaces to accept the same optional scope where applicable
- thread scope through the public session-setting helpers rather than requiring callers to dispatch low-level events directly
- align direct interactive model-set entrypoints that already bypass slash-command parsing with the same canonical helper/API shape, specifically the Emacs `set_model` RPC caller and the TUI submit handling path, while preserving omitted-scope compatibility and without requiring new scope-picking UI in this task
- make workflow-created child-session model changes explicitly session-scoped, including ranked fallback model switching for workflow-owned session execution
- update help/documentation text that describes `/model`
- add focused proof that workflow model changes do not persist project or user configuration

Out of scope:
- redesigning model-selection or model-resolution semantics
- changing the default meaning of `/model <provider> <model-id>` unless implementation records a compelling reason
- adding an analogous scope argument to unrelated commands in this task
- creating a broader preference-policy abstraction beyond the existing `:session|:project|:user` model-setting semantics
- adding new UI beyond the command/RPC surfaces needed to carry the scope

## Desired behaviour

### Slash command

`/model` supports these forms:
- `/model` → show current model
- `/model <provider> <model-id>` → set model using the existing default scope behavior
- `/model <provider> <model-id> <scope>` → set model with explicit scope, where `<scope>` is one of `session`, `project`, `user`

Validation rules:
- any other arity is rejected with a clear usage message
- any unknown scope token is rejected with a clear error naming the accepted scopes
- unknown models continue to fail coherently as they do today

Usage/help text should show the third optional argument explicitly, e.g.:
- `/model [provider model-id [session|project|user]]`

### Public helper and RPC parity

Any canonical public helper used for model setting should accept optional scope and forward it to the lower mutation unchanged.

The RPC `set_model` surface should also accept optional scope and apply the same semantics:
- omitted scope preserves the current default behavior
- explicit `session|project|user` behaves the same as the slash command

If the Emacs direct setter or picker-backed setter uses the RPC `set_model` op, it should remain compatible when scope is omitted. The direct interactive entrypoints that already call `set_model` / `set-model-in!` outside slash-command parsing are in scope to align with the same canonical helper/API contract, but adding explicit scope-selection UI is not required; omitted scope remains the default path and the transport/helper must be able to carry the optional scope cleanly.

### Workflow-owned model changes

Workflow-authored model selection must be transient and scoped only to the created child session.

Authoritative rule:
- when workflow runtime creates or updates the model for a workflow-owned child session, that change must behave as `:scope :session`

This includes:
- the initially resolved concrete model for a workflow child session
- any workflow-owned fallback switching across ranked `:model-query` candidates
- any other workflow runtime path that changes the execution child session's model during the run
- workflow-owned judge child sessions if they set or later gain the ability to set a model through the same public helper/API surface; this task does not require inventing new judge-specific model selection behaviour, but it does classify judge sessions under the same transient/no-persistence rule rather than treating them as a separate persistence domain

Required persistence semantics:
- no write to project preferences
- no write to user config
- the model change is visible only on the workflow-created execution session state

This task is about scope/persistence semantics, not about changing how workflows choose models.

## Architectural intent

The lower scoped mutation contract already exists in `:session/set-model`. This task should expose and preserve that contract rather than introducing parallel persistence logic at higher layers.

Preferred shape:
- command/RPC/helper layers parse and validate a scope token
- those layers pass scope downward unchanged
- workflow runtime explicitly opts into `:session` scope wherever it sets models for workflow-owned child sessions

That keeps the persistence policy centralized in the existing session mutation handler while making higher-level intent explicit.

## Constraints

- preserve existing behavior for callers that omit scope, unless implementation records and justifies a deliberate default change
- do not let workflow-owned model changes write project or user preferences
- keep workflow scope selection local to the workflow-created session rather than mutating the parent session
- do not broaden this task into thinking-level or prompt-mode scope redesign, except where a tiny supporting adjustment is strictly necessary for coherence
- keep user-facing command semantics simple and unambiguous

## Acceptance

- `/model` accepts the existing zero-arg show form
- `/model <provider> <model-id> <scope>` accepts `session`, `project`, and `user`
- invalid scope values produce a clear error
- help/usage text for `/model` documents the optional scope argument
- the canonical model-setting helper/API surface used by commands and RPC can carry optional scope
- RPC `set_model` accepts optional scope and preserves existing behavior when omitted
- explicit `session` scope does not persist user or project config
- explicit `project` scope persists only project-local preferences
- explicit `user` scope persists only user config
- workflow-owned child-session model changes are explicitly session-scoped and do not persist project or user config
- workflow transient-scoping proofs explicitly cover both the initial workflow child-session concrete-model setup path and ranked fallback switching as persistence regression targets
- focused tests cover both command/API scope handling and workflow transient-scoping regression cases

## Likely owners

This task likely touches:
- `components/agent-session/src/psi/agent_session/commands.clj`
- `components/agent-session/src/psi/agent_session/session_settings.clj`
- `components/agent-session/src/psi/agent_session/core.clj`
- `components/rpc/src/psi/rpc/session/ops.clj`
- `components/agent-session/src/psi/agent_session/context.clj`
- workflow runtime/session execution seams that currently set child-session models during fallback or execution
- relevant tests under `components/agent-session/test/psi/agent_session/` and `components/rpc/test/psi/`

## Explicit non-goals

- changing ordinary model catalog contents or model ranking
- introducing scope selection to unrelated commands by default
- redesigning project/user preference storage layout
- making workflow model selection persistent across sessions
- adding a generalized persistence-policy DSL