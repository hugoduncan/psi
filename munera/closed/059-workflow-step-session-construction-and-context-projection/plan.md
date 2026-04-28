# 059 — Plan

## Approach

Treat this as a large-scope workflow authoring capability task with a deliberately incremental implementation path.

Do not start by trying to land the entire session-construction model in one pass. Start by defining the final architecture and then ship it in slices that each produce a user-visible gain without compromising the intended direction.

## Architectural objective

Move workflow-step authoring up one level:
- from implicit previous-step prompt chaining
- to explicit session construction and reference-context projection

The implementation should preserve the canonical workflow runtime and make the workflow file compiler / step-preparation path responsible for translating author intent into canonical runtime data.

## End-state model

Target end-state for a multi-step workflow step:
- `:workflow` / executor selection
- `:session` block for child-session shaping and preloaded/reference context
- optional `:bind` block for prompt-template convenience
- `:prompt` for the submitted step prompt
- optional `:judge` / `:on` for routing

The compiler should resolve author-facing names to canonical step ids and runtime-consumable structures.

## Child tasks

The implementation is now split into child tasks:
- `060` — explicit source selection
- `061` — minimal projections
- `062` — step-level session shaping overrides
- `063` — reference message/transcript projection
- `064` — workflow authoring convergence and examples

## Child-task responsibilities

Task `059` owns the overall design and the intended end-state authoring model.

Concrete implementation responsibilities are delegated as follows:
- `060` — explicit source selection for current working input/reference channels
- `061` — minimum projection vocabulary (`:text`, `:full`, `:path [...]`)
- `062` — per-step session shaping overrides for system prompt / tools / skills / model / thinking
- `063` — constrained reference/preloaded message or transcript projection plus canonical source-of-truth decision
- `064` — examples, docs, validation clarity, and final authoring convergence

The umbrella should not duplicate the implementation checklists from those child tasks. It should instead ensure they continue to fit one coherent session-first authoring story.

## Likely implementation surfaces

Primary code areas:
- `components/agent-session/src/psi/agent_session/workflow_file_compiler.clj`
- `components/agent-session/src/psi/agent_session/workflow_file_loader.clj`
- `components/agent-session/src/psi/agent_session/workflow_step_prep.clj`
- workflow execution/runtime namespaces where child-session creation and preloading occur

Likely test areas:
- workflow compiler tests
- workflow loader tests
- workflow execution/runtime tests
- modular workflow examples where useful

## Key design decisions settled for initial implementation

1. **Authoring syntax shape**
   - begin with `:session` immediately
   - do not begin with a throwaway prompt-binding-only syntax
   - `:bind` may remain a later convenience surface, but is not the primary first-cut design

2. **Reference naming**
   - use author-facing workflow-step names in files, compile to canonical step ids internally

3. **Allowed source directions**
   - explicit step references target only prior steps in definition order in the first implementation cut
   - forward references are compile/load errors

4. **Projection vocabulary**
   - first-cut vocabulary is `:text`, `:full`, and `:path [...]`
   - richer transcript-tail/tool-filter projection is later-phase work

5. **Prompt-binding role**
   - prompt bindings are convenience channels, not the primary abstraction
   - arbitrary named prompt variables are out of scope for the first implementation cut

6. **Override semantics**
   - step-specified tools/skills/model/thinking replace delegated/default values
   - system prompt follows current composition rules unless a later explicit replace mode is introduced
   - runtime extension/workflow environment remains inherited by default

## Verification plan

The umbrella task should verify design coherence across child tasks, while each child task owns its direct implementation verification.

Umbrella-level verification should include:
1. checking that 060–064 preserve one compatible authoring model
2. checking that default session-construction semantics remain consistent as slices land
3. checking that docs/examples/convergence work in 064 still matches the lower-level implementation tasks

## Completion rule

This task is complete when the workflow authoring model can describe non-linear, modular workflow context flow in explicit session-oriented terms, and the code/tests/docs all tell the same story.
