# Workflow Grammar Concepts

## Overview

The workflow grammar is a compact, EBNF-like documentation grammar for authored
workflow data. It is not intended to be a complete executable parser
specification.

This document explains the supported authoring model built around explicit step
`:type` values, contributions, delegated boundaries, and yielded-value
semantics.

The grammar separates workflow authoring into a small number of orthogonal concerns:

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

A step produces a result. Routing decisions are made from that result through a judge sub-step.

A judge is itself a routing sub-step. The grammar allows at least two judge forms:

- LLM-backed judge
- deterministic invoke-style judge

The purpose of the judge is to normalize a step result into a routing outcome consumed by `:on`.

### Judge outcome contract

All judge forms normalize to one logical outcome value.

That normalized outcome value:

- may be a string or keyword
- is matched against the keys of the parent step's `:on` map
- is case-sensitive for strings
- does not auto-coerce between strings and keywords

A judge result that does not normalize to a declared `:on` key is a workflow execution error in the first cut.

Control flow is orthogonal to step execution form, so invoke, session, and delegate steps may all participate in routing.

`:max-iterations` appears in two places in the first cut:

- as a step-level loop bound on the parent step
- as an optional transition-local bound inside an `:on` routing directive

The transition-local form uses the literal key `:max-iterations`; the earlier `:max-iterations?` spelling in the docs was only an imprecise optionality notation and not a distinct authored field name.

## Execution forms

The grammar has three step execution forms:

- `:type :invoke`
- `:type :session`
- `:type :delegate`

These are mutually exclusive.

All three step forms may author `:yields`. When omitted, the runtime applies the default yielded-value rule for that step type.

### Invoke

`:type :invoke` describes deterministic execution.

An invoke step names:

- `:operation`
- `:args`

It is intended for operations that are deterministic, code-backed, and structurally data-oriented.

### Session

`:type :session` describes inline child-session construction.

A session step names inline session-construction fields such as:

- `:model`
- `:tools`
- `:skills`
- `:contributions`

Its purpose is to explicitly describe the child session to be run and the conversation that will be assembled for that session.

### Delegate

`:type :delegate` describes delegation to an existing named workflow.

A delegate step names:

- `:target`
- `:prompt-string`
- optional `:context`

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

`:prompt-string` is the new request sent to the delegated workflow. It may be authored as a literal string or as a template-shaped renderer, both of which render to a final string before delegation.

A `:prompt-string` may also be authored as a `:map` form — `{:type :map :fields {<key> <source-spec> ...}}` — which renders to a **map**, not a string, with each field resolved from its source-spec. The delegated workflow then receives that map as its `:workflow-input`, so a sub-workflow reference like `{:from :workflow-input :path [<key>]}` resolves the corresponding field. This is the mechanism for threading a structured identifier (e.g. a task id under `:input`) into a delegated workflow; see the shipping exemplars `gh-issue-implement.edn` and `review-task-implementation.edn`.

`:context` is caller-derived material forwarded across the delegation boundary. It is optional; when omitted, it is equivalent to an empty vector.

The grammar keeps these separate because they play different roles:

- prompt string = explicit new ask
- context = carried-forward source material

The delegated workflow treats the final rendered prompt string — or, for the `:map` form, the rendered map — as its local workflow input surface.

## Data flow

Data flow is expressed through source references and optional projection.

The core data-flow surface is:

- `:from`
- `:path`
- `:projection`
- `{:step ... :output ...}`
- `{:step ... :prompt ... :output ...}`
- `{:step ... :yield ...}`

This data-flow surface is shared across:

- invoke args
- source contributions
- template vars
- delegated context
- model-selection query values where applicable

The grammar therefore treats data flow as a common substrate rather than duplicating separate per-feature reference languages.

### Projection rule

A source-spec may contain either:

- `:path`, or
- `:projection`

The first cut does not allow both on the same source-spec. `:path` is the simple selector form; `:projection` is the richer selector form.

### Workflow input and original request

For a top-level workflow invocation:

- `:workflow-input` is the workflow's current input value
- `:workflow-original` is the invocation's original request surface

For a delegated workflow invocation:

- `:workflow-input` is the delegated step's fully rendered `:prompt-string`
- `:workflow-original` is rebound per invocation and is local to the delegated workflow run rather than implicitly inherited from the root caller

The rendered `:prompt-string` is a string for literal and `:template`-shaped
forms, and a **map** for the `:map` form (see the Delegation boundary section).
For a `:map` `:prompt-string`, `:workflow-input` is therefore that map, so the
delegated workflow's `{:from :workflow-input :path [<key>]}` selectors resolve
against it.

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

Template variable names are strings in the grammar. The placeholder `{{issues}}` therefore binds to the key `"issues"` in `:vars`.

This keeps textual rendering aligned with workflow data flow.

## Result surfaces

The grammar distinguishes between:

- step-local output surfaces
- step-local yielded value references
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

For the first cut:

- invoke steps may expose `:data`, `:summary`, and optional `:result`
- session steps may expose `:final-llm-reply`, `:transcript`, and optional `:result`
- delegate steps do not re-export callee step-local outputs and do not add first-cut step-specific output surfaces beyond any future explicit delegate-local debug/result surface

A reference that selects an output not exposed by that step type is invalid.

### Per-prompt output surfaces (`:prompt` discriminator)

A multi-prompt session step (`:prompts`, see the grammar reference) exposes two
levels of output surface:

- **step-level** — the unqualified `{:step s :output k}` ref. `:final-llm-reply`
  is the **last** group's reply; `:transcript` is **accumulated** across every
  group's turn. This is back-compatible: a no-`:prompt` ref against a
  multi-prompt step resolves the step-level surface exactly as against a
  single-prompt step.
- **per-prompt** — `{:step s :prompt p :output k}` addresses named group `p`'s
  **turn-local** surface in step `s`: that group's own `:final-llm-reply` and
  `:transcript` for the turn it ran. `:prompt` is an optional discriminator on
  the canonical `{:step s :output k}` ref; it lives on the shared data-flow
  substrate and resolves uniformly wherever a source ref is admitted (invoke
  args, source contributions, template vars, delegated context).

A `:prompt` ref is invalid (reported fail-fast at workflow load / IR validation)
when any of the following hold:

- step `s` is **not** a `:session` step;
- step `s` is **single-prompt** (a `:contributions` step has no named-group
  namespace);
- `p` is **not** a declared group of `s`;
- `k` is **not** a per-prompt text surface (`:final-llm-reply` / `:transcript`)
  — structured/`:result` keys are not per-prompt-addressable;
- the ref targets the **same step being assembled** (a sibling-group ref,
  forward or back) — assembly-time refs cannot see a turn that has not run yet.

**Post-drain judge carve-out.** The one exception to the same-step rule is the
step's **own post-drain `:judge`**: it *may* address its prompt-groups via
`{:step s :prompt p :output k}`. The judge resolves **after the drain**, once
every group's turn is recorded, so the value is present and deterministic —
unlike an assembly-time contribution/template that would reference a sibling
turn that has not yet run.

`:prompt` is `:output`-only — a `:prompt` discriminator on a `:yield` ref
(`{:step s :prompt p :yield k}`) is **structurally** rejected (it matches neither
the `:output` ref shape nor the closed `:yield` ref shape), so the case is
unreachable rather than a semantic carve-out. The step's yielded value is
unchanged (text from the step-level `:final-llm-reply`).

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

- invoke step ⇒ yields data-oriented value sourced from its `:data` output surface
- session step ⇒ yields text-oriented value sourced from the `:final-llm-reply` output surface
- delegate step ⇒ yields the called workflow's yielded value unchanged

### Referencing another step's yielded value

A downstream step may reference a prior step's yielded value through `{:step ... :yield ...}`.

Examples:

- `{:from {:step "discover" :yield :data}}`
- `{:from {:step "report" :yield :text}}`
- `{:from {:step "review" :yield :reason}}`

This reference form addresses fields of the yielded tagged union, not step-local output surfaces.

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

A workflow's result is the yielded value of the step that transitions execution to `:done`.

If a step reaches `:done` directly, that step's yielded value becomes the workflow result.

If a step uses a judge and the judge outcome selects a transition whose `:goto` is `:done`, the workflow result is still the parent step's yielded value, not the judge's routing value.

An invoke step yields a deterministic result-oriented value.

A session step yields a text-oriented value derived from the final LLM reply.

A delegate step yields the called workflow's yielded value unchanged.

This makes delegation compositional: the delegated workflow's resulting value becomes the delegating step's resulting value.
