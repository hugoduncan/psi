# Plan

Implementation is complete (committed as `55d41bb0`). This task directory was created post-hoc for review tracking.

## What was done

1. IR schema — `:temperature` optional on `session-spec-schema`
2. Target IR compiler — `:temperature` in `select-keys` for session and judge steps
3. Step session config — `(contains? session-spec :temperature)` guard, assocs value
4. Child session contract — `:temperature` optional in `request-schema`
5. Statechart runtime — `(contains? step-config :temperature)` guard in step/enter
6. Child session state — `(some? temperature)` guard in `child-session-base-state`
7. Session state model — `:temperature` optional in `agent-session-schema`
8. Prompt request — `(some? (:temperature session-data))` guard in `session->request-options`

## Follow-up (from review)

See steps.md.
