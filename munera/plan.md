# Munera plan

Rebuilt 2026-05-31 from the authoritative `munera/open/` directory after an
open-task reconciliation audit (completed-task closure, NNN-collision renumber).

## Open tasks (suggested execution order)

Not yet started:

- `munera/open/203-task-lifecycle-chained-workflow/` — renumbered from 198 (NNN collision with closed `198-fix-tool-metrics-empty-tools-map`); new orchestration workflow chaining review-task-design → create-task-plan → review-task-plan → implement-task → review-task-implementation. Design-only; open questions on stage gating and context threading.
- `munera/open/197-ui-action-invocation/` — renumbered from 191 (NNN collision); side-effecting UI action invocation deferred from closed 190/194 UI work.
- `munera/open/021-emacs-session-tree-buffer-with-magit-sections/`

## Recently closed

- `202-reusable-review-follow-up-step` — unified the review-workflow follow-ups onto two shared profile prompts (`review-follow-up-design.md`, `review-follow-up-steps.md`); removed the four per-aspect follow-up files and the inline `review-step` template; tests + docs + changelog updated (2026-06-01). Renumbered from 199 (NNN collision with master).
- `201-task-design-architectural-fit-review` — added `review-task-architecture` skill + `review-task-design-architecture-review.md` prompt; prepended an `architecture-review`/`architecture-follow-up` aspect pair (runs first) to `review-task-design.edn`, reusing the shared `review-follow-up-design.md` profile; final-summary sources the architecture yield + prose; tests (8 steps) + `doc/workflows.md` updated (2026-06-01). Renumbered from 198 (NNN collision with master).
- `108-project-nrepl-testing-without-mocks` — de-mocked all six in-scope `components/project-nrepl/test/` files (zero `with-redefs`); introduced three thin production seams (`real-nrepl-connector`, `real-process-launcher`, optional `:runtime-handle` seed on attach/start). PR #147.
- `199-metrics-turn-finished-handler-logging-idiom` — replaced raw `println` debug logging in metrics `make-turn-finished-handler` with timbre (closed upstream on master).
- `200-remove-dead-wrap-tool-executor-and-dedupe-result-filter` — removed dead `wrap-tool-executor`; modifiable-key contract (`:content`/`:details`/`:is-error`) now expressed once in the `dispatch-tool-result-in` filter; migrated the non-map-return guard to a direct test.
- `198-fix-tool-metrics-empty-tools-map` — `:tools {}` always empty in metrics.edn; fixed by bridging `:tool-start`/`:tool-result` lifecycle events → extension `dispatch-in` in `emit-tool-lifecycle!`.

## Audit outcome (2026-05-31)

Closed as complete (moved to `munera/closed/`):

- `164-registry-semantics-unification-audit` — registry-unification audit done; arc complete through 177.
- `187-md-workflow-input-expansion`
- `188-openai-codex-native-structured-output` — complete (PR #132); one deferred conditional-future Codex non-streaming test remains by design.
- `190-conditional-review-follow-ups-for-design-and-plan-workflows`
- `191-fix-judge-structured-output-non-object-schema`
- `193-improve-project-workflows`
- `173`, `175`, `186`, `189` — closed earlier in the session.

Closed as stale/abandoned (predate registry/bootstrap/structured-output arcs):

- `001-post-wave-b-gordian-follow-on`
- `002-compatibility-scaffold-removal`
- `003-prompt-lifecycle-architectural-convergence`
- `005-canonical-dispatch-pipeline-trace-observability`
- `006-agent-tool-skill-prelude-follow-on`

Renumbered (NNN collision, slug/content unchanged):

- `154-fix-workflow-max-iterations-error-surfacing` → `195` (since closed — core fix done, edge-case tests deferred)
- `194-deterministic-review-step-routing` → `196` (since closed — work landed under 189/190; stale unchecked boxes)
- `191-ui-action-invocation` → `197` (still open)

## Conventions

- `munera/open/` is the canonical source of truth for active work; this file curates order only.
- Completed or abandoned tasks live under `munera/closed/` (no completed-vs-abandoned distinction; reason recorded inside each task).
- Historical per-task completion notes live in git history and `mementum/state.md`; they are intentionally no longer duplicated here.
- NNN collisions across concurrent branches: rename, never merge. Closed-task historical collisions are left as-is.
