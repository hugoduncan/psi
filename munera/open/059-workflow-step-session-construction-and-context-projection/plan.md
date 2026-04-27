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

## Implementation phases

### Phase 1 — Explicit source selection for step inputs

Goal: solve the immediate non-adjacent data-flow problem without waiting for the whole session model.

Deliverables:
- add a minimal `:session`-based authoring surface for explicit source selection on step input/reference channels
- support the closed first-cut source set:
  - `:workflow-input`
  - `:workflow-original`
  - `{:step "<step-name>" :kind :accepted-result}`
- restrict step references to earlier steps in definition order
- compile to canonical `:input-bindings`
- preserve current defaults when absent
- add compile/load validation and tests

This phase is intentionally narrower than the whole task but aligned with the session-first design.

### Phase 2 — Minimal projection vocabulary

Goal: make source selection genuinely useful.

Deliverables:
- support constrained projections `:text`, `:full`, and `:path [...]`
- keep the projection surface under the `:session`-first authoring model rather than introducing a temporary throwaway syntax
- add validation for unsupported projection forms and malformed paths
- add tests covering structured-field extraction and branch-safe non-adjacent source use

### Phase 3 — Step-level session shaping

Goal: expose already-existing session-construction concerns in workflow authoring.

Deliverables:
- step-level authoring for selected session-shaping metadata:
  - system prompt
  - tools
  - skills
  - thinking level
  - model
- route these through `workflow_step_prep.clj`
- preserve delegated-workflow defaults when no override is supplied
- add tests showing per-step override behavior

### Phase 4 — Reference message/transcript projection

Goal: allow steps to preload projected context into the child session, not just bind prompt variables.

Deliverables:
- define a constrained reference/preload authoring surface under `:session`
- support at least one projected message/transcript form, likely reusing concepts from judge projection
- support optional tool-output stripping and tail selection if feasible
- settle one canonical source of truth for step-session message/transcript projection
- feed this into child-session creation/preloading paths
- add focused execution tests proving the preloaded context is visible to the step session

### Phase 5 — Authoring convergence and examples

Goal: make the final workflow authoring model coherent and proven.

Deliverables:
- update docs/examples
- revisit `gh-bug-triage-modular` and related modular workflow candidates
- decide whether a separate `:bind` convenience surface remains worth keeping or whether the `:session` model should own most author intent
- add any final validation/error-shaping needed for clarity

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

For each phase:
1. focused compiler/loader tests
2. focused execution/runtime tests for the new behavior
3. isolated workflow suite if applicable
4. full unit suite at meaningful checkpoints

## Completion rule

This task is complete when the workflow authoring model can describe non-linear, modular workflow context flow in explicit session-oriented terms, and the code/tests/docs all tell the same story.
