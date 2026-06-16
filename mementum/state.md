# Mementum State

Working-memory bootloader for psi. Read first each session for fast orientation.
This file describes the **current state of the project in terms of features and
structure** — it is not a task work log. Per-task history lives in git, Munera
task artifacts, and mementum memories/knowledge.

Bootstrapped on 2026-04-02.

## What psi is

A deterministic, replayable, UI-agnostic AI coding-agent harness in JVM Clojure.
Architecture follows a Viable System Model (see `AGENTS.md` → Architecture):

- Single canonical state atom; all reads go through resolvers (Pathom/EQL), all
  changes and side effects go through mutations dispatched on an interceptor
  chain that produces effects-as-data run at the boundary.
- Event log + replay; statecharts enforce valid transitions.
- Extensions are isolated mini viable systems (manifest/permissions/subscriptions).
- Adapters: TUI (terminal) and RPC (stdio/EDN, used by emacs-ui). `app-runtime`
  is shared by the adapters; `rpc` is transport-only.

## Capabilities (current)

- Agent sessions with turn execution, provider-boundary retry/backoff
  observability, speed/effort controls, mid-conversation system messages.
- Deterministic workflows in `.psi/workflows/` invoked via `/delegate` and the
  `delegate` tool: `task-lifecycle`, `review-task-design`/`-plan`/
  `-implementation`, `create-task-plan`, `implement-task`, `reduce-incidental-complexity`,
  `reduce-architectural-complexity`, `extract-task-knowledge`, and the `gh-*`
  GitHub workflows. `review-task-design` is the multi-prompt exemplar: one
  shared `design-review` batch (architecture/ambiguity/inconsistency) followed
  by one batched design follow-up.
- Workflow routing uses generic deterministic operations (`workflow/pass-status-routing`,
  `workflow/constant-routing`, `workflow/exact-marker-routing`,
  `workflow/munera-open-task-path-routing`); workflow-specific labels/topology
  live in the authored workflow definitions, not in runtime code. Judged routing
  directives may declare `:on-max-iterations` (alongside `:max-iterations`) to
  route loop exhaustion to an author target instead of hard-failing; the
  runtime-governing site is `workflow-judge/evaluate-routing`. `review-task-design`/
  `-plan` use it to hand non-converging reviews to a not-converged summary, and
  `task-lifecycle` gates design/plan stages on the review `PASS_STATUS`.
- Workflow cancellation/removal stop signals and ordinary-work entry locks are
  shared in `components/workflow-coordination`; workflow-runtime,
  agent-session, and deterministic-operation-runtime reuse those primitives.
- Registered deterministic operations are directly invokable via `/operations`,
  `/operation`, and the psi-tool `operation` action.
- Built-in slash-command surface is single-sourced in the backend and projected
  to both TUI and Emacs autocomplete.
- Gordian (`bb gordian`) provides architecture/coupling/complexity analysis used
  by the simplification workflows.
- Project nREPL management (`/project-repl`, psi-tool `project-repl`).
- **dev-http** dev-time extension: localhost HTTP side channel (`/dev-http`,
  `dev-present` tool) presenting markdown/table/vega/mermaid/file/hiccup/choices
  in a browser; choices post back as a mid-conversation user message via the
  `psi.extension/submit-synthetic-prompt` core mutation. See `doc/dev-http.md`.

## Protocols

- **mementum** — git-native memory. `mementum/memories/`, `mementum/knowledge/`,
  and this `state.md` working memory. Memories/knowledge are durable; state.md
  is the orientation bootloader.
- **munera** — git-native task protocol. `munera/open/` and `munera/closed/`
  task dirs (`design.md`, `plan.md`, `steps.md`, `implementation.md`);
  `munera/plan.md` curates active-task order.

## Orientation

- Active task work: read `munera/plan.md`, then the relevant `munera/open/NNN-*`.
- Architecture/principles: `AGENTS.md`, `META.md`, `doc/architecture.md`.
- User docs: `README.md`, `doc/`.
- Deeper recall: `git log`/`git grep` over `mementum/` and task artifacts.

## Build / test

- Tests: Scry-first. `bb clojure:test:unit` (also `:extensions`, `:integration`);
  focused `bb clojure:test:scry --namespace <ns>`.
- Lint/format: `clj-kondo --lint <paths>`; `clj-paren-repair <file>` after edits.
- Babashka tasks: `bb tasks`.
