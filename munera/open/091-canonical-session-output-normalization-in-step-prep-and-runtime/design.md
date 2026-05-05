Goal: eliminate the remaining canonical-session-output normalization seam between workflow IR/compiler semantics and runtime step preparation/statechart execution so session output lookup is defined in one place and no longer duplicated across workflow execution paths.

## Intent

Task `077` established canonical session output naming around `:final-llm-reply` at the target grammar, IR, and compatibility-compiler layers.

A follow-on implementation seam remains in runtime execution:

- `workflow_step_prep.clj` still contains compatibility-shaped knowledge about how session outputs are recovered
- statechart/runtime step-entry behavior has already shown duplication risk around session conversation vs compat preload handling
- the system still relies on more than one place understanding how session-step results map to canonical output/yield surfaces

This task should finish that convergence so canonical session output semantics are defined once and consumed consistently by both the direct workflow execution path and the Phase A statechart runtime path.

## Problem statement

The current workflow implementation has mostly converged on canonical session output naming, but runtime preparation and execution still carry localized compatibility knowledge.

Symptoms include:

- duplicated or overlapping runtime behavior around session-step preparation
- risk of drift between compiler/IR semantics and runtime lookup semantics
- bugs that surface as mismatched preload behavior or inconsistent interpretation of session-step results
- difficulty reasoning about whether `:yield :text`, `:output :final-llm-reply`, compat `:outputs :text`, and related session-result reads all pass through one authoritative normalization boundary

The most important architectural issue is not a single failing test. It is that session output normalization is still partly a distributed concern.

## Scope

In scope:

- identify the single authoritative boundary where session-step accepted-result data is normalized to canonical output/yield semantics
- prefer an existing layer already closest to canonical output interpretation over introducing a new parallel helper/seam; runtime callers should delegate to that authoritative layer rather than restating translation logic sideways or upward
- remove duplicated or competing session-output translation logic from runtime step-preparation and/or statechart execution paths
- make direct workflow execution and statechart workflow execution consume the same canonical session-output interpretation
- preserve intended current externally visible workflow behavior while simplifying internal lookup/translation responsibilities
- add or update focused tests that prove canonical behavior for:
  - `:output :final-llm-reply`
  - `:yield :text`
  - interaction between canonical session contributions and compat preload handling where still supported
- document any narrow remaining compatibility rule if one must still exist temporarily

Behavior-change rule:

- accidental or compatibility-era duplication may be removed when doing so makes canonical behavior more internally consistent, provided the resulting behavior is made explicit in focused tests and the change does not broaden the task into a larger workflow-runtime redesign

Out of scope:

- broad redesign of workflow step preparation beyond the output-normalization seam
- retiring the whole current-authored grammar
- unrelated prompt lifecycle or child-session architecture work
- expanding workflow authoring surfaces beyond what task `077` already decided

## Desired outcome

There is one obvious place in the runtime/codebase that defines canonical session output normalization, and both workflow execution paths rely on it.

A future reader should be able to answer these questions by reading one small area of code:

- how a session step exposes `:output :final-llm-reply`
- how a session step exposes `:yield :text`
- whether any compat `:outputs :text` reading is still allowed, and if so where that compatibility is contained

## Acceptance

- canonical session output normalization is centralized enough that runtime step preparation no longer duplicates the output-translation concern
- both direct workflow execution and Phase A statechart execution use the same canonical session-output interpretation
- focused tests prove canonical `:output :final-llm-reply` and `:yield :text` behavior through the chosen boundary
- previously observed preload duplication/interaction behavior is either eliminated by simplification or explicitly contained and tested
- the resulting implementation is simpler and less drift-prone than the current split responsibility
