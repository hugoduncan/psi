# Steps

## Phase 1 — Extension: shared slug helper

- [ ] Extract `derive-slug` from `psi.github.find-issue` into `psi.github.slug`
- [ ] Update `psi.github.find-issue` to require and use `psi.github.slug/derive-slug`
- [ ] Verify existing `find-issue` tests still pass

## Phase 2 — Extension: `github/find-pr`

- [ ] Create `extensions/github/src/psi/github/find_pr.clj`
  - [ ] Same narrowing logic as `find-issue` (number, URL, title substring)
  - [ ] Shell: `gh pr list --state <state> --label ... --json number,title,url,state,labels,headRefName,baseRefName`
  - [ ] Slug from `headRefName` via `psi.github.slug/derive-slug`
  - [ ] Output data keys: `:pr-number :pr-title :pr-url :pr-branch :base-branch :worktree-description`
  - [ ] Summary markdown: `## PR Selection` + `## Handoff Data` lines
- [ ] Create `extensions/github/test/psi/github/find_pr_test.clj`
  - [ ] Stub `github-shell-fn`; cover ok / no-match / shell-error / narrow-by-number / narrow-by-url / narrow-by-title
- [ ] Register `github/find-pr` in `psi.github.extension/init`

## Phase 3 — Extension: `github/add-label` and `github/remove-label`

- [ ] Create `extensions/github/src/psi/github/label_ops.clj`
  - [ ] `label-csv` helper: `(str/join "," labels)`
  - [ ] `add-label` handler: dispatches on `:target`; `gh issue edit <N> --add-label <csv>` or `gh pr edit`
  - [ ] `remove-label` handler: dispatches on `:target`; `gh issue edit <N> --remove-label <csv>` or `gh pr edit`
  - [ ] Both return `{:status :ok/:error :data {...} :summary "..."}`
- [ ] Create `extensions/github/test/psi/github/label_ops_test.clj`
  - [ ] Stub shell-fn; cover add/remove for issue, add/remove for pr, shell-error cases
- [ ] Register `github/add-label` and `github/remove-label` in `psi.github.extension/init`
- [ ] Update `extension-test` to assert four registrations

## Phase 4 — Lint and focused verification

- [ ] `clj-kondo --lint extensions/github/src extensions/github/test` → zero errors/warnings
- [ ] Run github extension tests → all green

## Phase 5 — Workflow migrations

### Discovery migrations

- [ ] `gh-bug-discover-and-read.md`
  - [ ] Add leading `:invoke github/find-issue` step
  - [ ] AI session step reads issue only; strip `gh issue list` instruction from prompt
  - [ ] Wire discover `:data` into AI session context

- [ ] `gh-bug-triage.md`
  - [ ] Add leading `:invoke github/find-issue` discover step
  - [ ] Wire discover output into existing AI triage session
  - [ ] Add trailing `:invoke github/remove-label` (remove `triage`) step
  - [ ] Add trailing `:invoke github/add-label` (add `waiting` or `fix`) step — conditional on AI output
  - [ ] Strip `gh issue list` and `gh issue edit` label instructions from AI prompt

- [ ] `gh-issue-ingest.md`
  - [ ] Add leading `:invoke github/find-issue` discover step
  - [ ] Wire discover output into AI triage session
  - [ ] Add trailing `:invoke github/remove-label` (remove `triage`)
  - [ ] Add trailing `:invoke github/add-label` (add `waiting`)
  - [ ] Strip `gh issue list` and label instructions from AI prompt

- [ ] `gh-issue-implement.md`
  - [ ] Replace inline `gh pr list` AI discover session → `:invoke github/find-pr` step
  - [ ] Wire PR data into prep and implement sessions
  - [ ] Add trailing `:invoke github/remove-label` (remove `implement`)
  - [ ] Add trailing `:invoke github/add-label` (add `review`)
  - [ ] Strip label instructions from AI push/label session prompt

- [ ] `gh-pr-fix-checks.md`
  - [ ] Replace inline `gh pr list` AI discover session → `:invoke github/find-pr` step
  - [ ] Wire PR data into remaining sessions

### Label-mutation-only migrations

- [ ] `gh-bug-post-repro.md`
  - [ ] Add trailing `:invoke github/remove-label` (remove `triage`)
  - [ ] Add trailing `:invoke github/add-label` (add `waiting` or `fix`, determined by AI classify output)
  - [ ] Strip `gh issue edit` label instructions from AI prompt

- [ ] `gh-bug-request-more-info.md`
  - [ ] Add trailing `:invoke github/remove-label` (remove `triage`)
  - [ ] Add trailing `:invoke github/add-label` (add `waiting`)
  - [ ] Strip `gh issue edit` label instructions from AI prompt

- [ ] `gh-issue-refine.md`
  - [ ] Add trailing `:invoke github/remove-label` (remove `refine` from issue)
  - [ ] Add trailing `:invoke github/add-label` (add `waiting` to PR, target `pr`)
  - [ ] Strip label instructions from AI publish prompt

- [ ] `gh-bug-fix-and-pr.md`
  - [ ] Add trailing `:invoke github/remove-label` (remove `fix`)
  - [ ] Strip label instruction from AI implement prompt

## Phase 6 — Final verification

- [ ] All github extension tests green
- [ ] `clj-kondo` zero errors/warnings
- [ ] Smoke-read each migrated workflow for structural coherence
- [ ] Commit: `⚒ 138: github extension label ops and workflow adoption`
- [ ] Update `munera/plan.md`
