# Design: workflow session prompt assembly

## Status

Proposed intended behavior.

## Purpose

Define the intended prompt-assembly semantics for workflow child sessions so that:

- child sessions are mode-aware
- prompt construction is structural and introspectable
- workflow-local instructions compose with the standard prompt surface
- capability narrowing remains coherent between session state and rendered prompt text

## Principle

A workflow child session's system prompt is derived from structured session state, not assembled by raw string pass-through except when explicitly requested.

## Default rule

When a workflow step creates a child session and does **not** specify `:prompt-component-selection`, the child session must receive the **full default system prompt** for that session, assembled in the same mode-aware way as a normal runtime session.

Full default system prompt means:

- respect `:prompt-mode` (`:lambda` or `:prose`)
- include the corresponding preamble for that mode
- include rendered tool descriptions for the child session's available tools
- include rendered skill descriptions for the child session's available skills
- include rendered extension descriptions for the child session's available extensions
- include rendered workflow descriptions for the child session's available workflows
- include allowed context files
- include runtime metadata according to normal defaults
- include eligible extension prompt contributions
- include workflow-specific instruction text

## Meaning of `:prompt-component-selection`

`:prompt-component-selection` controls **filtering/composition of prompt components**, not whether prompt rebuilding happens at all.

Therefore:

- `nil` `:prompt-component-selection` means **use the full/default prompt composition**
- non-`nil` `:prompt-component-selection` means **rebuild the prompt with the requested filtered subset**

It must **not** mean:

- skip prompt assembly
- use explicit workflow text as the whole prompt
- disable lambda/prose preamble generation

## Meaning of workflow capability declarations

When a workflow step or delegated workflow specifies capabilities such as tools, skills, extensions, and workflows, those declarations constrain both:

1. the child session's available capabilities
2. the rendered capability sections of the child session prompt

So if a workflow specifies:

```clojure
{:tools ["read" "bash"]
 :skills ["lambda-compiler"]}
```

then the child prompt should be assembled for a session whose visible prompt surface includes:

- only `read` and `bash` in the tool section
- only `lambda-compiler` in the skill section
- the mode-appropriate preamble
- the workflow instruction text

The same rule should apply to:

- extensions
- workflows

That is, extensions and workflows should be configurable and filterable in the same way as tools and skills, affecting both:

- child session capability state
- rendered prompt visibility

## Meaning of workflow prompt text

Workflow-authored prompt text, including delegated workflow body text and parent workflow framing text, is an **instruction layer within prompt assembly**, not an implicit replacement for the entire system prompt.

By default, workflow prompt text should be treated like:

- workflow instruction text appended to or layered within the assembled system prompt

not:

- the complete final system prompt by itself

## Replacement behavior must be explicit

If a workflow author wants a child session to use a literal prompt string as the entire system prompt, that must be expressed through an explicit replacement mechanism.

Absent such an explicit mechanism, the runtime should assume **compositional assembly**, not replacement.

## Consequence for lambda mode

If a workflow child session has `:prompt-mode :lambda`, then the effective system prompt must visibly reflect lambda mode in its assembled prompt text unless an explicit full-prompt replacement mechanism suppresses it.

That means lambda mode should normally contribute:

- lambda prelude
- lambda identity/tool/guideline sections
- lambda-form skill/tool rendering
- lambda-form extension/workflow rendering where applicable

and not merely exist as hidden session metadata.

## Example: expected behavior for the observed case

Given a delegated workflow step using:

- workflow profile `lambda-compiler`
- `:prompt-mode :lambda`
- tools `["read" "bash"]`
- skills `["lambda-compiler"]`
- workflow instruction text:
  - `Use the lambda-compiler skill...`
  - plus parent framing text from `lambda-build`

then the resulting child system prompt should be a **lambda-mode full assembled prompt** narrowed to those tools/skills, with the workflow instruction text included as an additional instruction layer.

It should **not** be only:

```text
Use the lambda-compiler skill.
...
Iteratively compile and refine ...
```

unless explicit full-prompt replacement was requested.

## Summary

Workflow child-session prompt assembly should follow this model:

- always rebuild from structured state
- `nil` prompt-component-selection means full/default composition
- explicit selection means filtered composition
- workflow capability declarations narrow both actual availability and rendered prompt visibility
- workflow-authored prompt text composes into the prompt rather than replacing it by default
- prompt mode must be reflected in the visible prompt, not just stored in metadata
