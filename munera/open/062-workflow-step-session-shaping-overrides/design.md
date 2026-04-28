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
- keep those override keys as peer keys alongside task-060/task-061 `:session :input` and `:session :reference` entries, not as a replacement authoring surface
- route overrides through `workflow_step_prep.clj`
- preserve default inheritance when overrides are absent
- implement the settled override semantics from task 059
- add focused tests

Out of scope:
- transcript/message preload projection
- broader session reuse semantics

## Authoring shape

Task `062` should extend the existing `:session` map rather than introducing a second override container.

Preferred shape:

```clojure
{:name "review"
 :workflow "reviewer"
 :session {:input {:from {:step "build" :kind :accepted-result}}
           :reference {:from :workflow-original}
           :system-prompt "Focus only on correctness, edge cases, and missing tests."
           :tools ["read" "bash"]
           :skills ["testing-best-practices"]
           :model "gpt-5"
           :thinking-level :high}
 :prompt "Review the following implementation:\n\n$INPUT\n\nOriginal request: $ORIGINAL"}
```

Task-062-specific semantics:
- `:system-prompt`, `:tools`, `:skills`, `:model`, and `:thinking-level` are peer keys under `:session`
- task `062` does not change task-060 source-selection semantics or task-061 projection semantics
- `:tools`, `:skills`, `:model`, and `:thinking-level` replace delegated/default values when explicitly present
- `:system-prompt` follows current composition rules unless a later task introduces explicit replace semantics
- absent override keys preserve delegated/default inheritance
- empty `:session {}` remains equivalent to an absent `:session` block
- empty collections are meaningful overrides:
  - `:tools []` means no delegated/default tools are selected for the step
  - `:skills []` means no delegated/default skills are selected for the step

## Validation

Validation should reject at least:
- malformed override value types
- unsupported `:session` keys once the implemented surface for tasks `060`–`062` is known
- malformed combinations that would violate the settled override semantics

## Acceptance

- [ ] Per-step session-shaping overrides are authorable under `:session`
- [ ] Override keys compose coherently with task-060/task-061 `:session` source/projection entries
- [ ] Default inherited behavior remains unchanged when overrides are absent
- [ ] Tools/skills/model/thinking overrides replace delegated/default values as designed
- [ ] System prompt follows current composition rules unless explicit replace mode is introduced later
- [ ] Focused tests prove override behavior
