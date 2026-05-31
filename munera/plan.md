# Munera plan

Rebuilt 2026-05-31 from the authoritative `munera/open/` directory after an
open-task reconciliation audit (completed-task closure, NNN-collision renumber).

## Open tasks (suggested execution order)

In progress (partial `steps.md`):

- `munera/open/195-fix-workflow-max-iterations-error-surfacing/` — 15/4 checked; renumbered from 154 (NNN collision).
- `munera/open/196-deterministic-review-step-routing/` — 42/24 checked; renumbered from 194 (NNN collision).

Not yet started:

- `munera/open/197-ui-action-invocation/` — renumbered from 191 (NNN collision); side-effecting UI action invocation deferred from closed 190/194 UI work.
- `munera/open/021-emacs-session-tree-buffer-with-magit-sections/`
- `munera/open/108-project-nrepl-testing-without-mocks/`
- `munera/open/157-jar-owned-deps-release-startup/`

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

Renumbered (NNN collision, slug/content unchanged, still open):

- `154-fix-workflow-max-iterations-error-surfacing` → `195`
- `194-deterministic-review-step-routing` → `196`
- `191-ui-action-invocation` → `197`

## Conventions

- `munera/open/` is the canonical source of truth for active work; this file curates order only.
- Completed or abandoned tasks live under `munera/closed/` (no completed-vs-abandoned distinction; reason recorded inside each task).
- Historical per-task completion notes live in git history and `mementum/state.md`; they are intentionally no longer duplicated here.
- NNN collisions across concurrent branches: rename, never merge. Closed-task historical collisions are left as-is.
