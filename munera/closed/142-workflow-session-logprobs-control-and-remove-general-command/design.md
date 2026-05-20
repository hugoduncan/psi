Goal: Add workflow-authored child-session control for logprob collection, parallel to the workflow-only `:response-mode` control added in task 141, and remove the general `/logprobs` slash command so logprob collection is no longer a user-facing session toggle.

## Why

Task 140 introduced logprob collection as a general transient session toggle exposed via `/logprobs`.
Task 141 then added workflow-owned child-session `:response-mode` control specifically to support provider combinations such as `tools + logprobs` that need non-streaming execution.

That leaves the control surface mismatched:
- `:response-mode` is workflow-scoped and carried through workflow child-session config
- `:logprobs-enabled` / `:top-logprobs` are still general session toggles exposed by slash command

For the motivating workflow use case, logprob collection should be authored as part of workflow child-session setup, not toggled ad hoc through a general command.

## Scope

This task is intentionally workflow-scoped.

It adds workflow child-session controls for logprob collection and removes the general `/logprobs` command surface.

This task does **not** add a new general user-facing replacement command, project config, or scheduler-wide session logprob preference.

## Desired behaviour

Workflow step session config may specify logprob collection controls for the child session.
A workflow-owned child session may request:

- `:logprobs true|false`
- optional `:top-logprobs N` where `1 <= N <= 20`

When workflow child-session config omits these fields:
- logprob collection defaults to disabled
- `:top-logprobs` remains absent

When workflow child-session config provides `:top-logprobs` while `:logprobs` is false or absent:
- resolution drops the authored `:top-logprobs` value from the resolved child-session config
- the workflow config is treated as effectively disabled for logprob collection rather than rejected

When workflow child-session config sets `:logprobs true` and omits `:top-logprobs`:
- resolved child-session config carries `:logprobs true`
- persisted child-session state may keep `:top-logprobs` absent
- lower request-option projection still defaults the provider request to top-N 3 when enabled, preserving task 140 behavior without inventing a second workflow-layer default

Ordinary interactive sessions no longer expose `/logprobs`.

## Control surface

The new workflow-authored field lives in workflow session config and resolved child session data as:

- `:logprobs` — boolean enable/disable flag
- `:top-logprobs` — optional integer 1–20

Rationale:
- matches the request-shaping concept already implemented in task 140
- keeps workflow-authored execution preferences together with `:response-mode`
- removes the now-too-general user-facing control path

Validation surface:
- workflow IR `session-spec-schema` must explicitly accept optional `:logprobs` and optional `:top-logprobs`
- child session state/schema must explicitly accept optional `:logprobs-enabled` and optional `:top-logprobs` if those remain the canonical persisted session-data keys
- if implementation instead renames the persisted child-session key from `:logprobs-enabled` to `:logprobs`, the task must update all downstream consumers coherently and preserve one canonical persisted key shape

## Canonical-shape decision to make explicit during implementation

Task 140 currently uses session-data keys:
- `:logprobs-enabled`
- `:top-logprobs`

This task should decide one of two coherent shapes:

1. **Workflow-control aliases onto existing persisted keys**
   - workflow session config accepts `:logprobs`
   - resolution/creation maps that onto child-session session-data key `:logprobs-enabled`
   - lower request building remains unchanged

2. **Canonical rename to match workflow control surface**
   - workflow session config accepts `:logprobs`
   - child-session/session-data canonical key becomes `:logprobs`
   - all lower request-option, telemetry, and proof surfaces are updated accordingly

Preferred direction: keep the workflow-authored control named `:logprobs`, but choose the smallest coherent implementation after inventorying downstream key usage. The task must not leave both names as long-term parallel canonical keys.

## Data flow

Workflow-authored step/session config:

```clojure
:session {:model ...
          :tools ...
          :response-mode :non-streaming
          :logprobs true
          :top-logprobs 5}
```

Flow:

```clojure
workflow definition
→ workflow target IR / effective step session spec
→ resolve-step-session-config
→ workflow attempt child-session creation opts
→ workflow execution adapter handoff
→ :create-workflow-child-session-fn / create-workflow-child-session!
→ :session/create-child dispatch params
→ child-session initializer
→ child session data stores resolved logprob controls
→ session->request-options projects logprob controls
→ provider request includes logprob fields when enabled
```

Propagation owners that must stay coherent for this task:
- `components/workflow-runtime/src/psi/workflow_runtime/target_ir_compiler.clj`
- `components/workflow-step-session-config/src/psi/workflow_step_session_config/core.clj`
- `components/workflow-runtime/src/psi/workflow_runtime/attempts.clj`
- `components/agent-session/src/psi/agent_session/context.clj`
- `components/agent-session/src/psi/agent_session/dispatch_handlers/session_lifecycle.clj`
- `components/agent-session/src/psi/agent_session/child_session_state.clj`
- `components/agent-session/src/psi/agent_session/prompt_request.clj`

Related workflow runtime/session execution surfaces that must not drift while this task lands:
- workflow session-step execution under `statechart-runtime/*`
- judge child-session creation in `components/agent-session/src/psi/agent_session/workflow_judge.clj` when judge-session config later adopts the same control family

Current task boundary: only the workflow child-session creation path used for authored workflow session steps must gain logprob propagation now; judge sessions remain unchanged unless they already consume the same canonical child-session creation shape as part of the minimal implementation.

## Architectural intent

Follow the same control-pattern shape chosen in task 141:
- workflow authors specify child-session behaviour in workflow session config
- workflow resolution propagates the config explicitly
- lower execution/request layers consume persisted child-session settings without needing workflow-specific logic

The only user-facing control removal in this task is `/logprobs`.
The lower logprob accumulation, journal append, projection, and resolver behaviour from task 140 should remain available whenever a session is configured for logprobs.

## Removal scope

Remove the general `/logprobs` command surface, including:
- slash-command dispatch/handler path
- help text and TUI autocomplete exposure
- any general-session mutation that exists only to back the command

Deliberate boundary for non-workflow callers:
- this task does not widen interactive or public non-workflow session creation surfaces to expose new logprob controls
- the existing public `psi.extension/create-child-session` mutation should not gain new logprob params as part of this task
- if lower child-session creation internals are widened for workflow propagation, interactive callers still remain unable to author logprob collection through a general user-facing command or replacement toggle

This task does **not** need to remove lower logprob capability itself.
It only removes the general command-based control path.

## Constraints

- Do not widen this into a new general replacement command or global config feature.
- Keep the lower request-building and logprob telemetry behaviour intact except where needed to support workflow-authored control.
- Preserve task 141’s workflow-only execution-mode design: workflows may combine `:response-mode :non-streaming` with logprob control to avoid provider rejection.
- Avoid leaving ambiguous dual canonical keys for enabled-state naming.

## Acceptance

- Workflow step/session config accepts `:logprobs` and optional `:top-logprobs`.
- `resolve-step-session-config` includes the resolved logprob controls in child-session config.
- Workflow child-session creation persists the resolved logprob controls on the child session.
- Request-option projection and request building honor workflow-owned child-session logprob controls.
- A workflow-owned child session can combine `:response-mode :non-streaming` with logprob collection for the motivating provider case.
- `/logprobs` is removed from user-facing command dispatch, help text, and autocomplete.
- No general session-level replacement toggle is introduced.
- Focused proof covers propagation plus removal of the general command surface.

## Explicit non-goals

- a new user-facing `/logprobs` replacement
- project-wide or scheduler-wide logprob defaults
- provider auto-detection or automatic response-mode fallback
- redesign of the lower logprob telemetry/journal projection feature
- widening logprob control to every session creation surface in the system
