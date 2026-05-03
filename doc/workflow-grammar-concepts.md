# Workflow Grammar Concepts

## Overview

The workflow grammar separates workflow authoring into a small number of orthogonal concerns:

- control flow
- execution form
- session construction
- delegation
- data flow
- result surfaces
- yielded value
- templating

A workflow is a graph of named steps. Each step uses one execution form and may participate in control flow.

## Control flow

Control flow describes how execution proceeds from one step to another.

The control-flow surface is made of:

- `:name`
- `:judge`
- `:on`
- `:goto`
- `:max-iterations`

A step produces a result. Routing decisions are made from that result, either directly or through a judge sub-step.

A judge is itself a routing sub-step. The grammar allows at least two judge forms:

- LLM-backed judge
- deterministic invoke-style judge

The purpose of the judge is to normalize a step result into a routing outcome consumed by `:on`.

Control flow is orthogonal to step execution form, so invoke, session, and delegate steps may all participate in routing.

## Execution forms

The grammar has three step execution forms:

- `:type :invoke`
- `:type :session`
- `:type :delegate`

These are mutually exclusive.

### Invoke

`:`type :invoke` describes deterministic execution.

An invoke step names:

- `:operation`
- `:args`

It is intended for operations that are deterministic, code-backed, and structurally data-oriented.

### Session

`:`type :session` describes inline child-session construction.

A session step names inline session-construction fields such as:

- `:model`
- `:tools`
- `:skills`
- `:contributions`

Its purpose is to explicitly describe the child session to be run and the conversation that will be assembled for that session.

### Delegate

`:`type :delegate` describes delegation to an existing named workflow.

A delegate step names:

- `:target`
- `:prompt-string`
- `:context`

Its purpose is to call a reusable workflow while making the delegation boundary explicit.

## Session construction

Session construction is the inline specification of a child session.

The grammar models session construction as:

- configuration fields such as model/tools/skills
- ordered `:contributions`

The assembled result of these contributions is the child-session conversation that will be executed.

The grammar intentionally does not use separate canonical fields such as:

- `:prompt`
- `:input`
- `:reference`
- `:preload`

Instead, these concerns are subsumed by ordered contribution assembly.

## Delegation boundary

Delegation is a distinct execution form rather than a variant of inline session construction.

The delegation boundary has two channels:

- `:prompt-string`
- `:context`

`:`prompt-string` is the new string request sent to the delegated workflow. It may be authored as a literal string or as a template-shaped renderer, but it is rendered to a final string before delegation.

`:`context` is caller-derived material forwarded across the delegation boundary.

The grammar keeps these separate because they play different roles:

- prompt string = explicit new ask
- context = carried-forward source material

The delegated workflow treats the final rendered prompt string as its local workflow input surface.

## Data flow

Data flow is expressed through source references and optional projection.

The core data-flow surface is:

- `:from`
- `:path`
- `:projection`
- `{:step ... :output ...}`

This data-flow surface is shared across:

- invoke args
- source contributions
- template vars
- delegated context
- model-selection query values where applicable

The grammar therefore treats data flow as a common substrate rather than duplicating separate per-feature reference languages.

## Contributions

Contributions are the building blocks of inline session conversation assembly.

There are two contribution forms:

- `:type :source`
- `:type :template`

### Source contributions

A source contribution injects sourced material into the child-session conversation.

It reuses the workflow source/projection model and preserves author order.

### Template contributions

A template contribution is authored text plus explicit variable bindings.

It is the grammar's textual rendering mechanism. It makes templating explicit rather than implicit.

A delegate step's `:prompt-string` may also use this same template shape before rendering to a final string.

## Templating

Templating is modeled as:

- `:text`
- `:vars`

A template contribution does not invent a separate data source model; it binds vars through the same source-spec mechanism used elsewhere.

This keeps textual rendering aligned with workflow data flow.

## Result surfaces

The grammar distinguishes between:

- step-local output surfaces
- the step's yielded value as a whole

### Output surfaces

Output surfaces are addressed through `:output` selectors in source refs.

Examples include:

- `:data`
- `:summary`
- `:result`
- `:final-llm-reply`
- `:transcript`

Not every step form exposes every output surface.

For the current grammar shape:

- invoke steps expose deterministic result-oriented surfaces such as `:data`, `:summary`, and optionally `:result`
- session steps expose session-oriented surfaces such as `:final-llm-reply`, `:transcript`, and optionally `:result`
- delegate steps expose the delegated workflow's yielded value and may later expose step-local debug/result surfaces

### Yielded value

The yielded value is the step's resulting value as a whole.

It is modeled through `:yields` as a tagged union.

Success forms:

- `{:type :data :data ...}`
- `{:type :text :text ...}`

Error form:

- `{:type :error :reason ... :message ... :details ...}`

This makes yielded values structurally exclusive rather than implicitly exclusive by map shape.

The default yielded-value composition by step form is:

- invoke step ⇒ yields data-oriented value
- session step ⇒ yields text-oriented value sourced from the `:final-llm-reply` output surface
- delegate step ⇒ yields the called workflow's yielded value unchanged

## Error handling

The grammar includes an explicit yielded error form.

An error has:

- `:type :error`
- `:reason`
- `:message`
- optional `:details`

The purpose of `:reason` is to provide a stable keyword-classified cause.

The purpose of `:message` is to provide human-readable diagnostic text.

The purpose of `:details` is to carry structured diagnostic data.

## Model selection

The grammar uses a single `:model` field for session steps.

That field may contain either:

- a concrete model id
- a query-shaped model selection specification

The model-selection grammar is defined separately in `doc/model-selection-grammar.md`.

In the workflow grammar, this appears as the nonterminal `model-selection-spec`, which is intentionally defined externally rather than re-specified inside the workflow grammar.

This keeps model choice in one semantic slot while allowing both direct and query-driven selection without redefining the broader model-selection language inside the workflow grammar.

## Workflow result composition

A workflow's result is built from step results.

An invoke step yields a deterministic result-oriented value.

A session step yields a text-oriented value derived from the final LLM reply.

A delegate step yields the called workflow's yielded value unchanged.

This makes delegation compositional: the delegated workflow's resulting value becomes the delegating step's resulting value.
