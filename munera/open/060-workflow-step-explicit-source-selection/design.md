# 060 — Workflow step explicit source selection

## Goal

Implement the first slice of session-first workflow authoring by allowing a step to select its current working input/reference source explicitly under a `:session` block.

## Context

Task 059 is now the umbrella for workflow step session construction and context projection. This task extracts Phase 1 so it can land independently and solve the concrete non-adjacent branch/data-flow problem.

## Scope

In scope:
- add a minimal `:session`-based authoring surface for explicit source selection
- in this task, the recognized `:session` source-selection fields are:
  - `:input`
  - `:reference`
- support the closed first-cut source set:
  - `:workflow-input`
  - `:workflow-original`
  - `{:step "<step-name>" :kind :accepted-result}`
- restrict step references to earlier steps in definition order
- compile to canonical `:input-bindings`
- preserve current defaults when absent
- add validation and tests

Out of scope for this task's `:session` surface:
- transcript/message preloading
- transcript/session projection
- arbitrary additional `:session` keys not yet introduced by later child tasks

Out of scope:
- projection operators beyond what is required for default source selection
- transcript/message preload projection
- per-step tool/skill/model/thinking/session override work beyond what is necessary to compile the new source-selection surface

## Authoring shape for this task

Task 060 should introduce a narrow `:session` source-selection form.

Preferred initial shape:

```clojure
{:name "request-more-info"
 :workflow "gh-bug-request-more-info"
 :session {:input {:from {:step "reproduce" :kind :accepted-result}}
           :reference {:from :workflow-original}}
 :prompt "$INPUT"}
```

Equivalent workflow-input example:

```clojure
{:name "report"
 :workflow "reporter"
 :session {:input {:from :workflow-input}
           :reference {:from :workflow-original}}
 :prompt "$INPUT"}
```

In task `060`, `{:step "<step-name>" ...}` refers to the author-facing step `:name`, not the delegated `:workflow` name.

Task-060-specific semantics:
- `:input` controls the current working input channel only
- `:reference` controls the built-in reference/original prompt channel only
- `:session :input` determines the canonical binding consumed by `$INPUT`
- `:session :reference` determines the canonical binding consumed by `$ORIGINAL`
- neither field preloads transcript/message context into the child session; that later capability belongs to task `063`
- task `060` introduces source selection only; it does not introduce a projection key such as `:project` on `:input` or `:reference`
- projection vocabulary is deferred to task `061`
- unspecified source-selection fields preserve existing defaults
- empty `:session {}` is equivalent to an absent `:session` block for this task

### Default source meanings in task 060

For this task, source selection should compile with explicit default meanings:
- `{:from :workflow-input}` under `:session :input`
  - compiles to the existing canonical workflow-input binding at path `[:input]`
- `{:from :workflow-original}` under `:session :reference`
  - compiles to the existing canonical workflow-input binding at path `[:original]`
- `{:from {:step "<step-name>" :kind :accepted-result}}` under `:session :input`
  - compiles to the existing canonical accepted-result binding shape/path already used for previous-step chaining
  - i.e. this task preserves current linear accepted-result semantics while allowing the author to select a named earlier step explicitly

Task `060` does not redefine the canonical `:input-bindings` structure. It reuses the existing canonical binding shapes and makes the source-selection authoring explicit.

Task 060 changes source-selection authoring only. Prompt rendering semantics remain unchanged and continue to consume canonical `:input-bindings`.

### Validation rules

- prior-step validation is by workflow definition order, not runtime reachability
- partial overrides are allowed:
  - specifying only `:input` leaves `:reference` at its current default
  - specifying only `:reference` leaves `:input` at its current default
- present-but-empty `:input {}` or `:reference {}` is malformed
- validation errors should distinguish at least:
  - malformed source form
  - unknown step name
  - forward reference to a later-defined step
  - use of projection-oriented keys such as `:project` before task `061` lands
- unsupported `:session` keys for this task should fail clearly rather than being silently ignored while task `060` alone defines the implemented `:session` surface

## Acceptance

- [ ] Workflow-file authoring supports explicit source selection under `:session`
- [ ] Existing workflows with no `:session` source selection continue to compile unchanged
- [ ] Named step references resolve to prior steps only
- [ ] Forward references fail validation clearly
- [ ] Explicit source selection compiles to canonical `:input-bindings`
- [ ] Compiler/loader tests cover non-adjacent branch-safe source selection
