# 060 — Workflow step explicit source selection

## Goal

Implement the first slice of session-first workflow authoring by allowing a step to select its current working input/reference source explicitly under a `:session` block.

## Context

Task 059 is now the umbrella for workflow step session construction and context projection. This task extracts Phase 1 so it can land independently and solve the concrete non-adjacent branch/data-flow problem.

## Scope

In scope:
- add a minimal `:session`-based authoring surface for explicit source selection
- support the closed first-cut source set:
  - `:workflow-input`
  - `:workflow-original`
  - `{:step "<step-name>" :kind :accepted-result}`
- restrict step references to earlier steps in definition order
- compile to canonical `:input-bindings`
- preserve current defaults when absent
- add validation and tests

Out of scope:
- projection operators beyond what is required for default source selection
- transcript/message preload projection
- per-step tool/skill/model/thinking/session override work beyond what is necessary to compile the new source-selection surface

## Acceptance

- [ ] Workflow-file authoring supports explicit source selection under `:session`
- [ ] Existing workflows with no `:session` source selection continue to compile unchanged
- [ ] Named step references resolve to prior steps only
- [ ] Forward references fail validation clearly
- [ ] Explicit source selection compiles to canonical `:input-bindings`
- [ ] Compiler/loader tests cover non-adjacent branch-safe source selection
