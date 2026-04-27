# 063 — Workflow step reference message projection

## Goal

Allow workflow steps to preload constrained projected message/transcript context into child sessions.

## Context

Task 059 is the umbrella. This task extracts Phase 4 so session-first workflow authoring can shape reference conversation context, not just current input bindings.

## Scope

In scope:
- define authoring for reference/preloaded context under `:session`
- support at least one projected message/transcript form
- settle one canonical source of truth for step-session message/transcript projection
- support optional tail selection and tool-output stripping if feasible within the task
- feed projected context into child-session creation/preloading
- add focused execution tests

Out of scope:
- arbitrary transcript transformation logic
- broad session reuse redesign

## Acceptance

- [ ] `:session` can describe constrained reference/preloaded message context
- [ ] One canonical source of truth for projected step-session messages is documented and used
- [ ] At least one transcript/message projection form is supported
- [ ] Focused execution tests prove the projected context reaches the child session
