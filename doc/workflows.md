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

`delegate remove` deletes the canonical workflow run. When the listed run still
has an active delegate/background job, remove first cleans up or terminalizes that
active job so a later `delegate list` does not report a stale active attempt for a
removed workflow run. If that cleanup cannot be completed, remove fails with an
actionable error and leaves the canonical run visible/manageable.

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

## Inherited session defaults are snapshotted at invoke time

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
and the loop advances `architecture → ambiguity → inconsistency → clarity-status
→ final-summary`. The `final-summary` pass reports the architectural-fit pass
alongside ambiguity and inconsistency.

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

Host routing and looping are unchanged by this sharing: `review-task-design` and
`review-task-plan` advance forward one aspect at a time, while `review-step`
loops back to its review step (`REPEAT → review`, bounded by
`:max-iterations`). Only the follow-up step *body* is shared; the
judge/`:on` wiring around each follow-up stays with its host.

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
`(ns, var, arity)`, qualifies units with `lcc-total ≥ 5.0 ∧ gap ≥ 2.0`, ranks by
`gap`, and applies an essential-vs-incidental judgment guard over the top 5 to
discard false positives. It selects exactly one unit, or reports that none
qualifies.

The workflow has two steps:

1. **select-and-create** (`:session`) — fetches `origin/master`, applies the
   `incidental-complexity-finder` skill, and **stops early** with no worktree or
   task if nothing qualifies. Otherwise it creates an isolated worktree via
   `work-on` off `origin/master`, captures `before-local.json` (per-unit burden
   baseline) and `before-diagnose.edn` (architectural gate baseline) into the
   generated task directory, allocates the next Munera task id, writes a
   **two-phase behaviour-preserving refactor** `design.md`, commits it on the
   worktree branch, and emits a structured `worktree_path:` / `munera_task_path:`
   handoff.
2. **lifecycle-in-worktree** (`:delegate`) — routes that handoff into the
   `task-lifecycle-in-worktree` wrapper (a three-step
   `resolve-worktree` → `task-lifecycle` → `summary` adapter, structurally like
   `review-implementation-in-worktree`), which re-establishes the worktree and
   drives the generated task through the full design → plan → implement → review
   lifecycle.

Because the workflow grammar has no conditional/skip step, the `:delegate`
always runs even on an early-stop (no-target) handoff. The wrapper handles this
at the prompt level: when the handoff carries no `worktree_path:` /
`munera_task_path:`, `resolve-worktree` emits a `NO_TARGET` sentinel (without
calling `work-on`) and the `summary` step detects it and reports a clean
"no target this run; nothing done" result instead of inspecting a nonexistent
task.

Each generated task is a behaviour-preserving refactor: **Phase 0** establishes
a green characterization-test safety net (gating all refactoring), and
**Phase 1** decomplects the target under objective acceptance — the target's
`lcc-total` decreases versus `before-local.json`, net burden across the
metric-derived touched set strictly decreases, `gordian gate --baseline
before-diagnose.edn --fail-on new-cycles,new-high-findings
--max-new-medium-findings 0` passes, and all tests stay green. The workflow ends
with a completed, reviewed task on the local worktree branch; it does **not**
push or open a PR — that decision is left to the user.

## Related docs

- [`doc/workflow-grammar.md`](workflow-grammar.md) — workflow grammar
- [`doc/workflow-grammar-concepts.md`](workflow-grammar-concepts.md) — workflow concepts and semantics
- [`doc/extensions-install.md`](extensions-install.md) — install optional extensions that may complement workflow usage
- [`doc/extensions.md`](extensions.md) — extension/tool details for workflow-adjacent extensions and shared workflow display conventions
- [`doc/tui.md`](tui.md) — general in-session command usage
- [`README.md`](../README.md) — top-level project overview
