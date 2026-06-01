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
  `.psi/workflows/` named `task-lifecycle`.
- Sequential delegation to the five existing named workflows via the
  `:type :delegate` / `:target "<workflow-name>"` grammar.
- Threading the same Munera task identifier (`:input`) into every stage, since
  all five target workflows consume `{:from :workflow-input :path [:input]}`.

Out of scope:

- Any change to the five existing target workflows.
- Conditional gating / early-exit between stages (see Open question below).
- New deterministic operations, prompt files, or grammar changes.
- Changes to how workflows are discovered, loaded, or reloaded.

## Overall functionality

- Input: a Munera task identifier supplied as the workflow input `:input`.
- Behaviour: invoke the five workflows in the listed order, each delegated as a
  sub-workflow step, passing the task identifier as that sub-workflow's input.
- Output: the orchestrator surfaces the outcome of the final stage
  (`review-task-implementation`); intermediate stage summaries may be forwarded
  as supporting context to later stages.

## Constraints

- Use only the existing converged workflow grammar
  (`:type :invoke | :session | :delegate`); do not invent new step shapes.
- Each delegate target is referenced by its static workflow name.
- Keep one clear purpose per workflow file.
- The workflow must parse, compile, and register on reload.

## Acceptance criteria

- A `task-lifecycle` workflow exists under `.psi/workflows/` and appears in the
  workflow definition registry after reload.
- It contains exactly five sequential delegate steps targeting, in order,
  `review-task-design`, `create-task-plan`, `review-task-plan`,
  `implement-task`, `review-task-implementation`.
- Each step passes the Munera task identifier as the delegated workflow's input.
- The workflow parses and compiles cleanly (delegate targets resolve to
  workflow references) and is verified by the narrowest relevant
  parser/compiler/definition surface.
- Documentation listing project workflows (e.g. `doc/workflows.md`) reflects the
  new workflow if such a list is maintained; CHANGELOG updated if the new
  workflow is considered user-visible.

## Open questions

- **Stage gating:** Should the chain be plain-sequential (every stage runs
  unconditionally) or should a stage's outcome be able to short-circuit the
  chain (e.g. stop before implementation if design review cannot reach a clean
  state)? Current intent leans plain-sequential for simplicity; conditional
  gating would require `:judge`/`:on` routing and expands scope.
- **Context threading:** Whether later stages receive only the task identifier
  (clean, deterministic — each sub-workflow re-inspects the task files) or also
  receive prior-stage summaries as supporting context. Leaning toward
  input-only with prior summaries as optional non-authoritative context.
