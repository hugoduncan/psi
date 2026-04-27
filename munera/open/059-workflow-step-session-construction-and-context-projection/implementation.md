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

Even though the task is large, start with the smallest slice that fixes the concrete data-flow problem:
- explicit source selection for current working input/reference channels
- compile to canonical input bindings
- validate clearly

Then grow upward into the broader session-shaping model.

This gives immediate value while keeping the architecture pointed at the right abstraction.

## Open questions to settle during implementation

- What exact first-phase authoring form is the cleanest on the path toward a full `:session` block?
- Should workflow-file references be restricted to prior steps only in the first cut?
- What canonical representation should message/transcript preload projections compile to?
- How should prompt-binding convenience relate to richer session preloading once both exist?
- Which existing helpers or seams should own synthetic preloaded messages for workflow steps?
