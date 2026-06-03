# Munera plan

Rebuilt 2026-05-31 from the authoritative `munera/open/` directory after an
open-task reconciliation audit (completed-task closure, NNN-collision renumber).

## Open tasks (suggested execution order)

Not yet started:

- `munera/open/206-emacs-buffer-local-widget-mutation-timers/` — move Emacs widget-projection mutation watchdog timers from a module-global hash into buffer-local `psi-emacs-state` and cancel them in `psi-emacs--teardown-buffer`, fixing orphaned/non-deterministic/cross-buffer timers when a psi buffer is killed mid-mutation. Design-only.
- `munera/open/203-task-lifecycle-chained-workflow/` — renumbered from 198 (NNN collision with closed `198-fix-tool-metrics-empty-tools-map`); new orchestration workflow chaining review-task-design → create-task-plan → review-task-plan → implement-task → review-task-implementation. Design-only; open questions on stage gating and context threading.
- `munera/open/197-ui-action-invocation/` — renumbered from 191 (NNN collision); side-effecting UI action invocation deferred from closed 190/194 UI work.
- `munera/open/021-emacs-session-tree-buffer-with-magit-sections/`

## Recently closed

- `205-invoke-deterministic-operation` — made registered deterministic operations directly invokable outside a workflow run via two new surfaces sharing one mechanism: the `/operations` (list) and `/operation <id> {edn-args}` (invoke) slash commands, and a psi-tool `operation` action with `op: list|invoke`. Both delegate to a single shared invocation/listing helper (`deterministic-operation-action` / `commands.operation`) that builds the invocation map from session ctx (`:args`/`:ctx`/`:session-id`, optional `:parent-session-id`, nil `:workflow-run-id`/`:step-id`) and routes through the existing runtime boundary (`invoke-operation-in` via `runtime/invoke-operation`) — no new execution/validation/permission semantics. Listing is sorted-by-id (registry runtime-handle read, not a resolver); invocation renders all top-level result keys (`pr-str`'d, per-key 2000-char truncation). Tests + docs (`doc/operations.md`) + changelog. Registered `/operations`/`/operation` in the single-source `builtin-command-specs` table during rebase onto the master single-source change (2026-06-02).
- `208-fix-tui-init-state-nil-query-fn-empty-builtin-command-specs` — fixed the broken TUI slash-autocomplete test setup: the test helper `init-state` built state via `(app/make-init nil …)` with a nil `query-fn`, so `build-init` skipped introspection and `:builtin-command-specs` stayed empty (built-in slash autocomplete rendered zero candidates → tests passed vacuously / didn't cover the task-205 single-sourced surface). Rewired `init-state` onto a stub `query-fn` seam returning `{:psi.agent-session/builtin-command-specs …}` (default `sample-builtin-command-specs`, per-case via opt), dropped the post-hoc `assoc`, and strengthened the autocomplete tests to assert `/help`/`/status`/`/quit` present (default) and absent (empty surface), with symmetric per-name failure messages. Test-only (single file `app_input_selector_test.clj`); no production change, no changelog. Focused test 15/42/0, `--focus unit` RC=0, clj-kondo clean (2026-06-02).
- `201-verify-scheduler-execution` — verification-only scheduler audit complete: all 7 scope areas verified-correct, no scheduler-behaviour defects found, one doc-gap remediation raised as task 202, scheduler suite and full `bb test` green after deterministic test-flake fixes.
- `207-workflow-session-defaults-inheritance-snapshot` — workflow runs now snapshot the inheritable default session details from their parent at invoke time (top-level steps from the invoking session; nested workflows from their parent run) into `:inherited-defaults`, so later parent model switches or user/project default-model changes do not affect an already-running delegated workflow. `resolve-step-session-config` consumes the snapshot (zero live-parent reads on the snapshot path) via a single `inherited` map; `:thinking-level` precedence unified with `:model` (inherited default ranks above base-meta); child-session assembly gates `inherited-default` on `:inherited-snapshot?` so the workflow judge keeps live-parent inheritance. Tests + design + CHANGELOG (2026-06-02).
- `205-single-source-builtin-slash-commands` — made the backend the single authoritative source of the built-in slash-command surface via one ordered keyed spec table (`commands.builtin-specs/builtin-command-specs`); `exact-command-handlers`, `prefixed-command-prefixes`, `builtin-command-names`, `format-help`'s built-in lines, and the new `builtin-commands-resolver` (`:psi.agent-session/builtin-command-specs` / `-names`) are all derived projections, so name drift is structurally unrepresentable (Option B, `unreachable > forbidden`). TUI + Emacs now build slash autocomplete from the resolver (deleted `shared/builtin-slash-commands`; trimmed the Emacs `defcustom` to `/skill:`, backend-wins), so previously-missing `/reload-models`/`/reload-prompts`/`/reload-extension-installs`/`/speed`/`/effort`/`/project-repl` now appear. Tests (backend projections/help/resolver + TUI + Emacs capf), changelog, `doc/architecture.md` (2026-06-01).
- `204-prompts-reload-command-and-mutation` — added `/reload-prompts` command + `psi.extension/reload-prompts` mutation (psi-tool visible) + pure `:session/reload-prompts` dispatch handler that re-discovers prompt templates from the session worktree and replaces `:prompt-templates` (no effects); `reload-prompts-in!` settings fn + `core.clj` re-export. Tests + docs + changelog (2026-06-01).
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
