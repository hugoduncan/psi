# 184 workflow file kinds and md step prompts

## Intent

Allow workflows to be authored in two explicit file forms:

- **multi-step workflows** as `.edn` files
- **single-step workflows** as `.md` files

Also allow a step inside a multi-step `.edn` workflow to source its prompt from a single-step `.md` workflow file.

This task exists to make single-turn workflows lighter-weight to author and read, while preserving the existing richer multi-step workflow form for orchestration.

## Problem

Workflow authoring currently loads checked-in workflows only from markdown files under workflow roots, and current markdown workflow files combine workflow metadata, target-authored workflow EDN, and optional framing prose in one artifact.

That keeps a single checked-in file kind, but it does not distinguish two authoring intents clearly:

- a true orchestration with multiple explicit runtime steps
- a single prompt-driven workflow that is semantically one session step

The desired authoring model is more explicit:

- if a workflow is a true orchestration with multiple steps and control flow, it should live in `.edn`
- if a workflow is just one prompt-driven step, it should live in `.md`
- multi-step workflows should be able to reuse those single-step markdown prompt artifacts instead of duplicating prompt text inline

Without this split:

- simple workflows are more ceremony-heavy than needed
- prompt content is less ergonomic to write and review
- reusable single-step prompt assets are harder to share across larger workflow plans
- loader/runtime/docs contracts remain ambiguous about whether markdown bodies are framing prose for multi-step workflows, canonical prompt text for single-step workflows, or both

## Desired outcome

After this task:

- workflow loading distinguishes workflow kind by file type
- `.edn` workflow files represent multi-step workflows
- `.md` workflow files represent single-step workflows
- a multi-step `.edn` workflow step may reference a single-step `.md` workflow file as its prompt source
- single-step `.md` workflows may use frontmatter to control execution/session configuration such as session settings, model selection, and related per-workflow options
- loader, compiler, and docs use one explicit discovery and precedence contract across both file kinds

## Scope

### In scope

- defining the workflow authoring contract for `.edn` multi-step workflows versus `.md` single-step workflows
- defining the canonical structure of a single-step `.md` workflow file
- defining which configuration belongs in markdown frontmatter versus markdown body
- allowing multi-step workflow steps to source prompt content from a single-step `.md` workflow file
- defining how frontmatter from a referenced single-step `.md` file affects the consuming step
- defining discovery, naming, loading, compilation, validation, and error behavior needed to support both file kinds
- focused tests and documentation for the new workflow authoring model

### Out of scope

- redesigning general multi-step workflow semantics beyond what is needed for markdown-backed step prompts
- introducing additional workflow file kinds beyond `.edn` and `.md`
- adding arbitrary markdown includes outside the workflow system
- changing unrelated prompt, skill, or delegate semantics except where required for this workflow contract
- preserving backward compatibility for ambiguous mixed-definition same-name authoring across file kinds; this task chooses one explicit collision/error policy instead

## Workflow kinds

### Multi-step workflow

A multi-step workflow is authored as an `.edn` file.

It remains the artifact for:

- multiple named steps
- step-to-step control flow
- judges, branching, looping, and orchestration
- workflow-level structure that cannot be represented as one prompt turn

A multi-step workflow file compiles directly to the existing canonical workflow definition shape with `:steps [...]` and any other already-supported workflow-level keys.

### Single-step workflow

A single-step workflow is authored as an `.md` file.

It represents exactly one prompt-driven workflow step.

Its markdown body is the authored prompt content.

Its frontmatter carries workflow identity plus allowed execution/session configuration for that single step.

A single-step markdown workflow does not declare multiple named steps or internal control flow.

## Discovery and loading contract

Workflow discovery scans the same workflow roots used today, but now accepts both `.md` and `.edn` files:

- `~/.psi/workflows`
- `~/.psi/agent/workflows`
- `<project>/.psi/workflows`

Precedence across roots stays the existing loader precedence: later roots override earlier roots only when the workflow **name is otherwise unique by file kind**.

Within and across all roots, workflow names must be globally unique regardless of file kind. A `.md` single-step workflow and an `.edn` multi-step workflow must not share the same workflow name, even if they live in different roots or different files.

The collision policy is therefore:

- same workflow name appearing more than once with the **same file kind** across precedence-ordered roots keeps the current later-wins behavior and emits a duplicate warning
- same workflow name appearing with **different file kinds** is a load error, not a precedence case

This rejects ambiguous author intent such as a markdown single-step workflow and an EDN orchestration both claiming to be `planner`.

Built-in workflow discovery, `/delegate`, and `/delegate-reload` continue to operate on workflow names, not file paths. `/delegate-reload` broadens from “reload `.psi/workflows/*.md`” to “reload workflow definitions from workflow roots”, covering both `.md` and `.edn` files.

Documentation and any built-in examples/config paths that currently say `.psi/workflows/*.md` must change to say workflow definitions live under `.psi/workflows/` as `.md` or `.edn` according to workflow kind.

## Single-step markdown workflow contract

A single-step `.md` workflow file consists of:

- required frontmatter
- markdown body

The markdown body is the canonical prompt text for the single step.

The frontmatter defines workflow identity and allowed execution/session options.

The frontmatter is structured configuration only and is never prompt text.

A single-step `.md` workflow file must not contain a leading EDN workflow definition block. If the body begins with an authored EDN map, that is an authoring error because `.md` files are no longer the carrier for multi-step workflow definitions.

## Standalone compilation target for `.md` workflows

A standalone single-step `.md` workflow compiles into a canonical workflow definition with exactly one step:

- workflow `:definition-id` comes from frontmatter `name`
- workflow `:name` comes from frontmatter `name`
- workflow `:summary` / `:description` come from frontmatter `description`
- workflow `:workflow-file-meta` records file provenance details as needed, but workflow identity is not inferred from path
- workflow `:steps` is a one-element vector
- that single step is `{:name "step" :type :session ...}`

The step prompt is represented canonically as one session contribution that carries the markdown body text as the authored prompt. The body is not stored as workflow-level framing prose for `.md` workflows.

Workflow-level metadata ends at the frontmatter keys `name` and `description`. All other allowed frontmatter keys belong to the compiled single runtime step's session configuration.

“Exactly one prompt-driven step” therefore means:

- one workflow definition
- one canonical `:session` step
- no authored `:steps` vector in the file
- no internal control flow
- no second prompt source inside the same compiled step

The compiled single step uses the same runtime shape as other target-authored `:session` steps so downstream session execution, session-config resolution, and tests stay on the existing canonical path.

## Frontmatter schema and context rules

A single-step `.md` workflow frontmatter must allow exactly these keys:

- `name` — required string; workflow identity and canonical `:definition-id`
- `description` — required string; human-readable summary
- `tools` — optional vector of tool names; maps to session-step `:tools`
- `skills` — optional vector of skill names; maps to session-step `:skills`
- `model` — optional existing session-step `:model` value; reused exactly
- `thinking-level` — optional existing session-step `:thinking-level` value; reused exactly
- `response-mode` — optional existing session-step `:response-mode` value; reused exactly
- `prompt-mode` — forbidden in `.md` frontmatter because prompt mode is inherited from the parent session rather than authored per workflow step today
- `temperature` — optional existing session-step `:temperature` value; reused exactly
- `logprobs` — optional existing session-step `:logprobs` value; reused exactly
- `top-logprobs` — optional existing session-step `:top-logprobs` value; reused exactly and only meaningful when `logprobs` is enabled
- `prompt-component-selection` — optional existing session-step `:prompt-component-selection` value; reused exactly

No other frontmatter keys are allowed for this task. Unsupported keys must fail clearly during load/compile; they must not be ignored.

These keys intentionally reuse existing session-step field names exactly wherever possible so `.md` frontmatter is a thin authoring surface over the canonical single compiled `:session` step.

### Standalone `.md` context

When a `.md` workflow is run standalone by name via `/delegate`, all allowed optional frontmatter keys above may apply.

### Referenced-from-`.edn` step context

When a `.md` workflow is referenced from a step inside a multi-step `.edn` workflow:

- `name` and `description` remain required in the referenced file because the file is still a workflow artifact with independent identity
- prompt-body text always contributes the step prompt source
- `tools`, `skills`, `model`, `thinking-level`, `response-mode`, `temperature`, `logprobs`, `top-logprobs`, and `prompt-component-selection` may flow into the consuming `:session` step subject to one precedence rule
- `name` and `description` never override consuming step fields because they are workflow metadata, not session-step config

## Referencing markdown workflows from multi-step workflows

A step in a multi-step `.edn` workflow may source its prompt from a single-step `.md` workflow file only when the consuming step is itself a `:session` step.

This task does **not** make markdown prompt workflows a generic prompt source for `:delegate` or `:invoke` steps.

The exact authored syntax is:

```clojure
{:name "plan"
 :type :session
 :prompt-workflow "planner.md"}
```

`:prompt-workflow` is a file reference string, not a workflow name.

Rationale:

- this avoids ambiguity between workflow identity and file identity
- it keeps reuse rename-safe within the authoring tree because the consuming file names the actual asset it depends on
- it lets standalone workflow identity (`name`) remain independent from local file references and duplicate-name error checking

A `:session` step may specify **exactly one** prompt source:

- inline authored contributions/prompt text through the existing session-step surface
- or `:prompt-workflow "...md"`

It must not specify `:prompt-workflow` together with any existing inline prompt-authoring surface that would create a second competing prompt source. In particular, `:prompt-workflow` is forbidden together with step-authored prompt/contribution fields that already define the step's main prompt body. The loader/compiler must fail clearly instead of trying to merge competing prompt texts.

When `:prompt-workflow` is present, the referenced markdown body becomes the canonical prompt body for that step, and the referenced file's allowed session config fields become default step config.

## Precedence and merge rule

When a `:session` step uses `:prompt-workflow`, the merge rule is:

1. start from the referenced `.md` workflow's allowed session config fields
2. overlay the consuming `.edn` step's own fields
3. keep the referenced markdown body as the step prompt body unless the step also tries to define another prompt source, which is invalid rather than mergeable

So the explicit precedence rule is:

- consuming `.edn` step fields override referenced `.md` frontmatter for the same session-step option
- competing prompt bodies are not overridden; they are rejected as invalid dual prompt sources

## Canonical path resolution and identity

`:prompt-workflow` paths resolve relative to the consuming `.edn` file's directory.

This is the only allowed reference mode in this task.

Not allowed in this task:

- lookup by workflow name
- workflow-root-global search for prompt-workflow references
- cross-root implicit fallback
- absolute paths

This keeps references local, explicit, and rename-safe under normal repository moves.

Identity rules:

- runtime workflow identity still comes from frontmatter `name`, not file path
- `:prompt-workflow` resolution uses file path only to find the referenced markdown asset at compile/load time
- after resolution, the referenced file contributes its compiled single-step prompt/config contract, not a second separately-runnable nested run

## Error behavior

The loader/compiler must fail clearly for these cases:

- `.md` workflow missing required `name`
- `.md` workflow missing required `description`
- `.md` workflow contains unsupported frontmatter keys
- `.md` workflow body starts with an EDN workflow definition block or otherwise attempts multi-step/orchestration authoring
- standalone `.md` workflow body is empty
- `.edn` workflow file does not define a valid multi-step workflow shape
- `:prompt-workflow` is used on a non-`:session` step
- `:prompt-workflow` points to a missing file
- `:prompt-workflow` points to a non-`.md` file
- `:prompt-workflow` points to a `.md` file that does not satisfy the single-step markdown workflow contract
- consuming `:session` step specifies `:prompt-workflow` plus another prompt-defining authored field, creating two competing prompt sources
- the same workflow name is defined by both a `.md` and an `.edn` file anywhere in discovered workflow roots

Duplicate same-kind names across precedence-ordered roots remain warnings with later-wins behavior; mixed-kind duplicate names are errors.

## Required behavior

1. Workflow file type determines workflow kind:
   - `.edn` means multi-step workflow
   - `.md` means single-step workflow
2. Workflow discovery scans the existing workflow roots and accepts both `.edn` and `.md` files.
3. A single-step `.md` workflow represents exactly one prompt-driven `:session` step.
4. The markdown body of a single-step `.md` workflow is the canonical prompt content for that step.
5. Frontmatter in a single-step `.md` workflow is structured configuration for allowed session-step options and is not part of the prompt body.
6. A standalone single-step `.md` workflow compiles to a canonical workflow definition with exactly one `:session` step.
7. A multi-step `.edn` workflow step may reference a single-step `.md` workflow file as its prompt source only through `:prompt-workflow` on a `:session` step.
8. When a multi-step step references a single-step `.md` workflow file, the body and supported frontmatter from that markdown workflow apply to the consuming step according to one explicit precedence rule.
9. The precedence rule is: step-local `.edn` fields override referenced `.md` frontmatter when both specify the same execution/session/model option.
10. Dual prompt sources are invalid: a consuming step may not use `:prompt-workflow` together with another authored main prompt source.
11. `:prompt-workflow` paths resolve relative to the consuming `.edn` file.
12. Unsupported, invalid, or misplaced frontmatter/configuration must fail clearly during load or compilation.
13. A single-step `.md` workflow must fail clearly if it omits required `name` or `description` frontmatter.
14. A single-step `.md` workflow must fail clearly if it attempts to express multi-step orchestration structure.
15. Existing standalone workflow invocation by workflow name continues to work; `/delegate-reload` reloads both workflow file kinds.

## Acceptance criteria

This task is complete when:

- the workflow system supports two explicit authoring kinds: multi-step `.edn` workflows and single-step `.md` workflows
- workflow discovery/loading docs and implementation accept `.psi/workflows/*.md` and `.psi/workflows/*.edn` with one explicit precedence/collision policy
- a single-step `.md` workflow has a documented and validated contract of required frontmatter (`name`, `description`), explicit allowed optional frontmatter keys, and markdown body
- a standalone single-step `.md` workflow compiles to exactly one canonical `:session` step
- a multi-step `.edn` `:session` step can source its prompt from a single-step `.md` workflow file via `:prompt-workflow`
- frontmatter from a referenced single-step `.md` workflow can control supported session/model/step options for the consuming step
- precedence and validation rules are explicit and unambiguous, including that consuming `.edn` step fields override referenced `.md` frontmatter while competing prompt sources are rejected
- invalid file-kind usage, invalid frontmatter, missing/wrong-kind referenced files, mixed-kind duplicate names, and unresolved references fail clearly
- documentation and focused tests cover both standalone single-step markdown workflows and reuse from multi-step `.edn` workflows
