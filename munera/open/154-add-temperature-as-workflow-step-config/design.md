# 154 — Add temperature as workflow step session config option

## Intent

Allow workflow authors to set `:temperature` on any `:type :session` step (and judge specs) so the AI provider receives an explicit sampling temperature rather than its built-in default.

## Scope

Thread `:temperature` through the full workflow → provider chain:
- IR schema: `session-spec-schema` accepts optional `:temperature [0.0, 2.0]`
- Target IR compiler: `select-keys` includes `:temperature` for session and judge steps
- Step session config: `resolve-step-session-config` conditionally assocs `:temperature`
- Child session contract: `request-schema` accepts optional `:temperature`
- Statechart runtime: `step/enter` propagates `:temperature` to attempt creation
- Child session state: `child-session-base-state` stores `:temperature` when present
- Session state model: `agent-session-schema` accepts optional `:temperature`
- Prompt request: `session->request-options` projects `:temperature` into AI options

## Constraints

- Temperature is opt-in: absent = provider default applies
- Explicit 0.0 flows through as 0.0 (overrides provider default)
- Range: [0.0, 2.0] validated at IR layer

## Acceptance

- Workflow step with `:temperature 0.0` produces request with `temperature: 0.0`
- Workflow step without `:temperature` does not inject a temperature key into request options
- Tests cover absent and explicit (0.0) cases at step-session-config and prompt-request layers
