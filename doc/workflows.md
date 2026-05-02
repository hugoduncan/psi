# Workflows

Psi can delegate reusable tasks to named workflows.

A workflow is a named prompt or orchestration loaded from `.psi/workflows/*.md`
by the `psi/workflow-loader` extension. Some workflows are single focused agents;
others are multi-step flows that pass results from one step to the next.

This document covers the user-facing workflow surface: how to enable workflows,
list them, run them, reload them during development, and understand the basic
shape of workflow definitions.

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

- `plan-build-review`
- `planner`
- `builder`
- `reviewer`
- `gh-bug-triage`
- `gh-issue-ingest`
- `gh-pr-heal-check-loop`

## User-facing workflow commands

When `psi/workflow-loader` is active, psi exposes:

- `/delegate <workflow> <prompt>`
- `/delegate-reload`

Typical usage:

```text
/delegate planner analyze the scope of the current refactor
/delegate plan-build-review add user-facing workflow docs
/delegate gh-bug-triage
/delegate gh-bug-triage issue 123
```

What happens:

- psi starts the named workflow
- the workflow runs asynchronously
- you get an immediate acknowledgement
- the final workflow result is posted back into the same conversation

If you want a workflow to continue from a narrow request, put that request after
the workflow name as the prompt text.

## Listing workflows and runs

The workflow system also has a `delegate` tool with a `list` action. In normal
interactive use, the simplest path is usually to:

- inspect the `.psi/workflows/` directory
- read project prompts under `.psi/prompts/` for example invocations
- use `/delegate <workflow> <prompt>` directly when you already know the name

For extension/runtime-oriented details on the `delegate` tool surface, see
[`doc/extensions.md`](extensions.md).

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

## Two common ways to invoke workflows

### 1. Direct command invocation

Use `/delegate` when you know the workflow name.

Examples:

```text
/delegate reviewer review the current branch changes
/delegate lambda-build derive a concise lambda from this prompt
```

### 2. Natural-language invocation

This repository also contains prompt examples under `.psi/prompts/` such as:

- `.psi/prompts/gh-bug-triage.md`
- `.psi/prompts/gh-issue-ingest.md`

These document phrases like:

- `Run workflow gh-bug-triage`
- `Run workflow gh-bug-triage for issue 123`
- `Run workflow gh-issue-ingest`

Use those as operator examples and as reusable prompt patterns.

## Workflow kinds

### Single-step workflows

A single-step workflow is a named focused agent profile. For example, a workflow
can describe tools, skills, and instructions for one bounded job.

Example shape:

```markdown
---
name: gh-bug-triage
description: Find labeled GitHub bug-triage issues...
---
{:tools ["read" "bash" "edit" "write" "work-on"]
 :skills ["issue-bug-triage"]
 :thinking-level :high}

You are executing a focused GitHub bug-triage workflow in this repository.
```

This kind of workflow behaves like a reusable specialist session configuration.

### Multi-step workflows

A multi-step workflow orchestrates several named workflows in sequence.

Example shape:

```markdown
---
name: plan-build-review
description: Plan, build, and review code changes
---
{:steps [{:name "plan"
          :workflow "planner"
          :session {:input {:from :workflow-input}
                    :reference {:from :workflow-original}}
          :prompt "$INPUT"}
         {:name "build"
          :workflow "builder"
          :session {:input {:from {:step "plan" :kind :accepted-result}}
                    :reference {:from :workflow-original}}
          :prompt "Execute this plan:\n\n$INPUT\n\nOriginal request: $ORIGINAL"}]}
```

This kind of workflow passes outputs from earlier steps into later ones.

## Input and context flow

In multi-step workflows, child sessions use explicit `:session` data flow.

The most important pieces are:

- `:session :input` — what becomes `$INPUT`
- `:session :reference` — what becomes `$ORIGINAL`
- `:session :preload` — extra context loaded into the child session without
  changing `$INPUT` or `$ORIGINAL`

Interpretation:

- `$INPUT` is the immediate task for the current step
- `$ORIGINAL` is the original workflow request/reference context
- `:preload` is supporting context

When a step references a previous step, it uses that step's author-facing name,
for example:

```clojure
{:step "plan" :kind :accepted-result}
```

## Authoring guidelines

Prefer:

- one clear workflow purpose per file
- descriptive `name` and `description`
- small prompts with explicit goals and stopping conditions
- explicit `:session` wiring in multi-step workflows
- reusing focused workflows as building blocks for larger orchestrations

Good first workflow authoring loop:

1. create or edit `.psi/workflows/<name>.md`
2. run `/delegate-reload`
3. invoke it with `/delegate <name> <prompt>`
4. tighten the prompt or step wiring
5. reload and repeat

## Related docs

- [`doc/extensions-install.md`](extensions-install.md) — enable `psi/workflow-loader`
- [`doc/extensions.md`](extensions.md) — extension/tool details for `workflow-loader`
- [`doc/tui.md`](tui.md) — general in-session command usage
- [`README.md`](../README.md) — top-level project overview
