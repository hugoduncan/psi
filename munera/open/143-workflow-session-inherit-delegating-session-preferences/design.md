Goal: Make workflow-owned child sessions prefer the delegating session's active preferences as their inheritance base, instead of falling back to user/project-configured defaults that may have been loaded into a different context session.

## Why

Workflow child-session execution is supposed to continue the delegating session's local execution context.
Today, workflow session config resolution can fall back to the first listed context session when no explicit parent session id is provided. In contexts containing multiple sessions, that can make workflow-owned child sessions inherit the wrong model and adjacent preferences.

The concrete failure mode is that a delegating session has explicitly selected a model and other runtime preferences, but the workflow child session resolves its inheritance base from a different session whose state still reflects user/project defaults or some unrelated session-local overrides.

That breaks the intuitive contract:
- delegate from session A
- workflow child session should inherit session A's active preferences unless the workflow explicitly overrides them

## Scope

This task is intentionally about **workflow child-session preference inheritance**.

It updates workflow session creation so the delegating session is the authoritative inheritance source for workflow-owned child sessions.

This task does **not** redesign all session creation defaults, scheduler session creation, or ordinary non-workflow child-session inheritance semantics.

## Desired behaviour

When a workflow run is executed from a session, workflow-owned child sessions created for that run should inherit their baseline preferences from that delegating session.

Preference families in scope for inheritance precedence:
- `:model`
- `:thinking-level`
- `:prompt-mode`
- tool / skill availability as already inherited from the parent session
- other workflow child-session preference families already designed to inherit from the parent session unless explicitly authored by the workflow

Precedence should be:
1. explicit workflow-authored step/session override
2. delegating session active value
3. workflow meta fallback where that is already part of the design
4. only then broader defaults if no delegating session is available

The key correction is that the delegating session must beat unrelated context-session defaults.

## Problem shape

Current workflow session-config resolution already accepts an explicit `parent-session-id`, but some call paths can omit it and then fall back to the first listed context session.
That fallback is too weak for workflow delegation because:
- context ordering is not the same thing as delegation authority
- the delegating session is the authoritative owner of the workflow invocation
- using another session can silently swap model and adjacent preferences

This is especially surprising for model selection, since users often change the current session model explicitly and expect delegated workflow work to continue under that choice.

## Architectural intent

The authoritative parent for workflow child-session inheritance should be the session that initiated execution of the workflow run.

The smallest coherent fix is to make workflow execution preserve and propagate that delegating session identity through the workflow execution path, then have step session-config resolution read inheritance from that authoritative session rather than guessing from context-session ordering.

That means:
- workflow runtime/session-config resolution should not infer parent authority from `list-context-sessions` when a delegating session id is known
- higher workflow execution entrypoints should preserve the delegating session id consistently through create/run/execute/resume paths
- fallback-to-first-context-session may remain only as a defensive compatibility path when no authoritative parent session id exists at all

## Control/data flow

Desired flow:

```clojure
user/delegate from session S
→ workflow run execution entrypoint receives parent-session-id S
→ workflow statechart/runtime context carries parent-session-id S
→ resolve-step-session-config reads parent session S as inheritance base
→ workflow child-session creation persists the resolved preferences
→ child session runs with S-derived model and other inherited preferences unless the workflow explicitly overrides them
```

## Likely owners

Preference inheritance authority is likely to involve these surfaces:
- `components/agent-session/src/psi/agent_session/workflow_execution.clj`
- `components/workflow-runtime/src/psi/workflow_runtime/statechart_runtime.clj`
- `components/workflow-step-session-config/src/psi/workflow_step_session_config/core.clj`
- any workflow create/execute/resume mutation or psi-tool workflow surface that currently omits or fails to preserve the delegating session id

Tests likely need to cover:
- workflow execution from a session whose model differs from another context session
- explicit workflow override still wins over the delegating session
- fallback behaviour when no delegating session id exists remains coherent

## Constraints

- Do not widen this into a general rework of all session defaulting.
- Do not change ordinary top-level session initialization semantics unless required as a narrow fix for workflow inheritance.
- Preserve existing workflow-authored override precedence.
- Prefer passing authoritative parent-session identity explicitly over discovering it indirectly from context ordering.
- Avoid silent ambiguity: workflow child-session inheritance should have one obvious authoritative source.

## Acceptance

- Workflow child-session preference inheritance uses the delegating session as the authoritative parent when one exists.
- A delegating session's active `:model` is inherited by workflow-owned child sessions unless the workflow explicitly authors a different model.
- Other already-parent-inherited workflow child-session preferences follow the same authoritative delegating-session source rather than an unrelated context session.
- Existing workflow-authored explicit overrides still win.
- Focused proof covers the motivating case: two context sessions differ, workflow executes from one of them, and the child session inherits that delegating session's model rather than the other session's/default model.

## Task artifacts

This task uses:
- `design.md` for the stable intent, scope, behaviour, and acceptance surface
- `plan.md` for the implementation approach and risks
- `steps.md` for implementation execution steps only
- `design-steps.md` for actionable follow-up items produced by design/ambiguity review passes
- `implementation.md` for append-only in-flight decisions, discoveries, and blocking notes

Design-review follow-up items belong in `design-steps.md` and should be executed independently from `steps.md` work when explicitly requested.

## Explicit non-goals

- redesigning scheduler session-config inheritance
- changing ordinary non-workflow child-session APIs
- broad changes to user/project preference persistence
- introducing new user-facing commands or workflow config fields
