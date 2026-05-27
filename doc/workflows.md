# Workflows

Psi can delegate reusable tasks to named workflows.

A workflow is a named prompt or orchestration loaded from `.psi/workflows/*.md`
as a built-in core capability. Some workflows are single focused agents;
others are multi-step flows that pass results from one step to the next.

This document is the primary example-led guide for workflow authoring. It
covers the user-facing workflow surface, how to enable and run workflows, and
the supported target-authored grammar.

## Prerequisite

Workflow loading is built in.

No extension manifest entry is required to enable `/delegate` or `.psi/workflows/*.md` discovery.

Optional workflow-adjacent extensions such as `psi/mementum` still use normal extension install manifests.
For manifest details and install options, see [`doc/extensions-install.md`](extensions-install.md).

## Built-in workflow-adjacent skills

Psi may also ship psi-owned built-in skills as packaged resources. Those skills
are materialized at runtime into readable files under `~/.psi/agent/built-in-skills/`
so workflows and agents continue to use the normal file-backed skill model.

Built-in packaged skills are lowest precedence beneath explicit extra paths,
project-local `.psi/skills/`, and user-global `~/.psi/agent/skills/` overrides.
This means a workflow that requests a skill by exact name can be overridden by a
project or user skill with the same name without changing the workflow file.

## Where workflows live

Workflow definitions are discovered from:

```text
.psi/workflows/*.md
```

This repository includes many examples there, including:

- `plan-build`
- `plan-build-review`
- `delegate-build-review`
- `gh-bug-triage-modular`
- `planner`
- `builder`
- `reviewer`

The authoritative example set is:

- `plan-build` — compact inline-session authoring example
- `plan-build-review` — compact multi-step inline-session example
- `delegate-build-review` — executable delegate-heavy target-authored example proving canonical downstream delegated yielded-text consumption
- `gh-bug-triage-modular` — richer target-authored orchestration example proving delegated yielded text plus structured delegated handoff consumption

## User-facing workflow commands

Psi exposes:

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

For the formal grammar, see [`doc/workflow-grammar.md`](workflow-grammar.md).
For the conceptual explanation, see
[`doc/workflow-grammar-concepts.md`](workflow-grammar-concepts.md).

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

## Example 3: executable delegate-heavy workflow

`delegate-build-review` is the authoritative checked-in target-authored example
for delegate-heavy downstream authoring.

```markdown
---
name: delegate-build-review
description: Delegate planning and building, then review the delegated build result
---
{:steps [{:name "plan"
          :type :delegate
          :target "planner"
          :prompt-string {:type :template
                          :text "{{input}}"
                          :vars {"input" {:from :workflow-input
                                           :path [:input]}}}
          :context [{:type :source
                     :from :workflow-original}]}
         {:name "build"
          :type :delegate
          :target "builder"
          :prompt-string {:type :template
                          :text "Execute this plan:\n\n{{plan}}\n\nOriginal request: {{original}}"
                          :vars {"plan" {:from {:step "plan" :yield :text}}
                                 "original" {:from :workflow-original
                                             :path [:original]}}}
          :context [{:type :source
                     :from :workflow-original}
                    {:type :source
                     :from {:step "plan" :yield :text}}]}
         {:name "review"
          :type :session
          :tools ["read" "bash"]
          :contributions [{:type :source
                           :from :workflow-original}
                          {:type :template
                           :text "Review the following delegated implementation:\n\n{{implementation}}\n\nOriginal request: {{original}}"
                           :vars {"implementation" {:from {:step "build" :yield :text}}
                                  "original" {:from :workflow-original
                                              :path [:original]}}}]}]}
```

What this teaches:

- explicit `:type :delegate` boundaries for reusable named workflows
- canonical downstream delegate-result consumption through `{:from {:step "..." :yield :text}}`
- delegate `:prompt-string` as the new immediate ask for the callee
- ordered delegate `:context` as forwarded reference material
- later inline-session steps consuming delegated results with the same `:yield :text` ref shape used for prior session results

Minimum canonical delegated result model:

- downstream steps should read the delegated step's yielded text through
  `{:from {:step "..." :yield :text}}`
- workflows that want to export stable machine-facing handoff data should declare
  `:terminal-contract {:handoff {:type :markdown-handoff-data}}`
- callers should read that structured delegated handoff through
  `{:from {:step "..." :output :handoff}}`
- delegate diagnostics and other callee-internal detail remain runtime/debug
  surfaces, not the primary authoring contract for normal downstream flow
- first-cut fallback is explicit: if a workflow does not declare a terminal
  handoff contract, callers should not rely on `:output :handoff`

## Example 4: richer orchestration and executable bug triage

`gh-bug-triage-modular` is now the richer orchestration/context/reference
example for realistic bug triage.

It proves the dual-plane delegated model directly:

- each named bug-triage phase is an explicit delegated workflow boundary
- the next phase receives its immediate ask from prior delegated yielded text
- stable machine-facing bug-triage data flows through delegated `:output :handoff`
- original request context and constrained transcript context are carried
  explicitly rather than assumed implicitly

Representative target-style classification step:

```clojure
{:name "post-repro"
 :type :delegate
 :target "gh-bug-post-repro"
 :outputs {:handoff {:source :delegate/handoff}}
 :prompt-string {:type :template
                 :text "{{report}}"
                 :vars {"report" {:from {:step "reproduce" :yield :text}}}}
 :context [{:type :source
            :from :workflow-original}
           {:type :source
            :from {:step "discover" :output :handoff}}
           {:type :source
            :from {:step "worktree" :output :handoff}}
           {:type :source
            :from {:step "reproduce" :output :handoff}}]}
```

What this teaches:

- yielded text and structured handoff are distinct delegated contracts
- `:yield :text` is the human-facing chaining surface
- delegated `:output :handoff` is the machine-facing orchestration surface for delegated workflow exports
- session and LLM-judge steps may also declare structured `:outputs` for local validated model/judge data; see [`doc/workflow-grammar.md`](workflow-grammar.md) and [`doc/workflow-ir.md`](workflow-ir.md) for the formal schema, raw-output envelope, validation, and provider-strategy details
- downstream orchestration should prefer declared structured outputs over parsing transcripts or assuming delegated transcript export

## Input and context flow

The most important authoring references in this guide are:

- `:workflow-input` — the current workflow's input value
- `:workflow-original` — carried original request/reference context
- `{:from {:step "..." :yield :text}}` — prior step result used as the next ask, including delegate-step yielded text
- `{:from {:step "..." :output :handoff}}` — prior delegated workflow's structured terminal handoff
- `{:from {:step "..." :output :classification} :path [:next-action]}` — prior session-step structured output field, when that step declares a structured entry in `:outputs`
- `{:from {:step "..." :output :transcript}}` with `:projection` — projected
  transcript/reference context

Interpretation:

- `:workflow-input` is the immediate ask for the current workflow invocation
- `:workflow-original` is the carried reference context
- prior-step `:yield` refs are the simplest way to feed one step's result into
  the next step's authored text
- delegated `:output :handoff` refs are the stable way to consume machine-facing
  exported workflow data without parsing markdown heuristically downstream
- session structured `:outputs` are the stable downstream-reference surface for
  machine control flow to consume model-generated data through validated fields
  instead of prose parsing; each session step may declare at most one structured
  output key, with multiple values grouped as fields of one map schema
- LLM-judge structured `:outputs` are judge-local data for transition evaluation;
  they are not automatically exported as parent step `:output` refs, and each
  judge may declare at most one structured output key
- `:context` on a delegate step carries forwarded material without changing the
  delegated workflow's prompt string

## Authoring choices

Keep the authoring choices practical:

- if you are assembling a child conversation inline, use `:session`
- if you are calling another workflow by name, use `:delegate`
- if you need deterministic code-backed work, use `:invoke`

## Current boundary of this guide

This guide intentionally teaches the currently migrated example-led surfaces:

- target-grammar step authoring shape
- inline `:session` authoring
- delegated `:prompt-string` and `:context`
- canonical downstream delegated yielded-text consumption
- canonical downstream delegated structured-handoff consumption through `:output :handoff`
- session-step structured entries in `:outputs` for validated downstream
  machine-facing data
- LLM-judge structured entries in judge-local `:outputs` for validated transition
  evaluation data
- one structured-output key per session step or LLM judge; use one map schema
  plus `:path` references for multiple fields
- shared reference syntax for `:workflow-input`, `:workflow-original`, prior
  step yields, delegated handoffs, structured output fields, and projected
  transcript context

It does not try to turn arbitrary delegate-local runtime envelopes or diagnostics
into authoring contracts. Delegate handoffs remain the standardized delegated
workflow export, session structured `:outputs` are the validated downstream
model-data surface, and LLM-judge structured `:outputs` are validated
judge-local transition data unless a future explicit export contract says
otherwise. When you
need the full formal surface, use the grammar/reference docs.

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

- [`doc/workflow-grammar.md`](workflow-grammar.md) — workflow grammar
- [`doc/workflow-grammar-concepts.md`](workflow-grammar-concepts.md) — workflow concepts and semantics
- [`doc/extensions-install.md`](extensions-install.md) — install optional extensions that may complement workflow usage
- [`doc/extensions.md`](extensions.md) — extension/tool details for workflow-adjacent extensions and shared workflow display conventions
- [`doc/tui.md`](tui.md) — general in-session command usage
- [`README.md`](../README.md) — top-level project overview
