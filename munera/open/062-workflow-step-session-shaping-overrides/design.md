# 062 — Workflow step session shaping overrides

## Goal

Expose selected per-step session-shaping metadata in workflow-file authoring.

## Context

Task 059 is the umbrella. This task extracts Phase 3 so workflow-file authoring can control system prompt, tools, skills, model, and thinking level explicitly.

## Scope

In scope:
- authoring for per-step overrides of:
  - system prompt
  - tools
  - skills
  - model
  - thinking level
- route overrides through `workflow_step_prep.clj`
- preserve default inheritance when overrides are absent
- implement the settled override semantics from task 059
- add focused tests

Out of scope:
- transcript/message preload projection
- broader session reuse semantics

## Acceptance

- [ ] Per-step session-shaping overrides are authorable under `:session`
- [ ] Default inherited behavior remains unchanged when overrides are absent
- [ ] Tools/skills/model/thinking overrides replace delegated/default values as designed
- [ ] System prompt follows current composition rules unless explicit replace mode is introduced later
- [ ] Focused tests prove override behavior
