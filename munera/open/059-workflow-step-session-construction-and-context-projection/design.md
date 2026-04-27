# 059 — Workflow step session construction and context projection

## Goal

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
- add step-level workflow-file authoring for child-session construction/shaping
- add step-level authoring for reference-context projection/preloading
- support explicit source selection independent of file-order adjacency
- retain backward compatibility for existing workflow files that do not use the new surface
- compile new authoring syntax into canonical runtime/session-preparation semantics
- validate source references and projection specs clearly at load time where possible
- add tests covering linear and branched workflows with explicit context projection
- update workflow docs/examples, including modular GitHub workflow examples
- define an incremental implementation plan so the large scope can land safely in slices

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

Each step may gain an optional session-shaping block, tentatively named `:session`.

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

A reference-context source should be able to target explicit workflow-visible data such as:
- workflow input
- workflow original request/context
- accepted result of a named prior step
- projected transcript/messages of a named prior step session
- possibly workflow runtime metadata when clearly justified

Preferred author-facing references use stable step names/workflow names from the file, not compiled step ids.

### Projection surface

This task should include a projection model that is useful for both:
- prompt/input extraction
- preloaded/reference conversation shaping

The projection vocabulary should stay constrained. Likely supported forms, potentially in staged delivery:
- `:text`
- `:full`
- `:path [...]`
- transcript-tail projection like `{:type :tail :turns N}`
- optional tool-output stripping for transcript projections

Projection should describe selection and filtering, not general computation.

### Prompt bindings

Prompt bindings should remain supported, but as a lower-level convenience.

Near-term expectation:
- keep built-in prompt channels such as current working input and original/reference context
- allow them to be derived from explicit sources/projections
- do not center the whole design around `$INPUT`/`$ORIGINAL`

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
