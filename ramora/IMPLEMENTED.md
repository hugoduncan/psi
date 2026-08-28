# Current Capabilities

- Agent sessions with turn execution, provider-boundary retry/backoff
  observability, and operator-configurable retry policy: retryable provider
  failures default to a 10-minute retry window instead of a fixed attempt cap.
  The window opens at the first retry decision, excluding initial-request
  execution, and is checked only between attempts; an in-flight provider request
  may finish after the deadline and still succeed. Also includes speed/effort
  controls and mid-conversation system messages.
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
  non-converged handback). `implement-task` has three authored pass outcomes:
  bounded `MORE_WORK_REMAINS`, `IMPLEMENTATION_COMPLETE`, and
  `IMPLEMENTATION_BLOCKED`. Blocked routing deterministically validates exactly
  one fresh complete blocker record from one artifact snapshot and exports a
  branch-specific terminal status plus the validated blocker/action. Gates in
  task lifecycle, worktree/GitHub wrappers, and complexity workflows prevent a
  blocked implementation from reaching downstream review, validation,
  extraction, or publication work.
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
