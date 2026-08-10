# Current Capabilities

- Agent sessions with turn execution, provider-boundary retry/backoff
  observability, speed/effort controls, mid-conversation system messages.
- Custom providers from user-global or project-local `models.edn`, including
  the documented DeepSeek `deepseek-v4-flash` setup over its
  Anthropic-compatible endpoint with adaptive-thinking support.
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
  `task-lifecycle` gates design/plan stages on the review `PASS_STATUS`, and a
  pre-plan `check-scope-question-status` gate (`workflow/scope-question-gate-routing`)
  halts the lifecycle when `design-steps.md` has unchecked `SCOPE_QUESTION:` items
  (content scan, independent of convergence; scope handback wins over the
  non-converged handback).
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
