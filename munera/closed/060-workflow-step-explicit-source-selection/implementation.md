# 060 — Implementation notes

This task is Phase 1 extracted from umbrella task 059.

Key constraints:
- use `:session` immediately, not a temporary `:bind` syntax
- in this task, the recognized source-selection fields are `:input` and `:reference`
- prior-step references only in the first cut, validated by definition order
- compile into existing canonical `:input-bindings`
- keep prompt rendering semantics unchanged
- do not yet broaden scope into transcript projection, message preloading, or full session shaping

## 2026-04-27

Implemented the first-cut `:session` source-selection compiler in `workflow_file_compiler.clj`.

What landed:
- `:session` now accepts only `:input` and `:reference` for task 060
- source forms supported:
  - `:workflow-input`
  - `:workflow-original`
  - `{:step "<step-name>" :kind :accepted-result}`
- explicit source-selection step references resolve via author-facing step `:name` only
- explicit step references are restricted to earlier steps in definition order
- explicit source selection compiles to the existing canonical `:input-bindings` shapes
- absent or empty `:session` preserves prior defaults
- compile-time errors now cover:
  - unknown step name
  - forward step reference
  - malformed `:session` entry
  - unsupported `:projection` / `:project` before task 061
  - unsupported `:session` keys for task 060
  - duplicate author-facing step names in a multi-step workflow
- named `:goto` routing now resolves against author-facing step names, with legacy compatibility for unambiguous delegated workflow names

Test coverage added:
- compiler tests for explicit non-adjacent named source selection
- compiler tests for partial override and empty-session default preservation
- compiler validation tests for bad sources and unsupported keys
- loader tests for explicit named source selection and forward-reference load errors

## Review note

Terse review:
- implementation matches 060 intent and fits the compiler-first architecture
- validation and backward-compatibility handling are strong
- one documentation drift remains: source-selection references are explicit `:name` only, while legacy compatibility fallback was restored only for unambiguous `:goto` routing
- small follow-up recommended: document that boundary clearly and add direct negative coverage for malformed `:reference {}`

## Code-shaper review note

Terse review:
- implementation is well-shaped overall: compiler-local, canonical-output preserving, and validation-heavy in the right places
- main remaining shaping concern was policy accumulation in `workflow_file_compiler.clj`
- source-selection resolution, routing compatibility resolution, and compile-time validation now live in extracted authoring-resolution helpers
- mixed-purpose helper naming was also tightened during that extraction (`routing-target->step-id-map` now names the actual concern)
