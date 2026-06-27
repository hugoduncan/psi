# Disable auto-session-name for delegated workflows

## Intent

`auto-session-name` should rename only top-level, user-interactive sessions. It must not schedule or run rename inference for sessions created to execute delegated workflows, workflow steps, nested workflow runs, or helper sessions.

## Problem

The extension currently reacts to every `session_turn_finished` event except sessions it created as its own helper. Delegated workflow sessions also produce turns, so the extension can waste model calls, create extra helper sessions, emit irrelevant notifications, and mutate names for implementation/review/planning child sessions whose names are authored by workflow/runtime context rather than by a human-facing conversation.

## Scope

In scope:

- Gate `auto-session-name` eligibility so only top-level user-interactive sessions can accumulate turn counts and schedule rename checkpoints.
- Treat delegated/workflow-owned sessions as ineligible even if they have visible user/assistant messages.
- Ensure already-scheduled checkpoints for now-ineligible sessions are no-ops and do not run rename inference.
- Preserve the existing self-helper guard: auto-session-name helper child sessions remain ineligible.
- Add regression coverage for delegated workflow sessions and top-level sessions.
- Update user/developer documentation that describes auto-session-name behavior.

Out of scope:

- Redesigning the session tree or workflow session model.
- Renaming existing historical sessions or repairing names already changed by the extension.
- Changing how workflow/session names are assigned by workflow runtime.
- Changing helper model selection or title prompt semantics.

## Desired behavior

A session is eligible for `auto-session-name` only when it is a top-level user-interactive session:

- It has no parent session relationship.
- It is not owned by a workflow run/step/attempt.
- It is not a helper session created by `auto-session-name` itself.

A delegated workflow session is ineligible when session state/event context indicates any of:

- `:workflow-run-id` is present.
- workflow step/attempt ownership metadata is present.
- `:workflow-owned?` is true.
- `:parent-session-id` is present because it is a child/delegated session.

If the runtime has a more authoritative explicit “interactive top-level session” marker, prefer that single predicate over re-deriving the above from multiple fields. If no such explicit marker exists, treat a root session as user-interactive for this extension when it has no parent session relationship, has no workflow run/step/attempt ownership metadata, is not marked `:workflow-owned?`, and is not an auto-session-name helper session. Do not add a new interactivity projection solely for this task unless the existing extension-safe session query surface cannot expose those existing ownership fields. The implementation should keep the eligibility decision local and testable rather than scattering checks across handlers.

## Acceptance criteria

1. When a normal top-level user-interactive session finishes enough turns to hit the configured interval, `auto-session-name` still schedules the rename checkpoint exactly as before.
2. When a delegated workflow child session finishes turns, `auto-session-name` does not increment its rename turn count and does not schedule a checkpoint.
3. When a checkpoint event fires for an ineligible delegated/workflow-owned session, the extension does not query conversation history, create a helper child session, run an agent loop, set the session name, or emit the fallback checkpoint notification.
4. `auto-session-name` helper sessions remain ineligible and do not recursively trigger naming work.
5. Manual override and stale checkpoint guards continue to work for eligible top-level sessions.
6. Tests cover at least:
   - eligible top-level session schedules/runs through the existing path;
   - workflow-owned/delegated child session is ignored on turn-finished;
   - workflow-owned/delegated child session checkpoint is a no-op;
   - helper session remains ignored.
7. Documentation states that automatic naming applies only to top-level user-interactive sessions and explicitly excludes delegated workflow sessions.

## Implementation notes

Likely starting points:

- `extensions/auto-session-name/src/extensions/auto_session_name.clj`
- `extensions/auto-session-name/test/extensions/auto_session_name*_test.clj`
- Session metadata is created in/around `components/agent-session/src/psi/agent_session/child_session_state.clj` and exposed through extension query/mutation APIs. Use existing extension-safe session query surfaces; do not reach directly into the global atom from the extension.

Preferred shape:

- Add one predicate such as `eligible-source-session?` / `top-level-interactive-session?` that takes the extension API and session id, queries the minimal authoritative session metadata, and returns a boolean.
- Call that predicate before incrementing turn counts and before checkpoint inference.
- Make ineligible checkpoints silently no-op to avoid delegated workflow noise.
- If current extension query surfaces cannot expose the needed metadata, add the smallest resolver/API projection required rather than special-casing workflow internals in the extension.

## Risks and constraints

- Do not break scheduled top-level sessions created by the scheduler if they are intended to be user-interactive top-level sessions; eligibility should be based on top-level/interactivity rather than “created by TUI only”.
- Avoid a broad compatibility shim. The contract should be explicit: workflow/delegated child sessions are not auto-named.
- Keep behavior deterministic and testable without model calls by using existing extension test seams.
