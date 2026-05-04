# Workflows

Psi can delegate reusable tasks to named workflows.

A workflow is a named prompt or orchestration loaded from `.psi/workflows/*.md`
by the `psi/workflow-loader` extension. Some workflows are single focused agents;
others are multi-step flows that pass results from one step to the next.

This document is the primary example-led guide for the preferred workflow
authoring path. It covers the user-facing workflow surface, how to enable and run
workflows, and the converged target grammar that new workflow authors should
prefer when the current implementation supports the needed surface.

## Prerequisite: enable workflow loading

Workflows are provided by the `psi/workflow-loader` extension.

Project-local example:

```clojure
{:deps {psi/workflow-loader {}
        psi/mementum {}}}
```

Put that in one of the supported extension manifest locations, then start psi.
For manifest details and install options, see [`doc/extensions-install.md`](extensions-install.md).

## Where workflows live

Workflow definitions are discovered from:

```text
.psi/workflows/*.md
```

This repository includes many examples there, including:

- `plan-build`
- `plan-build-review`
- `gh-bug-triage-modular`
- `planner`
- `builder`
- `reviewer`

For this migration slice, the authoritative example set is:

- `plan-build` — compact inline-session authoring example
- `plan-build-review` — compact multi-step inline-session example
- `gh-bug-triage-modular` — richer orchestration/context/reference example used to teach the delegate-oriented target model and to anchor the real executable bug-triage flow

## User-facing workflow commands

When `psi/workflow-loader` is active, psi exposes:

- `/delegate <workflow> <prompt>`
- `/delegate-reload`

Typical usage:

```text
/delegate planner analyze the scope of the current refactor
/delegate plan-build-review add user-facing workflow docs
/delegate gh-bug-triage-modular issue 123
```

What happens:

- psi starts the named workflow
- the workflow runs asynchronously
- you get an immediate acknowledgement
- the final workflow result is posted back into the same conversation

If you want a workflow to continue from a narrow request, put that request after
the workflow name as the prompt text.

## Reloading workflow definitions

When editing `.psi/workflows/*.md`, reload them without restarting psi:

```text
/delegate-reload
```

Reloading:

- re-discovers workflow definitions
- registers changed definitions
- retires removed definitions

Use this during workflow authoring or prompt iteration.

## Preferred authoring model

Prefer the converged target workflow grammar for new examples and new workflow
files.

That grammar has three explicit step forms:

- `:type :invoke` — deterministic operation call
- `:type :session` — inline child-session construction
- `:type :delegate` — call another named workflow through an explicit boundary

At a high level:

- use `:invoke` when code should do deterministic work and return structured data
- use `:session` when you want to assemble a child conversation inline
- use `:delegate` when you want to call a reusable named workflow

For the formal target grammar, see [`doc/workflow-grammar.md`](workflow-grammar.md).
For the conceptual explanation, see
[`doc/workflow-grammar-concepts.md`](workflow-grammar-concepts.md).
For the older currently implemented authored shape, see
[`doc/workflow-grammar-current.md`](workflow-grammar-current.md).

## Example 1: compact inline session workflow

`plan-build` is the smallest authoritative example of the preferred inline
session style.

```markdown
---
name: plan-build
description: Plan and build without review
---
{:steps [{:name "plan"
          :type :session
          :tools ["read" "bash"]
          :contributions [{:type :template
                           :text "{{input}}"
                           :vars {"input" {:from :workflow-input
                                            :path [:input]}}}]}
         {:name "build"
          :type :session
          :tools ["read" "bash" "edit" "write"]
          :contributions [{:type :source
                           :from :workflow-original}
                          {:type :template
                           :text "Execute this plan:\n\n{{plan}}\n\nOriginal request: {{original}}"
                           :vars {"plan" {:from {:step "plan" :yield :text}}
                                  "original" {:from :workflow-original
                                              :path [:original]}}}]}]}
```

What this teaches:

- explicit `:type :session`
- ordered `:contributions`
- template rendering through `:text` + `:vars`
- prior-step reuse through `{:from {:step "plan" :yield :text}}`
- separate carried reference context through `:workflow-original`

## Example 2: multi-step inline session workflow

`plan-build-review` extends the same style with one more downstream step.

```markdown
---
name: plan-build-review
description: Plan, build, and review code changes
---
{:steps [{:name "plan"
          :type :session
          :tools ["read" "bash"]
          :contributions [{:type :template
                           :text "{{input}}"
                           :vars {"input" {:from :workflow-input
                                            :path [:input]}}}]}
         {:name "build"
          :type :session
          :tools ["read" "bash" "edit" "write"]
          :contributions [{:type :source
                           :from :workflow-original}
                          {:type :template
                           :text "Execute this plan:\n\n{{plan}}\n\nOriginal request: {{original}}"
                           :vars {"plan" {:from {:step "plan" :yield :text}}
                                  "original" {:from :workflow-original
                                              :path [:original]}}}]}
         {:name "review"
          :type :session
          :tools ["read" "bash"]
          :contributions [{:type :source
                           :from :workflow-original}
                          {:type :template
                           :text "Review the following implementation:\n\n{{implementation}}\n\nOriginal request: {{original}}"
                           :vars {"implementation" {:from {:step "build" :yield :text}}
                                  "original" {:from :workflow-original
                                              :path [:original]}}}]}]}
```

What this adds:

- a readable multi-step chain without old `$INPUT` / `$ORIGINAL` shortcuts
- repeated use of prior-step text yields for downstream authoring
- a clear separation between sourced context and newly authored task text

## Example 3: richer orchestration and delegate-oriented mapping

`gh-bug-triage-modular` is the authoritative richer orchestration example for
context/reference flow in a realistic workflow. Today, its checked-in executable
file still uses the older current-authored multi-step shape, because that path
remains the repository's concrete bug-triage implementation surface.

The delegate-oriented target-model reading for that workflow is:

- each named bug-triage phase is conceptually a delegated workflow boundary
- the next phase receives a new ask derived from the prior phase result
- original request context and constrained transcript context are carried
  explicitly rather than assumed implicitly

A representative target-style delegate sketch for the final classification step
looks like:

```clojure
{:name "post-repro"
 :type :delegate
 :target "gh-bug-post-repro"
 :prompt-string {:type :template
                 :text "{{report}}"
                 :vars {"report" {:from {:step "reproduce" :yield :text}}}}
 :context [{:type :source
            :from :workflow-original}
           {:type :source
            :from {:step "discover" :yield :text}}
           {:type :source
            :from {:step "worktree" :yield :text}}
           {:type :source
            :from {:step "reproduce" :output :transcript}
            :projection {:type :tail :turns 4 :tool-output false}}]}
```

What this teaches:

- how the richer orchestration maps onto explicit delegated workflow boundaries
- a new delegated ask through `:prompt-string`
- carried caller material through ordered `:context`
- shared reference syntax across prompt rendering and forwarded context
- transcript-tail projection as constrained delegated context

Implementation boundary note:

- `plan-build` and `plan-build-review` are now migrated checked-in target-grammar examples
- `gh-bug-triage-modular` remains executable in the current-authored surface while this guide teaches the delegate-oriented target mapping explicitly rather than silently claiming the file is already migrated end-to-end

## Input and context flow

The most important authoring references in this guide are:

- `:workflow-input` — the current workflow's input value
- `:workflow-original` — carried original request/reference context
- `{:from {:step "..." :yield :text}}` — prior step result used as the next ask
- `{:from {:step "..." :output :transcript}}` with `:projection` — projected
  transcript/reference context

Interpretation:

- `:workflow-input` is the immediate ask for the current workflow invocation
- `:workflow-original` is the carried reference context
- prior-step `:yield` refs are the simplest way to feed one step's result into
  the next step's authored text
- `:context` on a delegate step carries forwarded material without changing the
  delegated workflow's prompt string

## Concise current → target mapping

When reading older workflow examples, the simplest mapping is:

- old multi-step `:workflow` step entry → usually new `:type :delegate`
- old `:session :input` / `:reference` / `:preload` → new explicit
  `:contributions` for inline sessions, or `:prompt-string` + `:context` for
  delegates
- old prompt strings using `$INPUT` / `$ORIGINAL` → new template contributions
  using `:text` + `:vars`

Keep the mapping practical:

- if you are assembling a child conversation inline, use `:session`
- if you are calling another workflow by name, use `:delegate`
- if you need deterministic code-backed work, use `:invoke`

## Current boundary of this guide

This guide intentionally teaches the currently migrated example-led surfaces:

- target-grammar step authoring shape
- inline `:session` authoring
- delegated `:prompt-string` and `:context`
- shared reference syntax for `:workflow-input`, `:workflow-original`, prior
  step yields, and projected transcript context

It does not yet try to be the authoritative example-led guide for all
`outputs`/`yields` variations beyond what the examples above use directly.
When you need the full formal surface, use the grammar/reference docs.

## Authoring guidelines

Prefer:

- one clear workflow purpose per file
- descriptive `name` and `description`
- small, explicit step graphs
- explicit `:type` on every authored step
- explicit reference wiring rather than implicit positional flow
- reusable focused workflows as delegate targets

Good first workflow authoring loop:

1. create or edit `.psi/workflows/<name>.md`
2. run `/delegate-reload`
3. invoke it with `/delegate <name> <prompt>`
4. tighten the authoring shape or reference wiring
5. reload and repeat

## Related docs

- [`doc/workflow-grammar.md`](workflow-grammar.md) — target workflow grammar
- [`doc/workflow-grammar-concepts.md`](workflow-grammar-concepts.md) — target concepts and semantics
- [`doc/workflow-grammar-current.md`](workflow-grammar-current.md) — older authored shape still present during migration
- [`doc/workflow-grammar-migration.md`](workflow-grammar-migration.md) — migration strategy and layers
- [`doc/extensions-install.md`](extensions-install.md) — enable `psi/workflow-loader`
- [`doc/extensions.md`](extensions.md) — extension/tool details for `workflow-loader`
- [`doc/tui.md`](tui.md) — general in-session command usage
- [`README.md`](../README.md) — top-level project overview
