Goal: make workflow child-session prompt composition structurally consistent with normal session prompt assembly, and specify that behavior in Allium with proper integration into the existing prompt/session/workflow spec surface.

## Intent

Workflow child sessions should not silently bypass canonical prompt assembly. When a workflow step creates a child session, the child prompt should be built from structured session state in the same mode-aware way as a normal runtime session, then narrowed or shaped by explicit workflow capability/configuration declarations.

This task converges the current mismatch where workflow step sessions may carry `:prompt-mode`, tools, and skills structurally while materializing only workflow-local prompt text as the visible `:system-prompt`.

## Problem statement

Current behavior shows a gap between structured child-session state and the materialized prompt text used for workflow execution:

- workflow child sessions can carry `:prompt-mode :lambda`
- workflow child sessions can carry narrowed tools and skills
- delegated workflow definitions can provide workflow-local prompt text and parent framing text
- but the effective child `:system-prompt` may be only workflow-authored text plus framing text
- so lambda-mode preamble, tool descriptions, skill descriptions, and other default prompt sections may be absent from the visible prompt even though the corresponding state is present

This creates semantic drift between:

1. child session capability/configuration state
2. prompt introspection surfaces
3. actual provider-visible prompt text

There is also a specific semantics ambiguity around `:prompt-component-selection`:

- desired meaning: selection filters or narrows prompt composition
- current practical meaning in some child-session paths: selection is the switch that causes prompt rebuilding to happen at all

That ambiguity should be removed.

## Canonical model

### Canonical builder

Workflow child sessions should use the same canonical prompt-building model as normal runtime sessions: the child prompt is assembled from structured prompt inputs via the canonical system-prompt builder, with workflow-authored instruction text composed in as an instruction layer.

Operationally, the intended default is:

- assemble the same default prompt sections that `build-system-prompt` would assemble for an equivalently configured session
- apply child-session capability narrowing before rendering the corresponding capability sections
- then compose workflow-authored instruction text into the assembled prompt

### Authoritative proof surface

The authoritative runtime proof target for this task is the provider-visible prepared request system prompt produced during request preparation.

Stored/introspectable prompt surfaces must remain coherent with that authoritative prompt, specifically:

- child `:base-system-prompt`
- child `:system-prompt`
- prompt-layer introspection
- `prompt-request/effective-system-prompt`

A change that only updates stored prompt strings without updating the provider-visible prepared request prompt, or vice versa, does not satisfy the task.

## Required behavior

### Default workflow child-session prompt assembly

When a workflow step creates a child session and does **not** supply `:prompt-component-selection`, the child session must receive the **full default prompt composition** for that child session.

That full default composition must:

- respect the child session `:prompt-mode`
- include the corresponding psi-authored preamble for that mode
- include rendered tool descriptions for the child session's available tools
- include rendered skill descriptions for the child session's available skills
- include allowed context-file content
- include runtime metadata according to normal defaults
- include eligible extension prompt contributions
- include workflow-authored instruction text as an instruction layer within the assembled prompt

For this task, “full default prompt composition” refers to the same default psi-authored prompt sections normally built for a session by the canonical prompt builder, subject to the child session's resolved available capabilities and explicit component-selection filters.

### Child prompt-mode inheritance

Workflow child sessions inherit the parent session `:prompt-mode` by default unless explicit workflow/session override machinery already provides a narrower child value.

This task should preserve that inheritance model and make the inherited-or-overridden child prompt mode actually visible in the assembled prompt text.

### Meaning of `:prompt-component-selection`

`:prompt-component-selection` controls filtering/composition of prompt components. It does **not** control whether prompt rebuilding occurs.

Therefore:

- `nil` `:prompt-component-selection` means full/default prompt composition
- non-`nil` `:prompt-component-selection` means rebuild the prompt with the requested filtered subset

It must not mean:

- skip prompt assembly
- use raw workflow prompt text as the complete system prompt by default
- suppress prompt-mode-specific preambles unless an explicit replacement mechanism asks for that

### Meaning of workflow capability declarations

When a workflow step or delegated workflow specifies tools and skills, those declarations must constrain both:

1. actual child-session capability state
2. rendered prompt visibility for those capability sections

So capability narrowing must stay coherent between:

- session state
- introspection surfaces
- provider-visible prompt text

### Extensions and workflows in this task

Extensions and workflows are in scope in this task only at the level of prompt-visible filtering/rendering semantics if they are already surfaced by the existing prompt assembly model.

This task does **not** require inventing new child-session runtime capability-gating machinery for extensions/workflows if that machinery does not already exist. Instead, it requires:

- preserving or shaping coherent prompt-visible treatment for extensions/workflows where they already participate in prompt composition
- avoiding a model where tools/skills are treated structurally but extensions/workflows are silently inconsistent on the same prompt surface

If deeper runtime capability gating for extensions/workflows is not already present, do not broaden this task to invent it.

### Meaning of workflow-authored prompt text

Workflow-authored prompt text, including delegated workflow body text and parent framing text, is by default an instruction layer within assembled prompt composition, not an implicit full replacement for the entire prompt.

For this task:

- do **not** treat the mere presence of workflow-local `:system-prompt` text as implicit full replacement
- do **not** introduce a new prompt-replacement mechanism unless preserving an already-existing explicit replacement contract proves necessary
- if an already-existing explicit replacement mechanism is found, preserve it explicitly rather than inferring replacement from ordinary workflow-authored prompt text

### Consequence for lambda mode

If a workflow child session is in lambda mode, then the effective materialized prompt should visibly reflect lambda-mode prompt assembly unless an explicit full-replacement mechanism suppresses it.

That means lambda mode should normally contribute:

- nucleus prelude
- lambda identity section
- lambda tool rendering
- lambda guidelines
- lambda graph-discovery section where applicable
- lambda-form rendering for psi-authored sections that support dual rendering

and not merely exist as hidden metadata.

## Scope

In scope:

- clarify and implement intended workflow child-session prompt assembly semantics
- make default child-session prompt rebuilding happen even when `:prompt-component-selection` is nil
- keep capability narrowing coherent across tools and skills
- keep prompt-visible extension/workflow treatment coherent where those surfaces already exist
- ensure workflow-authored prompt text composes into the assembled prompt by default
- add or refine Allium specification covering this behavior
- integrate the new Allium spec material into the relevant existing spec surface rather than leaving an isolated spec file
- add focused tests proving workflow child sessions respect prompt mode and capability rendering semantics

Out of scope:

- broad redesign of all prompt assembly surfaces unrelated to workflow child sessions
- unrelated compatibility cleanup beyond the seams touched by this convergence
- inventing a new prompt replacement mechanism unless needed to preserve an already-existing explicit replacement semantic
- inventing new runtime capability-gating machinery for extensions/workflows solely for this task
- redesigning extension/workflow capability models beyond what is needed to make prompt composition semantics coherent

## Allium specification requirement

This task must produce or refine Allium spec coverage for workflow child-session prompt composition.

The spec work must:

- describe workflow child-session prompt assembly semantics explicitly
- cover the meaning of nil versus explicit `:prompt-component-selection`
- cover coherence between capability state and rendered prompt visibility
- cover lambda/prose mode consequences for workflow child sessions
- cover workflow-authored prompt text as a compositional instruction layer by default
- integrate with the existing prompt/session/workflow-related Allium files instead of standing alone as an isolated spec island

Minimum integration requirement:

- update at least one existing connected prompt/session/lambda spec file
- and if a new Allium file is introduced, it must be explicitly connected to the existing prompt/session/workflow spec surface so it is not isolated

Likely integration points include the existing prompt/session/lambda/workflow spec surface such as:

- `spec/lambda-mode.allium`
- `spec/session-core.allium`
- `spec/session-startup-prompts.allium`
- and, if warranted, a new connected workflow-prompt file linked into those surfaces

Acceptance on the spec side is not merely “new file exists”; it is “behavior is properly represented in the connected Allium spec graph”.

## Acceptance criteria

- workflow child sessions rebuild their base prompt from structured state even when `:prompt-component-selection` is nil
- nil `:prompt-component-selection` yields full/default prompt composition rather than prompt-assembly bypass
- explicit `:prompt-component-selection` still narrows composition deterministically
- workflow child sessions inherit parent prompt mode by default and visibly materialize the resulting lambda/prose prompt structure unless an existing explicit override says otherwise
- workflow capability narrowing for tools and skills is reflected both in child-session state and in rendered prompt sections
- prompt-visible extension/workflow treatment remains coherent where those sections already participate in prompt composition
- workflow-authored prompt text composes into the assembled prompt by default rather than replacing it implicitly
- the provider-visible prepared-request system prompt reflects the intended assembled behavior
- prompt introspection surfaces (`:base-system-prompt`, `:system-prompt`, prompt layers, `prompt-request/effective-system-prompt`) stay coherent with provider-visible prompt assembly
- focused tests cover at least:
  - workflow child session default full-prompt composition in lambda mode
  - nil versus explicit `:prompt-component-selection`
  - capability narrowing reflected in rendered prompt content
  - composition of workflow-authored prompt text with canonical prompt assembly
  - provider-visible prepared request prompt as the authoritative proof surface
- Allium spec coverage exists and is integrated into the relevant existing prompt/session/workflow spec files

## Approach

1. identify the canonical child-session prompt assembly path for workflow execution
2. make child workflow sessions rebuild prompt structure from state by default rather than passing workflow-local text straight through as the whole prompt
3. preserve explicit narrowing semantics for `:prompt-component-selection`
4. ensure workflow capability declarations drive both capability state and rendered prompt visibility for tools/skills, while keeping extension/workflow prompt surfaces coherent where already present
5. decide and encode the canonical place for workflow-authored instruction text in assembled prompt layers
6. add or refine focused tests around child-session prompt materialization and provider-visible prompt assembly
7. add/refine Allium spec files and links so this behavior is part of the connected spec graph

## Risks

- preserving existing tests that encode old raw-string prompt pass-through semantics
- accidentally changing non-workflow child-session prompt behavior
- narrowing only runtime state or only prompt text and leaving the two inconsistent
- adding spec text without properly connecting it into the existing Allium spec structure
- silently broadening into extension/workflow runtime capability redesign beyond this task’s scope

## Notes

This task sharpens intended behavior already captured in:

- `doc/design-workflow-session-prompts.md`

Implementation should treat that design document as the intent source for the code/spec/test convergence work.