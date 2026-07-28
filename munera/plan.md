# Munera plan

## Open tasks (suggested execution order)

Not yet started:

- `munera/open/247-add-claude-opus-5-0-model/` — add the Anthropic Claude Opus 5.0 model to the catalog (`anthropic_catalog.clj` entry + `anthropic-json-schema-native-model-keys`). Design-only; pricing/limits are Opus 4.8 placeholders pending official Anthropic values.
- `munera/open/202-document-at-bounds-in-scheduler-doc/` — (from master) document the resolved-millisecond-delay absolute `:at` bounds in `doc/scheduler.md`. NNN collides with closed `202-reusable-review-follow-up-step` (left as-is per convention).
- `munera/open/206-emacs-buffer-local-widget-mutation-timers/` — move Emacs widget-projection mutation watchdog timers from a module-global hash into buffer-local `psi-emacs-state` and cancel them in `psi-emacs--teardown-buffer`, fixing orphaned/non-deterministic/cross-buffer timers when a psi buffer is killed mid-mutation. Design-only.
- `munera/open/203-task-lifecycle-chained-workflow/` — renumbered from 198 (NNN collision with closed `198-fix-tool-metrics-empty-tools-map`); new orchestration workflow chaining review-task-design → create-task-plan → review-task-plan → implement-task → review-task-implementation. Design-only; open questions on stage gating and context threading.
- `munera/open/197-ui-action-invocation/` — renumbered from 191 (NNN collision); side-effecting UI action invocation deferred from closed 190/194 UI work.
- `munera/open/244-migrate-footer-query-in-with-redefs-to-resolver-seam/` — migrate the `session/query-in` `with-redefs` (logic-boundary) in `rpc-prompt-footer-updated-tolerates-keyword-sentinel-values-test` onto the Pathom resolver/query seam; tracks the standing `¬mock/¬stub` violation task 243 excluded from its frozen scope. Design-only.
- `munera/open/021-emacs-session-tree-buffer-with-magit-sections/`

## Conventions

- `munera/open/` is the canonical source of truth for active work; this file curates order only.
- Completed or abandoned tasks live under `munera/closed/` (no completed-vs-abandoned distinction; reason recorded inside each task).
- Historical per-task completion notes live in git history; they are intentionally no longer duplicated here.
- NNN collisions across concurrent branches: rename, never merge. Closed-task historical collisions are left as-is.
