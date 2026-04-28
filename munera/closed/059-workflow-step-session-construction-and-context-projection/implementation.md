# 059 — Implementation notes

## Initial context

This task was reframed from explicit step input bindings to a broader session-first workflow authoring model.

Reason for reframing:
- the originally observed failure was a branch/data-flow mismatch in `gh-bug-triage-modular`
- a narrow fix for `:input` binding would solve one symptom
- the deeper authoring need is control over the child session being created for a workflow step, including reference context, system prompt, tools, skills, model, and thinking

## Key design constraint

Do not solve this with runtime heuristics based on execution history.

Examples of what to avoid:
- "most recently executed step"
- "infer the likely source from the branch path"
- hidden fallback rules that make workflow data/context flow hard to reason about

## Working hypothesis

The likely implementation center of gravity is still:
- `workflow_file_compiler.clj`
- `workflow_file_loader.clj`
- `workflow_step_prep.clj`
- targeted execution/runtime tests

The goal is not to replace the canonical runtime but to expose and shape it more deliberately.

## Current design bias

- session-first authoring
- explicit source selection
- constrained projections
- author-facing step names in workflow files
- compiler resolution to canonical step ids
- backward-compatible defaults preserved
- incremental implementation in slices

## Early implementation recommendation

The work is now split into child tasks so the umbrella can stay focused on coherence rather than direct implementation.

Recommended execution order:
- `060` explicit source selection for current working input/reference channels
- `061` minimal projections
- `062` step-level session shaping overrides
- `063` reference message/transcript projection
- `064` convergence/examples

This preserves immediate value while keeping the architecture pointed at the right abstraction.

## Remaining open questions during implementation

- What exact canonical representation should message/transcript preload projections compile to?
- How should prompt-binding convenience relate to richer session preloading once both exist?
- Which existing helpers or seams should own synthetic preloaded messages for workflow steps?
- Whether a later explicit system-prompt replace mode is needed beyond the current composition default

## Initial decisions now fixed by umbrella design

- child task `060` uses `:session` as the first implementation authoring surface
- child task `060` restricts explicit step references to prior steps only
- child task `060` rejects forward references as compile/load errors
- child task `061` introduces the first projection vocabulary: `:text`, `:full`, and `:path [...]`
- arbitrary named prompt variables are out of scope for the first cut

## Review note

Terse review after `060`–`064`:
- the implementation is architecturally strong and converges well on compiler-first, session-first workflow authoring
- the main remaining drift is that the umbrella spec says multi-step steps require author-facing unique `:name`, but implementation still preserves compatibility for unnamed multi-step steps
- because we are aiming to complete the spec rather than preserve compatibility, the next follow-on should remove that drift and make the authoring/documentation/tests tell one story
- smaller follow-on: update the umbrella task state so `059` reflects the landed child-task convergence and any intentional remaining gaps explicitly

## 2026-04-28 follow-on

- enforced the spec-first rule that all multi-step workflow steps must provide a unique string `:name`
- removed routing compatibility fallback from delegated workflow names to step ids; named routing is now step-`:name` only
- updated compiler, loader, and parser tests to use explicit step names in multi-step fixtures
- replaced the old unnamed-step compatibility test with a failure test proving missing multi-step step names are rejected clearly
- reran focused workflow authoring/compiler/loader/migration verification after the enforcement change (`22 tests, 183 assertions, 0 failures`)
- reran full unit verification after the spec-completion change (`1456 tests, 10806 assertions, 0 failures`)

## Code-shaper review note

Terse code-shaper review after `059`–`064`:
- the implementation is now well-shaped as a coherent session-first workflow authoring subsystem rather than a series of workflow-file patches
- the strongest design choice is compiling author-facing `:session` syntax into canonical runtime-facing forms (`:input-bindings`, `:session-overrides`, `:session-preload`) while keeping runtime materialization in `workflow_step_prep.clj`
- separation of concerns is good: source/projection/overrides, preload, routing, compilation, and runtime prep each have a clearer owner
- the spec is now singular: multi-step authoring, source references, preload references, and named routing all converge on explicit step `:name`
- main remaining shaping caution: do not let `workflow_file_compiler.clj` become the next oversized policy hub; if this surface grows further, consider extracting more multi-step assembly/validation helpers
- smaller shaping caution: `workflow_file_authoring_resolution.clj` now behaves mostly as façade glue; later either keep it intentionally as a stable façade or remove it
