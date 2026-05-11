# 138 — Plan

## Approach

Vertical-slice delivery in six phases, each independently verifiable.

### Phase 1 — Shared slug helper

Extract `derive-slug` from `psi.github.find-issue` into a new `psi.github.slug` ns.
Rewire `find-issue` to use it.  Run existing `find-issue` tests to confirm no regression.
This unblocks `find-pr` without touching any workflow files.

### Phase 2 — `github/find-pr` operation

Implement `psi.github.find-pr` parallel to `find-issue`:
- URL narrowing uses `#"/pull/(\d+)"` (not `/issues/`).
- Slug derived from `headRefName` via `psi.github.slug/derive-slug`.
- Output keys: `:pr-number :pr-title :pr-url :pr-branch :base-branch :worktree-description`.
- Unit tests cover: ok / no-match / shell-error / narrow-by-number / narrow-by-url (using `/pull/NNN`) / narrow-by-title.
Register in `psi.github.extension/init`.

### Phase 3 — `github/add-label` and `github/remove-label` operations

Implement `psi.github.label-ops` with shared `label-csv` helper, `:target` dispatch,
and both handlers.  Unit tests cover add/remove for issue and PR targets, plus shell-error.
Register both in `psi.github.extension/init`.
Update `extension-test` to assert all four operation ids explicitly:
`"github/find-issue"`, `"github/find-pr"`, `"github/add-label"`, `"github/remove-label"`.

### Phase 4 — Lint and focused verification

`clj-kondo --lint extensions/github/src extensions/github/test` → zero errors/warnings.
All github extension tests green.

### Phase 5 — Workflow migrations

Apply in this order (discovery-first, then label-only):

1. `gh-bug-discover-and-read` — leading `find-issue` step; strip discovery from AI prompt.
2. `gh-bug-triage` — leading `find-issue`; unconditional `remove-label triage`; conditional add stays AI-driven.
3. `gh-issue-ingest` — leading `find-issue`; `remove-label triage`; `add-label waiting`.
4. `gh-issue-implement` — `find-pr` with `:input {:from :workflow-input :path [:input]}`; `remove-label implement`; `add-label review`.
5. `gh-pr-fix-checks` — `find-pr` with `:input {:from :workflow-input :path [:input]}`; no label changes.
6. `gh-bug-post-repro` — unconditional `remove-label triage`; conditional add stays AI-driven.
7. `gh-bug-request-more-info` — `remove-label triage`; `add-label waiting`.
8. `gh-issue-refine` — `remove-label refine` (issue); `add-label waiting` (PR).
9. `gh-bug-fix-and-pr` — `remove-label fix`.

### Phase 6 — Final verification and commit

All extension tests green; `clj-kondo` clean; smoke-read each migrated workflow.
Commit: `⚒ 138: github extension label ops and workflow adoption`.
Update `munera/plan.md`.

## Key decisions

- **Slug**: extract to `psi.github.slug` (not inline copy) — see design.md §B.
- **find-pr URL regex**: `#"/pull/(\d+)"` — see design.md §C.
- **find-pr :input wiring**: `{:from :workflow-input :path [:input]}` — see design.md §D.
- **gh-bug-triage-modular**: already migrated for discovery; not touched here — see design.md §E.
- **Conditional label add**: stays AI-driven in `gh-bug-triage` and `gh-bug-post-repro` — see design.md §Out of scope.

## Risks

- Workflow IR does not support conditional `:invoke` steps → conditional label add must remain AI-driven; mitigated by explicit scope exclusion.
- `gh-bug-triage-modular` diverges from `gh-bug-triage` post-migration → acceptable; modular variant is the forward path; monolithic is migrated for label-mutation only.
