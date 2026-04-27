# 059 — Workflow step session construction and context projection

## Goal

Act as the umbrella/orchestration task for the workflow session-first authoring initiative.

Raise workflow-step authoring from narrow prompt-input wiring to explicit child-session construction.

A workflow author should be able to declare, per step:
- what child session is created or reused for the step
- what system prompt, framing prompt, tools, skills, model, and thinking level that session should use
- what reference context is preloaded into that session
- how that reference context is projected from workflow input, prior step outputs, or prior session/transcript material
- what prompt is then submitted into that prepared session

Prompt input binding remains useful, but it should be treated as one small part of a larger session-construction model rather than the primary abstraction.

## Problem

The current `.psi/workflows/*.md` authoring surface is strongest at linear prompt chaining and weakest at explicit step context/session control.

Today, multi-step workflow compilation mostly assumes:
- first step input comes from workflow input
- later step input comes from the previous step's accepted result text
- child-session shaping is derived elsewhere from workflow metadata and delegated workflow defaults
- prompt rendering is centered on `$INPUT` and `$ORIGINAL`

That is workable for simple chains, but it becomes limiting and misleading when workflows are modular or non-linear.

Concrete failure mode already observed:
- a workflow can branch control flow to a non-adjacent step
- but data flow still points at the previous step in file order
- the branch target may therefore receive the wrong input, or input from a step that never ran

More importantly, the real authoring need is broader than input wiring:
- steps often need deliberate session shaping, not just different prompt substitutions
- authors may need to preload some or all of a prior conversation
- authors may need transcript-tail projections, tool-output stripping, accepted-result extraction, or field/path selection
- authors may need to override tools, skills, system prompt, model, or thinking for one specific step

Treating these as ad hoc prompt-binding problems is too low-level and will keep producing awkward patches.

## Intent

Define a workflow-file authoring model where a step describes the session it wants to run in.

The conceptual flow for a step should become:
1. derive step-local session spec
2. project/select reference context from explicit sources
3. construct or shape the child session using that spec and reference context
4. optionally derive prompt bindings for concise prompt templates
5. submit the step prompt

This separates:
- control flow
- data flow
- session shaping
- prompt text rendering

## Scope

In scope:
- define the umbrella design and boundaries for the session-first workflow authoring initiative
- split implementation into concrete child tasks
- keep the cross-task design consistent as the slices land
- retain backward compatibility requirements across all child tasks
- track the end-state authoring model, defaults, and constraints
- update umbrella guidance as design decisions sharpen

The concrete implementation work is expected to land through child tasks:
- `060` explicit source selection
- `061` minimal projections
- `062` step-level session shaping overrides
- `063` reference message/transcript projection
- `064` authoring convergence and examples

Out of scope:
- replacing the canonical workflow runtime/statechart model
- adding arbitrary user scripting or transformation DSLs inside workflow files
- making session shaping depend on implicit execution-history guesses like "most recently executed step"
- redesigning extension workflow runtimes unrelated to canonical deterministic workflows

## Desired outcome

Workflow steps are authored in terms of explicit session construction and context projection rather than implicit prompt chaining.

Examples that should become straightforward and unsurprising:
- a branch target that consumes the reproduction report from the reproduction step regardless of file order
- a review step that sees the original request, the plan, and the implementation with controlled projection
- a judge step that sees a projected conversation tail without tool output
- a step that runs with a temporary tool/model/skill override while still participating in the canonical workflow runtime

## Core design principles

- **Session-first authoring**: describe the child session to run, not only prompt substitutions.
- **Explicit source selection**: authors should name where context comes from.
- **Projection over transformation**: allow structured selection/filtering, not arbitrary code.
- **Backward compatibility**: existing workflows continue to mean what they mean today.
- **Compile-time clarity**: invalid references and malformed projections should fail early.
- **Incremental delivery**: land the smallest useful slices first without locking in a bad overall model.

## Proposed authoring model

Each step may gain an optional session-shaping block named `:session`.

This task should treat `:session` as the primary authoring surface from the first implementation slice onward. A separate `:bind` surface may still exist as a convenience later, but the task should not begin with a throwaway prompt-binding-only syntax that has to be replaced immediately.

Illustrative shape:

```clojure
{:workflow "reviewer"
 :session {:system-prompt "...optional override..."
           :tools ["read" "bash"]
           :skills ["review-skill"]
           :thinking-level :high
           :model "...optional model..."
           :reference {:sources [...]
                       :mode :preloaded-messages}}
 :prompt "Review the following implementation:\n\n$INPUT\n\nOriginal request: $ORIGINAL"}
```

The session block is the high-level authoring surface. Prompt bindings are subordinate to it.

### Phase 1 concrete syntax target

The first implementation slice should still use `:session`, even if narrowly. Preferred starting shape:

```clojure
{:workflow "gh-bug-request-more-info"
 :session {:input {:from {:step "gh-bug-reproduce"
                          :kind :accepted-result}
                   :project :text}
           :reference {:from :workflow-original}}
 :prompt "$INPUT"}
```

Equivalent explicit workflow-input example:

```clojure
{:workflow "reporter"
 :session {:input {:from :workflow-input}
           :reference {:from :workflow-original}}
 :prompt "$INPUT"}
```

This gives Phase 1 a stable home for explicit source selection without committing the task to a prompt-binding-centric design.

### Session-shaping concerns

At minimum, the design should account for step-level control of:
- `:system-prompt`
- `:tools`
- `:skills`
- `:thinking-level`
- `:model`
- reference/preloaded context

The implementation may land these incrementally rather than all at once, but the design should keep them unified.

### Reference context sources

A reference-context source should target a closed, explicit set of workflow-visible data.

Phase 1 supported source set should be:
- `:workflow-input`
- `:workflow-original`
- `{:step "<step-name>" :kind :accepted-result}`

Later phases may add:
- `{:step "<step-name>" :kind :session-transcript}`
- narrowly justified workflow runtime metadata sources if a concrete need emerges

Preferred author-facing references use stable step names/workflow names from the file, not compiled step ids.

### Source-direction rule

For the first implementation cut, explicit step references should target only earlier steps in definition order.

Forward references should be rejected as load-time/compile-time errors. This avoids cyclic or speculative data-flow semantics and keeps Phase 1 deterministic.

### Projection surface

This task should include a projection model that is useful for both:
- prompt/input extraction
- preloaded/reference conversation shaping

The projection vocabulary should stay constrained.

Phase 2 minimum vocabulary should be:
- `:text`
- `:full`
- `:path [...]`

Later phases may add:
- transcript-tail projection like `{:type :tail :turns N}`
- optional tool-output stripping for transcript projections

Projection should describe selection and filtering, not general computation.

### Prompt bindings

Prompt bindings should remain supported, but as a lower-level convenience.

Near-term expectation:
- keep built-in prompt channels such as current working input and original/reference context
- allow them to be derived from explicit sources/projections
- do not center the whole design around `$INPUT`/`$ORIGINAL`
- do not introduce arbitrary named prompt variables in the first implementation cut

Prompt bindings and preloaded context should have distinct roles:
- session/reference projection shapes what context is present in the child session
- prompt bindings shape only prompt-template interpolation
- a step may use one, the other, or both
- prompt bindings do not implicitly preload context, and preloaded context does not implicitly populate prompt bindings unless the authoring model says so explicitly

This may mean a step eventually has both:
- `:session` for session construction
- `:bind` for prompt template convenience

## Default session construction

This task should make the default step-session construction explicit and preserve it unless a workflow step overrides part of it.

If a step has no explicit `:session` block, the default construction should be understood as:

1. **Create the step child session using the delegated workflow profile/default shape**
   - a multi-step workflow step still delegates to the referenced workflow as it does today
   - single-step workflows continue to use their own workflow metadata as today

2. **Compose prompts using existing default rules**
   - use the delegated workflow system prompt when present
   - compose in the parent workflow framing prompt when present and appropriate
   - preserve current prompt-template behavior for `$INPUT` / `$ORIGINAL` or equivalent prompt-binding defaults

3. **Inherit capability and environment defaults from the delegated workflow and parent session/runtime**
   - use delegated workflow metadata first when present
   - this includes not only system prompt, tools, skills, model, and thinking level, but also the effective extension/workflow environment that makes the delegated workflow usable
   - session construction should continue to preserve the sensible current baseline for:
     - tools
     - skills
     - model
     - thinking level
     - extensions available in the runtime/session environment
     - workflow definitions available in the runtime/session environment
   - inheritance/override semantics should be explicit in the implementation:
     - `:system-prompt` => compose using current default rules unless the new authoring surface explicitly introduces replace semantics
     - `:tools` => replace delegated/default tool selection when explicitly specified by the step
     - `:skills` => replace delegated/default skill selection when explicitly specified by the step
     - `:model` => replace when explicitly specified by the step
     - `:thinking-level` => replace when explicitly specified by the step
     - extensions/workflow environment => inherited from the runtime/session environment by default; not replaced by ordinary step-level shaping
   - parent-session or runtime fallbacks should remain in force where they exist today unless a step explicitly overrides them

4. **Use the current default data-flow bindings when no explicit source/projection is supplied**
   - first step current input -> workflow input
   - later step current input -> previous step accepted-result text
   - original/reference request -> workflow original request/context

5. **Do not preload additional reference context unless explicitly requested**
   - no new implicit transcript or message preload behavior should appear just because this task introduces a richer authoring model
   - richer preloaded/reference context should be opt-in and explicit

This default contract matters because the new authoring surface should be override-oriented rather than fully declarative-by-default. Authors need to know what baseline they are modifying when they add a `:session` block or explicit reference projection.

## Relationship to the existing runtime

This task should prefer extending the workflow file compilation and step-preparation path rather than rewriting the canonical runtime architecture.

Likely main implementation surfaces:
- `workflow_file_compiler.clj`
- `workflow_file_loader.clj`
- `workflow_step_prep.clj`
- targeted workflow execution/runtime tests

The runtime already has session-shaping concepts such as:
- system prompt composition
- tool resolution
- skill resolution
- thinking/model shaping
- prompt rendering
- delegated workflow metadata lookup
- access to the parent/runtime extension and workflow environment

This task should unify and expose those more deliberately in workflow-file authoring.

## Acceptance criteria

- [ ] Workflow-file authoring supports a step-level session-construction surface
- [ ] Existing workflows with no session block continue to compile and behave as they do today
- [ ] A step can explicitly select non-adjacent upstream sources without relying on file-order adjacency
- [ ] A step can project accepted-result text or other supported fields from a named prior step
- [ ] A step can project reference conversation/message context in at least one constrained supported form
- [ ] A step can override at least one session-shaping concern (for example tools/skills/system prompt/model/thinking) through workflow-file authoring
- [ ] Invalid source references and malformed projections fail with clear load-time or compile-time errors
- [ ] Branched workflow examples can express correct context/data flow directly
- [ ] Workflow compiler/loader/runtime tests cover backward compatibility plus new session/context-projection behavior
- [ ] Workflow docs/examples are updated to explain the new authoring model clearly

### Transcript/message projection source of truth

When transcript/message projection lands, it should read from one canonical step-session message source of truth rather than from ad hoc reconstructed values.

This task should settle and document that source explicitly during implementation. Current bias:
- prefer the same canonical message/transcript source used elsewhere for workflow/session reconstruction and deterministic behavior
- do not let different workflow paths project from different transcript representations implicitly

## Risks and traps to avoid

- Do not patch this with runtime heuristics like "bind to most recently executed step".
- Do not let the projection surface become a hidden programming language.
- Do not make step authors understand compiled step ids when file-level names suffice.
- Do not require all session-shaping capabilities to land before the first useful slice ships.
- Do not hide prompt-binding semantics inside session projection in a way that makes author intent opaque.

## Incremental delivery strategy

This is intentionally a large-scope task, but it should land in slices.

Recommended slice order:

1. **Source-selection and explicit accepted-result binding**
   - solve the immediate branch/non-adjacent data-flow problem
   - allow explicit source references to workflow input, workflow original, and named prior step accepted result
   - compile to canonical input bindings

2. **Minimal projection vocabulary**
   - add `:text`, `:full`, and `:path [...]`
   - validate clearly

3. **Step-level session shaping metadata**
   - expose per-step overrides for system prompt / tools / skills / model / thinking level
   - route through existing step prep helpers

4. **Reference message/transcript projection**
   - support a constrained message preload surface
   - likely reuse concepts already present in judge projection

5. **Workflow examples and cleanup**
   - revisit modular GitHub workflows and other examples
   - tighten docs/tests around the final authoring story

## Design recommendation

The first shipped slice does not need to solve every future session-authoring use case. But the overall task should be framed around session construction, so we do not optimize the wrong abstraction and then have to reopen the design immediately.
