# 198 — task-lifecycle chained workflow

## Intent

Provide a single orchestration workflow that runs a Munera task through its
entire lifecycle in one invocation, chaining the five existing task-lifecycle
workflows in order:

1. `review-task-design`
2. `create-task-plan`
3. `review-task-plan`
4. `implement-task`
5. `review-task-implementation`

Today an operator must invoke these five workflows manually and sequentially.
The goal is one named workflow (`task-lifecycle`) that takes a Munera task
identifier and drives all five stages back-to-back.

## Why

- Reduces friction and human bookkeeping for the standard
  design → plan → implement → review progression.
- Encodes the canonical task-lifecycle order as a single reusable artifact, so
  the sequence is consistent and discoverable rather than tribal knowledge.
- Each stage already operates independently on the task's Munera files; the
  orchestrator only needs to sequence them and thread the task identifier.

## Scope

In scope:

- A new project-local multi-step orchestration workflow file under
  `.psi/workflows/` named `task-lifecycle` (an `.edn` orchestration file, since
  it is multi-step).
- Sequential delegation to the five existing named workflows via the
  `:type :delegate` / `:target "<workflow-name>"` grammar.
- Threading the same Munera task identifier into every stage.

### Input threading mechanism

Each delegate step supplies the task identifier through its `:prompt-string`
using the `:map` form:

```clojure
:prompt-string {:type :map
                :fields {:input {:from :workflow-input :path [:input]}}}
```

This is required because, for a delegated workflow invocation, the delegated
step's rendered `:prompt-string` becomes that sub-workflow's `:workflow-input`.
The `:map` form makes the rendered `:prompt-string` — and therefore
`:workflow-input` — the map `{:input "<task-id>"}`, so a sub-workflow reference
of `{:from :workflow-input :path [:input]}` resolves the identifier.

Authority note: the runtime, not the prose doc, is the authority for this
mechanism. `psi.workflow-step-materialization.source-resolution/render-delegate-prompt-string`
returns a map (via `(into {} ...)` over `:fields`) for a `{:type :map}`
`:prompt-string`. `doc/workflow-grammar-concepts.md` § "Workflow input and
original request" describes `:workflow-input` only as the delegated step's
"fully rendered `:prompt-string`" / "final rendered prompt string" and presents
it as a string; it does not document the `:map` `:prompt-string` form (nor do
`doc/workflow-grammar.md` or `doc/workflow-ir.md`). That doc therefore does not
support — and textually contradicts — the map-shaped result. The mechanism is
correct against the runtime (and matches `gh-issue-implement.edn` /
`review-task-implementation.edn` usage); the concepts doc has a known gap for
the `:map` form.

All five targets uniformly require `:path [:input]` to resolve:

- `review-task-design`, `review-task-plan`, `implement-task` reference
  `{:from :workflow-input :path [:input]}` in their `final-summary` template
  `:vars`.
- `review-task-implementation` references `{:from :workflow-input :path
  [:input]}` directly in each delegate step's `:prompt-string :map` `:input`
  field.
- `create-task-plan` is a single `:session` step whose body
  (`create-task-plan-create-plan.md`) uses the `{{input}}` token. The
  workflow-loader compiler auto-wires the standard `{{input}}` token to
  `{:from :workflow-input :path [:input]}` (`compiler.clj` `standard-vars`), so
  it resolves identically to the others. (The earlier claim that
  `create-task-plan` was the odd one out was incorrect: `{{input}}` is the
  surface form of the same `:path [:input]` reference.)

This uses only the existing `:delegate` grammar; the `:map` `:prompt-string`
form is part of the converged grammar (cf. `gh-issue-implement.edn`,
`review-task-implementation.edn`) and is not a new step shape.

### Concrete step and file shape

Top-level workflow keys (every target `.edn` carries these; registry presence and
the verification test's "definition `\"task-lifecycle\"` is present" assertion are
keyed off the top-level `:name`):

- `:name "task-lifecycle"` — required.
- `:description` — required, e.g. "Run a Munera task through its full
  design → plan → implement → review lifecycle by chaining the five
  task-lifecycle workflows in order."

The five delegate steps, in order, with their `:name` and `:target`. Each step's
`:name` mirrors its `:target` (the convention used by
`review-task-implementation.edn`, whose first step is named for its purpose):

| order | `:name`                       | `:target`                     |
| ----- | ----------------------------- | ----------------------------- |
| 1     | `"review-task-design"`        | `"review-task-design"`        |
| 2     | `"create-task-plan"`          | `"create-task-plan"`          |
| 3     | `"review-task-plan"`          | `"review-task-plan"`          |
| 4     | `"implement-task"`            | `"implement-task"`            |
| 5     | `"review-task-implementation"`| `"review-task-implementation"`|

These are the five `:name`/`:type :delegate`/`:target` triples the verification
test asserts in order.

Per-step `:context`: each delegate step carries
`:context [{:type :source :from :workflow-original}]` and nothing else. This
matches the exemplar delegate steps (`gh-issue-implement.edn`,
`review-task-implementation.edn` first step), and is consistent with the
input-only context-threading decision: `:workflow-original` carries the original
request only — it does **not** thread prior-stage summaries forward. No step
references a prior step's yield in its `:context` (contrast the later steps of
`review-task-implementation.edn`, which deliberately chain prior-step yields; the
task-lifecycle orchestrator does not). The task identifier travels solely via the
`:map` `:prompt-string` `:input` field, not via `:context`.

Out of scope:

- Any change to the five existing target workflows.
- Conditional gating / early-exit between stages (see Resolved decisions below).
- New deterministic operations, prompt files, or grammar changes.
- Changes to how workflows are discovered, loaded, or reloaded.

## Overall functionality

- Input: a Munera task identifier supplied as the workflow input, addressable as
  `{:from :workflow-input :path [:input]}`.
- Behaviour: invoke the five workflows in the listed order, each delegated as a
  sub-workflow step, passing the task identifier as that sub-workflow's input
  via the `:map` `:prompt-string` form described above.
- Output: the orchestrator surfaces the outcome of the final stage
  (`review-task-implementation`).

### Final-stage surfacing

There is **no** additional synthesizing/`final-summary` step. The last delegate
step (`review-task-implementation`) surfaces its result directly, mirroring
`review-task-implementation.edn` itself, which ends on its last delegate step
with no trailing summary step.

A `:delegate` step does **not** yield text as a universal default. The
documented default (`doc/workflow-grammar-concepts.md` § default yielded-value
composition) is that a delegate step "yields the called workflow's yielded value
unchanged". The orchestrator's terminal output is text only because the chain
bottoms out in session steps:

- `task-lifecycle` last step delegates to `review-task-implementation`;
- `review-task-implementation` ends on its last delegate step (`review-code-shape`),
  which delegates to `review-step`;
- `review-step` terminates in a `:session` step, whose default yield is its
  `:final-llm-reply` text.

So the text yield is propagated unchanged up the delegate chain from the
terminal session step, rather than being a property of the `:delegate` step
itself. The terminal step therefore declares no explicit `:yields` (it relies on
this propagated default); no `:terminal-contract` is required.

Intermediate stage summaries are not threaded forward as authoritative input:
each sub-workflow re-inspects the task's Munera files (input-only threading, per
the Resolved decisions below). Optional supporting context may be added later
but is out of scope for the first cut.

## Constraints

- Use only the existing converged workflow grammar
  (`:type :invoke | :session | :delegate`); do not invent new step shapes. The
  `:delegate` `:prompt-string` `:map` form used for input threading is part of
  that existing grammar, not a new shape.
- Each delegate target is referenced by its static workflow name.
- Keep one clear purpose per workflow file.
- The workflow must parse, compile, and register on reload.

## Acceptance criteria

- A `task-lifecycle` workflow exists under `.psi/workflows/` and appears in the
  workflow definition registry after reload. The file declares top-level
  `:name "task-lifecycle"` and a `:description` (registry presence and the
  verification test's definition-presence assertion are keyed off the top-level
  `:name`).
- It contains exactly five sequential delegate steps whose `:name` equals its
  `:target`, targeting, in order, `review-task-design`, `create-task-plan`,
  `review-task-plan`, `implement-task`, `review-task-implementation`.
- Each delegate step carries `:context [{:type :source :from :workflow-original}]`
  and no prior-step yield context (input-only threading).
- Each step passes the Munera task identifier as the delegated workflow's input
  via `:prompt-string {:type :map :fields {:input {:from :workflow-input :path
  [:input]}}}`.
- The workflow parses and compiles cleanly (delegate targets resolve to
  workflow references). Verification is done by adding a `deftest` to
  `components/workflow-loader/test/psi/workflow_loader/workflow_definitions_test.clj`
  that loads `task-lifecycle.edn` via the existing `load-edn-only` helper
  (which calls `psi.workflow-loader.loader/load-workflow-definitions`), asserts
  `(empty? errors)`, asserts the definition `"task-lifecycle"` is present, and
  asserts the five step names/types/targets in order. This is the same
  parser/compiler/definition surface used by the sibling `*-test` deftests in
  that namespace (e.g. `review-task-implementation-test`, `create-task-plan-test`).
- CHANGELOG `[Unreleased]` MUST gain an `Added` entry for the new
  `task-lifecycle` workflow, following the precedent set by the existing
  `review-task-design` / `create-task-plan` entries (user-visible because it is
  invokable via `/delegate task-lifecycle`).
- `doc/workflows.md` MUST NOT be required to list `task-lifecycle`: that document
  is explicitly the example-led authoring guide ("the primary example-led
  guide"; "authoritative example set"), not an exhaustive enumeration of every
  project workflow. No `doc/workflows.md` edit is required for this task.

## Resolved decisions

- **Stage gating:** Plain-sequential. Every stage runs unconditionally; no
  `:judge`/`:on` short-circuit between stages in the first cut. Conditional
  gating is explicitly out of scope (see Out of scope).
- **Context threading:** Input-only. Each sub-workflow receives only the task
  identifier and re-inspects the task's Munera files. Prior-stage summaries are
  not threaded as authoritative input in the first cut.
