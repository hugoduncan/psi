# Workflows

Psi can delegate reusable tasks to named workflows.

A workflow is a named prompt or orchestration loaded from `.psi/workflows/`
as a built-in core capability.

- `.md` files author single-step prompt workflows
- `.edn` files author multi-step orchestration workflows

Some workflows are single focused agents; others are multi-step flows that pass
results from one step to the next.

This document is the primary example-led guide for workflow authoring. It
covers the user-facing workflow surface, how to enable and run workflows, and
the supported target-authored grammar.

## Prerequisite

Workflow loading is built in.

No extension manifest entry is required to enable `/delegate` or `.psi/workflows/` discovery.

Optional workflow-adjacent extensions such as `psi/mementum` still use normal extension install manifests.
For manifest details and install options, see [`doc/extensions-install.md`](extensions-install.md).

## Where workflows live

Workflow definitions are discovered from:

```text
~/.psi/workflows/
~/.psi/agent/workflows/
<project>/.psi/workflows/
```

Accepted file kinds:

```text
*.md   ; single-step prompt workflows
*.edn  ; multi-step orchestration workflows
```

Within precedence-ordered roots, later same-kind duplicates win with a warning.
Mixed-kind same-name collisions (`planner.md` plus `planner.edn`) are load
errors.

This repository includes many examples there, including:

- `plan-build`
- `plan-build-review`
- `delegate-build-review`
- `gh-bug-triage-modular`
- `reduce-incidental-complexity`
- `reduce-architectural-complexity`
- `planner`
- `builder`
- `reviewer`

The authoritative example set is:

- `planner` / `builder` / `reviewer` — single-step markdown prompt workflow examples
- `plan-build` — compact multi-step orchestration example
- `plan-build-review` — compact multi-step orchestration example
- `delegate-build-review` — executable delegate-heavy target-authored example proving canonical downstream delegated yielded-text consumption
- `gh-bug-triage-modular` — richer target-authored orchestration example proving delegated yielded text plus structured delegated handoff consumption

Note: this repository still contains transitional checked-in `.md` workflow
artifacts from the pre-split contract. New authoring should treat `.md` as
single-step and `.edn` as multi-step.

The current remaining deferred-migration markdown wrappers are:

- `gh-bug-discover-and-read.md`
- `gh-bug-post-repro.md`
- `gh-bug-reproduce.md`
- `gh-issue-create-worktree.md`
- `gh-issue-push-intent.md`
- `gh-issue-task-intent.md`
- `implement-task-in-worktree.md`

Those files still begin with legacy EDN workflow maps and are intentionally
tracked by the repo-corpus validation test as outstanding migration blockers,
not as valid single-step markdown examples. A later migration task should move
those orchestration workflows to `.edn` and leave only true standalone prompt
workflows in `.md`.

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

The underlying `delegate` tool also exposes management actions for active and
retained workflow runs: `list`, `continue`, and `remove`. `delegate list` is
scoped to the invoking session; it shows delegated workflow runs owned by that
session and does not show runs from unrelated sessions. Listed ids are canonical
workflow run ids, so the same id can be used with `delegate continue` when the
workflow status supports continuation, or with `delegate remove` while the run
still exists.

List output reports the canonical workflow status as the primary status and, when
available, the delegate/background attempt status separately. For example, a
blocked workflow may list as primary status `blocked` with a separate delegate
attempt status of `completed`, and a retained timed-out delegate attempt may show
the canonical workflow status plus a separate `delegate timed-out` status.

`delegate remove` requests canonical workflow removal through the workflow
runtime dispatch path. For a live top-level run, removal is cancel-then-remove:
psi first marks the run and its live descendant sub-runs cancelled, terminalizes
workflow background-job projections as cancelled, interrupts the top-level
workflow worker before dropping its runtime handle, then removes the canonical run
record. This prevents removed delegated workflows from continuing in the
background.

Direct removal of a live nested delegate sub-run does not interrupt the shared
parent/top-level workflow worker. Instead, psi aborts that sub-run's in-flight
child turn, removes the sub-run record, and lets the parent workflow continue by
observing the delegated step as a cancelled/removed failed step.

Removing an already-terminal run is idempotent canonical-record cleanup. Removing
an absent run is a success/no-op for the canonical record and still performs
stale runtime-handle cleanup when a leftover handle exists. Removal no longer uses
command-layer active-job pre-cleanup or a fail-if-pre-cleanup-fails model; job
terminalization and worker-handle cleanup are dispatch-owned runtime effects.

## Reloading workflow definitions

When editing workflow files under `.psi/workflows/`, reload them without restarting psi:

```text
/delegate-reload
```

Reloading:

- re-discovers workflow definitions
- registers changed definitions
- retires removed definitions

Use this during workflow authoring or prompt iteration.

## Workflow-run retention and cleanup

Psi automatically cleans up retained terminal workflow runs and their linked
workflow-owned child-session trees.

Retention applies per originating agent session. The retained terminal status set
is `:completed`, `:failed`, and `:cancelled`. Non-terminal workflow runs remain
present and are never removed by this cleanup.

The effective retention count is read from runtime config at:

```clojure
[:config :completed-workflow-run-retention-count]
```

Behavior:

- when the config key is absent, the default retention count is `1`
- when the count is `2` or higher, psi keeps that many newest retained terminal
  workflow runs for each originating session
- when the count is `0`, a newly terminal retained run is removed immediately
- negative values are invalid and are rejected

Newest-first ordering uses workflow-run terminal transition time (`:finished-at`)
with canonical workflow-run creation order as the deterministic tie-breaker when
multiple runs share the same terminal timestamp.

Retention counts only **top-level** delegated runs. A nested run created by a
`:delegate` workflow step belongs to its delegating parent run, not to the
originating session's retention budget, so delegating a single multi-step
workflow does not evict its own internal sub-runs (which would otherwise delete
the run you just started, along with its sessions). When a top-level run is
removed, its nested `:delegate` sub-runs and their linked workflow-owned
sessions are removed transitively along with it.

When an older retained terminal workflow run is removed, psi also tree-closes
that run's linked workflow-owned child sessions. The cleanup target set is the
canonical deduplicated union of linked execution-session ids and judge-session
ids recorded on that run. Missing, already-closed, duplicate, or non
workflow-owned linked roots are skipped.

This changes user-visible workflow introspection and listing behavior: retained
terminal workflow runs and their workflow-owned child sessions no longer remain
indefinitely once newer retained terminal runs for the same originating session
exist.

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

## Session profiles and inherited defaults are snapshotted at invoke time

Workflow steps can request a named session profile with `:session-profile`. The
name is resolved against the invoking session's effective config at workflow-run
creation time, then stored in the run's canonical `:session-profile-snapshot`.
Later user/project config edits do not affect that run's later steps, delegated
runs, or blocked-run resume.

```edn
{:name "plan"
 :type :session
 :session-profile :planning
 :thinking-level :high
 :contributions [{:type :source :from :workflow-input}]}

{:name "build"
 :type :delegate
 :target "builder"
 :session-profile :coding
 :prompt-string "Build {{input}}"}
```

Single-step markdown workflows can use frontmatter:

```markdown
---
name: planner
session-profile: planning
---
Plan {{input}}.
```

For profile-supported fields, workflow precedence is:

```text
explicit step setting > resolved :session-profile setting > inherited workflow-run default > fallback
```

This task's direct authored workflow overrides remain `:model` and
`:thinking-level`. Profile-derived `:speed-mode` and `:effort-override` can still
flow into the effective step config. A delegated step passes only the resolved
concrete defaults to the child run's narrow `:inherited-defaults`; profile names,
profile maps, and invalid-profile diagnostics stay in the run's
`:session-profile-snapshot`.

When a step does not specify its own override, it inherits its default session
details — model, prompt-mode, tools, skills, thinking-level, speed-mode, and
effort-override — from the session that invoked the workflow. These inherited
defaults are captured as a **snapshot when the workflow run is created**, and
that snapshot becomes part of the run's replayable canonical state.

Consequences:

- Changing the invoking session's model (or the user/project default model)
  *after* a workflow has started has **no effect** on the still-running
  workflow's later steps — they continue to use the model that was in effect
  when the workflow was invoked.
- A nested/delegated sub-workflow inherits the delegating step's **effective**
  config: the run snapshot combined with that step's own overrides, captured
  when the sub-delegation is created. So a step that overrides the model and
  then delegates passes the overridden model down to the sub-delegation.
- A step that specifies an explicit override (`:model`, `:tools`, `:skills`,
  `:thinking-level`, etc.) still applies that override — the snapshot governs
  only the inherited default used when a step gives no value of its own.
- Resuming a blocked run reuses the original invoke-time snapshot;
  *continuing* a terminal run is a fresh top-level invocation that captures a
  new snapshot from the live session at continuation time.

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

1. create or edit `.psi/workflows/<name>.md` for a single-step prompt workflow, or `.psi/workflows/<name>.edn` for a multi-step orchestration workflow
2. run `/delegate-reload`
3. invoke it with `/delegate <name> <prompt>`
4. tighten the authoring shape or reference wiring
5. reload and repeat

## `.md` single-step workflow authoring

`.md` single-step workflows support `{{input}}` and `{{original}}` template
variables directly in the body — no frontmatter declaration is needed.

- `{{input}}` — expands to the workflow's input text (the prompt string passed
  to `/delegate`)
- `{{original}}` — expands to the carried original request context
  (`:workflow-original`)

Minimal example:

```markdown
---
name: my-workflow
description: A simple single-step workflow
tools:
  - read
  - bash
---
Perform the task described by {{input}}.
```

### Custom vars

For custom variable bindings, declare a `vars:` key in the frontmatter as an
EDN string. Each declared var must specify a `:from` source — either
`:workflow-input` (with optional `:path`) or `:workflow-original`:

```markdown
---
name: my-workflow
description: Workflow with a custom var
tools:
  - read
vars: '{"task-path" {:from :workflow-input :path [:task-path]}}'
---
Work on the task at {{task-path}}.
Input summary: {{input}}.
```

Allowed `:from` values in `vars:`:

- `:workflow-input` — the workflow input map; use `:path` to extract a nested field
- `:workflow-original` — the carried original request context

### Unknown vars produce a compile-time error

Any `{{varname}}` token in the body that is neither a standard var (`input`,
`original`) nor declared in `vars:` produces a compile-time error when the
workflow file is loaded. This catches typos and missing declarations before
runtime.

Tokens that do not match the var pattern (e.g. `{{1bad}}`, `{{}}`) pass through
literally and are not subject to this check.

## Architectural-fit design review

`review-task-design` reviews a Munera task `design.md` along three aspects, in
order: **architectural fit**, then **ambiguity**, then **inconsistency**.

The architectural-fit aspect runs **first** so structural misfit is caught
before fine-grained clarity/consistency polishing. Its `architecture-review`
step is the workflow's start step (the first `:steps` element) and loads the
`review-task-architecture` skill — a thin lens that asks the reviewing agent to
check the design's fit with the current architecture, consulting the in-context
architecture sources (`AGENTS.md`, `META.md`, `doc/architecture.md`) as needed.
It judges architectural *fit* — does the design follow the one-way principle,
the dispatch/resolver/mutation boundaries, VSM layering, extension isolation,
effects-as-data, and the "no silent shims/adapters/compatibility layers" rule —
rather than correctness, clarity, or completeness.

Like the ambiguity and inconsistency aspects, architectural fit is a review
step + follow-up step pair gated by `pass-status-routing`: actionable misfits
are recorded as unchecked `design-steps.md` items, the `architecture-follow-up`
step reuses the shared `design`-profile follow-up (see below) to execute them,
and each pass advances deterministically through `architecture → ambiguity →
inconsistency → clarity-status`. `review-task-design` completes the remaining
phases in the current pass even when an earlier phase produced actionable
feedback. The `clarity-status` step is EDN invoke routing, not a standalone
prompt workflow; it remembers whether any phase in the completed pass returned
`ACTIONABLE_FEEDBACK` from the phase outputs rather than re-reading task
artifacts after follow-up execution. A clean pass goes to `final-summary`; a
feedback pass restarts at `architecture-review` with `:max-iterations 6`, so the
workflow can enter the first phase at most six total times including the initial
pass.

## Shared review follow-up steps

The review workflows (`review-task-design`, `review-task-plan`, and the
`review-step` sub-workflow that `review-task-implementation` delegates to) all
run the same kind of follow-up step after each review pass: execute the
unchecked items the preceding review pass just added, update the in-scope task
artifacts, mark completed items done, leave blocked items unchecked with a terse
`implementation.md` reason, and commit.

That follow-up behaviour is shared across hosts via **two** profile follow-up
`.md` files, referenced with `:prompt-workflow`. A profile is chosen by *which*
file a host references — there is no per-step parameter to get wrong:

| Profile  | File                         | Items file        | Writable artifacts                                  | Forbidden / read-only            |
| -------- | ---------------------------- | ----------------- | --------------------------------------------------- | -------------------------------- |
| `design` | `review-follow-up-design.md` | `design-steps.md` | `design.md`, `design-steps.md`, `implementation.md` | `plan.md`/`steps.md` (forbidden) |
| `steps`  | `review-follow-up-steps.md`  | `steps.md`        | `plan.md`, `steps.md`, `implementation.md`, plus referenced code/tests/docs | `design.md` (read-only context)  |

- `review-task-design` references the `design`-profile follow-up from its
  `architecture-follow-up`, `ambiguity-follow-up`, and `inconsistency-follow-up`
  steps.
- `review-task-plan` and `review-step` reference the `steps`-profile follow-up;
  `review-task-implementation` inherits it transitively via `review-step`.

The `steps` profile hosts both plan review and *implementation* review. When it
hosts implementation review (via `review-step`), follow-up items routinely
require editing the actual code, tests, and docs they reference — so the
`steps`-profile follow-up explicitly permits writing those referenced source
artifacts, not just the task files. For plan review there are simply no
code/test items to edit, so the broader scope is harmless.

Each shared file uses generic "preceding review pass" wording rather than naming
a specific review aspect: every host wires the follow-up immediately after its
review step, so "the preceding review pass" is unambiguous at runtime. Both
profiles execute only the items the immediately preceding review pass added,
leaving any pre-existing unchecked items untouched.

Host routing owns repetition; only the follow-up step *body* is shared.
`review-task-design` and `review-task-plan` both finish every phase in the
current pass before the deterministic `clarity-status` invoke step decides
whether to restart another full pass from the first phase. `review-task-design`
can enter `architecture-review` at most six total times, and `review-task-plan`
can enter `ambiguity-review` at most five total times. `review-step` loops back
to its `review` step (`REPEAT → review`) with `:max-iterations 10`, so
implementation-review profiles delegated through `review-task-implementation`
can enter the review step at most ten total times (the initial review plus up to
nine follow-up-driven re-reviews). The workflow runtime counts
`:max-iterations` as total target-step entries, including the initial entry.

## Task knowledge extraction

`extract-task-knowledge` mines a Munera task for durable, project-general
mementum knowledge.

```text
/delegate extract-task-knowledge {NNN-slug}
```

Standalone extraction mines any task that resolves uniquely under
`munera/closed/{NNN-slug}` or `munera/open/{NNN-slug}`. Inputs may be either an
exact `NNN-slug` or an exact `munera/{open|closed}/NNN-slug` task path; other
shapes, missing tasks, and duplicate matches stop with no mementum writes.

The workflow reads the task artifacts (`design.md`, `plan.md`, `steps.md`, and
`implementation.md` when present) plus task-scoped git history only: commits
touching the task directory, commits whose message mentions the task id or slug,
and SHAs explicitly recorded in the artifacts. It recalls existing
`mementum/memories/` and `mementum/knowledge/` before writing, then updates or
skips existing entries rather than duplicating them.

Extraction uses conservative mementum gates: an insight must help future AI
sessions, be likely to recur or have taken more than one attempt to learn, be
useful to the project outside the task's own context, and be significant for
future project development. Task-local trivia is rejected, and uncertain cases
are skipped. Producing zero entries is a successful outcome.

When entries pass those filters, the workflow writes mementum memories or
knowledge pages and commits them autonomously using the mementum commit
conventions; it does not request human approval.

`task-lifecycle` gates its final extraction stage after
`review-task-implementation`. It runs `extract-task-knowledge` only when the
immediately preceding implementation-review yielded text contains
`PASS_STATUS: REVIEW_COMPLETE`; otherwise it skips extraction and returns a
summary preserving the implementation-review outcome. When extraction does run,
that review text is carried in the extraction delegate's labeled
`:implementation-review-yield` prompt-string field and rendered to the markdown
workflow as `{{implementation_review_yield}}` so the final extraction summary can
preserve the prior implementation-review/lifecycle outcome alongside any captured
knowledge. `{{original}}` / `:workflow-original` remains ambient reference
context.

## Incidental-complexity simplification

`reduce-incidental-complexity` is an autonomous workflow that simplifies **one
aspect of the system per run** by targeting *incidental* complexity — the
comprehension burden that comes from how code is built, not from the problem it
solves. Running it repeatedly walks the codebase down its incidental-complexity
gradient, one isolated, reviewable change at a time.

```text
/delegate reduce-incidental-complexity
```

How it selects a target: raw cyclomatic complexity (`gordian complexity`)
surfaces *essential* complexity (flat dispatch/registration tables are
irreducible decision logic — false positives for simplification). The
discriminator for *incidental* complexity is **comprehension burden the
branching does not explain**: high `gordian local` burden against low/moderate
cyclomatic complexity. The `incidental-complexity-finder` skill encodes this as
`gap = lcc-total / max(cc, 1)`, joins the two `gordian` lenses on
`(ns, var, arity, line)` (`line` disambiguates same-named null-arity
`defmethod` units so the join stays deterministic), qualifies units with
`lcc-total ≥ 5.0 ∧ gap ≥ 2.0`, ranks by
`gap`, and applies an essential-vs-incidental judgment guard over the top 5 to
discard false positives. It selects exactly one unit, or reports that none
qualifies. For target-present runs, the generated task records the selector
justification in committed task artifacts: `design.md` explains why the chosen
unit passed the incidental guard, while `coverage-map.md` records selector proof,
top-5 guard evidence, rejected essential false positives when present, and any
marginal-target concerns, falsification evidence, or scope-review questions.

The workflow runs entirely in the invoking session's current worktree. The
caller is responsible for starting it from the intended branch/worktree; the
workflow does **not** call `work-on`, create a branch, or switch worktrees.
Workflow child sessions and nested delegated workflows inherit the same session
worktree.

The target-present workflow exposes the generated task lifecycle as explicit
phases instead of hiding it behind one opaque lifecycle delegate:

1. **select-and-create** (`:session`) — confirms the current git context,
   applies the `incidental-complexity-finder` skill, and **stops early** with no
   task if nothing qualifies. Otherwise it captures parse-checked
   `before-local.json` (per-unit burden baseline; JSON with a `units` array) and
   `before-diagnose.edn` (architectural gate baseline) into the generated task
   directory, allocates the next Munera task id in the current worktree, writes a
   **two-phase behaviour-preserving refactor** `design.md`, creates an initial
   `coverage-map.md` proof scaffold, commits them on the current branch, and
   emits a structured `munera_task_path:` handoff.
2. **extract-task-path** — validates the handoff through deterministic
   `workflow/munera-open-task-path-routing` before any task-local step runs.
   Downstream task identity is the extracted root-relative task path; the full
   selection handoff is reference context only. Malformed handoffs route to a
   terminal stop that does not invent a task path or read task artifacts.
3. **review-task-design**, **create-task-plan**, and **review-task-plan** — reuse
   the standard Munera design/plan review workflows while keeping the same
   inherited worktree and validated task path.
4. **clean-baseline** — before characterization work begins, records a
   task-local `characterization-baseline.edn` with the current `HEAD`, status,
   target/source paths, and any explicitly classified pre-existing task-artifact
   or doc dirt. Dirty target/source paths at this point stop the workflow instead
   of being absorbed into the unmodified-behavior baseline.
5. **coverage-review** / **coverage-disposition** / **coverage-fix** — iterates a
   pre-simplification characterization-test-net gate. Review completion means
   nominal, edge, and boundary behavior relevant to the target is sufficiently
   covered and green against current behavior. Fixable gaps route to a
   constrained coverage-fix pass that may add characterization tests and
   explicitly justified minimal testability seams only. Infeasible
   characterization records the reason and stops before simplification. These
   steps maintain `coverage-map.md` with authoritative test commands, coverage
   dispositions, latest counts when available, and the relationship to
   `characterization-baseline.edn`.
6. **diff-gate** — compares both committed changes since the recorded baseline
   `HEAD` and the current uncommitted worktree status/diff, then classifies every
   coverage-phase change. Only characterization tests, task artifacts, docs, and
   explicitly justified minimal testability seams may pass. Unclassified or
   non-minimal source/target edits, broad production edits, or premature
   simplification/refactor work stop the workflow before implementation.
7. **implement-task** and **review-task-implementation** — only after the
   characterization net and diff gate pass, delegate simplification
   implementation and implementation review.
8. **incidental-validation-capture** — captures and parse-checks
   `after-local.json`, writes a deterministic `incidental-burden-check.edn` for
   the A5 target-reduction and A2 relocation-guard checks, and captures
   parse-checked `incidental-gate.edn` for the A3 Gordian gate. Exit-0 unreadable
   JSON/EDN is treated as failure and replaced by a readable failure map; fixable
   validation failures route back through implementation repair, while
   unrecoverable capture failures route to a validation terminal stop.
9. **proof-sync** / **proof-sync-fixed-point** / **final-summary** — rereads the
   committed task-local proof authority (`coverage-map.md`,
   `characterization-baseline.edn`, before/after Gordian artifacts, and task
   notes) after review follow-ups. Final success is reachable only from a
   clean/no-op proof-sync pass or a clean read-only fixed-point pass. If
   proof-sync mutates stale proof artifacts, it commits those updates and routes
   back through coverage review, validation recapture, or fixed-point verification
   before any final summary.

The first step uses deterministic `PASS_STATUS` routing to send no-target runs
directly to workflow completion. When the selection output contains no valid
`munera_task_path:` line, every downstream design/plan/test-net/implementation
step is skipped and the run ends after reporting that no qualifying target was
found or that task identity could not be validated.

Each generated task is a behaviour-preserving refactor: **Phase 0** establishes
a green characterization-test safety net (gating all refactoring), and
**Phase 1** decomplects the target under objective acceptance — the target's
`lcc-total` decreases versus `before-local.json` (the A5 burden-reduction
check), a per-unit relocation guard holds (every new or below-ceiling after-row
`u` satisfies `after(u) < B`, where `B := before(target)` read from
`before-local.json`, so a tangle is never merely relocated into a new seam or a
sibling rather than reduced), `gordian gate --baseline
munera/open/NNN-slug/before-diagnose.edn --fail-on
new-cycles,new-high-findings --max-new-medium-findings 0` passes (with
`NNN-slug` replaced by the generated task id), and all tests stay green. The
workflow ends
with a completed, reviewed task on the local worktree branch; it does **not**
push or open a PR — that decision is left to the user.

## Architecture-level simplification

`reduce-architectural-complexity` is the architecture-level sibling of
`reduce-incidental-complexity`. It targets code **above the function/executable
unit level**: namespaces, namespace families, namespace pairs, or communities
ranked by Gordian's architecture-target lens.

```text
/delegate reduce-architectural-complexity
```

The workflow runs entirely in the invoking session's current worktree. The caller
is responsible for starting it from the intended isolated branch/worktree; it
does **not** call `work-on`, create or switch worktrees, push, or open a PR.

Selection uses `bb gordian architecture-targets --edn`. The workflow consumes the
authoritative top-level `:winner` and `:candidates` EDN envelope, then optionally
runs `bb gordian target-issues --candidate '<candidate-id>' --edn` only for
post-selection framing. Unsupported or failed `target-issues` framing does not
change the selected target and does not force a no-target stop; missing or
uninterpretable `architecture-targets` output does stop before task creation.

Target-present runs create a Munera task under `munera/open/NNN-slug/` with
worktree-root-relative Gordian artifacts such as `before-diagnose.edn`,
`architecture-targets.edn`, and either `target-issues.edn` or
`target-issues-unavailable.edn`. A dedicated `extract-task-path` step validates
the generated `munera_task_path:` handoff before any downstream task consumer
runs.

Before implementation, the workflow enforces a test-net gate adapted for
architecture targets: clean baseline recording, coverage review, a constrained
characterization-test fix loop for fixable gaps, terminal stop for infeasible
coverage, and a diff gate that allows only characterization tests, task
artifacts, docs, and explicitly justified minimal testability seams before
simplification. `implement-task` is unreachable until coverage and diff gates
pass.

After implementation, the workflow captures objective Gordian validation artifacts
(`after-diagnose.edn`, `after-architecture-targets.edn`, `architecture-compare.edn`,
and `architecture-gate.edn`) before review. Each artifact is parsed immediately
after it is written; exit-0 unreadable or truncated EDN is not accepted as proof
and is replaced by a readable failure map. Fixable validation failures route back
through implementation repair via deterministic validation-capture disposition;
unrecoverable capture failures route to a validation terminal stop with the
failing gate context. Successful validation then runs six explicit `review-step`
gates in order: `task-implementation-review`, `task-test-review`, the
architecture-specific `review-implementation-architecture`, `test-shaper`,
`review-task-docs`, and `code-shaper`. The architecture review skill reads
task-local Gordian artifacts and project architecture sources rather than relying
on inlined workflow context.

Target-present architecture tasks also use a committed `coverage-map.md` proof
artifact. The generated task creates an initial scaffold recording the selected
candidate identity, score, confidence, source areas, pending coverage/test fields,
and validation artifact references. Low-confidence winners do not auto-stop when
otherwise interpretable, but the generated design records actionability,
falsification evidence, review questions, and any scope-narrowing considerations.

Both simplification workflows use split terminal-stop summaries for malformed
task paths, clean-baseline failures, coverage-disposition stops, diff-gate stops,
validation-capture stops, and proof-sync fixed-point failures. Each terminal stop
receives explicit preceding-gate context in workflow topology and reads durable
task-local findings when a validated task exists, rather than inferring causes
from missing files or hidden runtime state.

Both workflows finish through a proof-sync fixed-point gate. `proof-sync` may
update stale proof artifacts and commit them, but that mutating pass cannot route
directly to final success. It must emit a deterministic disposition marker that
routes to coverage review, validation recapture, or a read-only fixed-point
verification. The final summary independently reads committed proof artifacts and
must not claim proof coherence from review prose or prior workflow yields alone.

Use `reduce-incidental-complexity` when the right target is a single high-burden
function/executable unit. Use `reduce-architectural-complexity` when the target is
a higher-level ownership, coupling, cycle, family, pair, or community problem.

## Related docs

- [`doc/workflow-grammar.md`](workflow-grammar.md) — workflow grammar
- [`doc/workflow-grammar-concepts.md`](workflow-grammar-concepts.md) — workflow concepts and semantics
- [`doc/extensions-install.md`](extensions-install.md) — install optional extensions that may complement workflow usage
- [`doc/extensions.md`](extensions.md) — extension/tool details for workflow-adjacent extensions and shared workflow display conventions
- [`doc/tui.md`](tui.md) — general in-session command usage
- [`README.md`](../README.md) — top-level project overview
