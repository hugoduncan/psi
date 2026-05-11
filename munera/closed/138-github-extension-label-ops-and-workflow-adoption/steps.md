# Steps

## Phase 1 — Extension: shared slug helper

- [x] Extract `derive-slug` from `psi.github.find-issue` into `psi.github.slug`
- [x] Update `psi.github.find-issue` to require and use `psi.github.slug/derive-slug`
- [x] Verify existing `find-issue` tests still pass

## Phase 2 — Extension: `github/find-pr`

- [x] Create `extensions/github/src/psi/github/find_pr.clj`
  - [x] Same narrowing logic as `find-issue` (number, URL, title substring)
  - [x] Shell: `gh pr list --state <state> --label ... --json number,title,url,state,labels,headRefName,baseRefName`
  - [x] Slug from `headRefName` via `psi.github.slug/derive-slug`
  - [x] Output data keys: `:pr-number :pr-title :pr-url :pr-branch :base-branch :worktree-description`
  - [x] Summary markdown: `## PR Selection` + `## Handoff Data` lines
- [x] Create `extensions/github/test/psi/github/find_pr_test.clj`
  - [x] Stub `github-shell-fn`; cover ok / no-match / shell-error / narrow-by-number / narrow-by-url / narrow-by-title
  - [x] narrow-by-url case must use a `/pull/NNN` URL (not `/issues/NNN`); regex is `#"/pull/(\d+)"`
- [x] Register `github/find-pr` in `psi.github.extension/init`

## Phase 3 — Extension: `github/add-label` and `github/remove-label`

- [x] Create `extensions/github/src/psi/github/label_ops.clj`
  - [x] `label-csv` helper: `(str/join "," labels)`
  - [x] `add-label` handler: dispatches on `:target`; `gh issue edit <N> --add-label <csv>` or `gh pr edit`
  - [x] `remove-label` handler: dispatches on `:target`; `gh issue edit <N> --remove-label <csv>` or `gh pr edit`
  - [x] Both return `{:status :ok/:error :data {...} :summary "..."}`
- [x] Create `extensions/github/test/psi/github/label_ops_test.clj`
  - [x] Stub shell-fn; cover add/remove for issue, add/remove for pr, shell-error cases
- [x] Register `github/add-label` and `github/remove-label` in `psi.github.extension/init`
- [x] Update `extension-test` to assert all four operation ids explicitly: `"github/find-issue"`, `"github/find-pr"`, `"github/add-label"`, `"github/remove-label"` — not just `(= 4 (count @calls*))`

## Phase 4 — Lint and focused verification

- [x] `clj-kondo --lint extensions/github/src extensions/github/test` → zero errors/warnings
- [x] Run github extension tests → all green (36 tests, 117 assertions)

## Phase 5 — Workflow migrations

### Discovery migrations

- [x] `gh-bug-discover-and-read.md`
  - [x] Add leading `:invoke github/find-issue` step; name it `"discover"`
  - [x] Rename existing `"discover"` session step to `"read"`
  - [x] Strip selection rules and step 1 from prompt; wire discover summary as contribution
  - [x] Add `:outputs {:summary {:source :invoke/summary}}` to the new `"discover"` `:invoke` step

- [x] `gh-bug-triage.md`
  - [x] Add leading `:invoke github/find-issue` discover step with `:outputs {:summary :data}`
  - [x] Wire discover output into existing AI triage session
  - [x] Add unconditional trailing `:invoke github/remove-label` (remove `triage`)
  - [x] Strip primary selection rule, input expectations, and step 1 from AI prompt

- [x] `gh-issue-ingest.md`
  - [x] Add leading `:invoke github/find-issue` discover step
  - [x] Wire discover output into AI triage session
  - [x] Add trailing `:invoke github/remove-label` (remove `triage`)
  - [x] Add trailing `:invoke github/add-label` (add `waiting`)
  - [x] Strip discovery sections and label-change instructions from AI prompt

- [x] `gh-issue-implement.md`
  - [x] Replace inline `gh pr list` AI `search` delegate → `:invoke github/find-pr` (keep step name `search`)
  - [x] Add `:outputs {:summary :data}` to search step
  - [x] Wire `:input` as `{:from :workflow-input :path [:input]}`
  - [x] Add trailing `:invoke github/remove-label` (remove `implement`)
  - [x] Add trailing `:invoke github/add-label` (add `review`)
  - [x] Strip label instructions from push delegate prompt

- [x] `gh-pr-fix-checks.md`
  - [x] Replace inline `gh pr list` AI `select` delegate → `:invoke github/find-pr` (keep step name `select`)
  - [x] Wire `:input` as `{:from :workflow-input :path [:input]}`
  - [x] No label-ops steps added (PR discovery only)

### Label-mutation-only migrations

- [x] `gh-bug-post-repro.md`
  - [x] Change classify session input var to wire from `{:from :workflow-input :path [:report]}`
  - [x] Add unconditional trailing `:invoke github/remove-label` (remove `triage`)
  - [x] Strip unconditional label instructions from AI prompt; conditional add stays AI-driven
- [x] `gh-bug-triage-modular.md` (§P prerequisite)
  - [x] Add `:data {:source :invoke/data}` to the `discover` step `:outputs`
  - [x] Change `post-repro` delegate step `prompt-string` to structured map type

- [x] `gh-bug-request-more-info.md`
  - [x] Add trailing `:invoke github/remove-label` (remove `triage`)
  - [x] Add trailing `:invoke github/add-label` (add `waiting`)
  - [x] Strip `gh issue edit` label instructions from AI prompt

- [x] `gh-issue-refine.md`
  - [x] Add `:data {:source :invoke/data}` to existing `discover` step `:outputs`
  - [x] Update `publish` step: add `pr_number:` to handoff, add `:outputs {:data {:source :delegate/handoff}}`
  - [x] Add trailing `:invoke github/remove-label` (remove `refine` from issue)
  - [x] Add trailing `:invoke github/add-label` (add `waiting` to PR)
  - [x] Strip label instructions from publish prompt

- [x] `gh-bug-fix-and-pr.md`
  - [x] Add `:data {:source :invoke/data}` to existing `discover` step `:outputs`
  - [x] Add trailing `:invoke github/remove-label` (remove `fix`)
  - [x] Strip label instruction from AI run prompt

## Phase 6 — Final verification

- [x] All github extension tests green (36 tests, 117 assertions, 0 failures)
- [x] `clj-kondo` zero errors/warnings
- [x] Smoke-read each migrated workflow — all parse cleanly, wiring verified
- [x] Commit: `⚒ 138: github extension label ops and workflow adoption`
- [x] Update `munera/plan.md`

## Code-shaping pass 1 follow-up

- [x] Extract shared `narrow-candidates` + `extract-url-number` from `find-issue` and `find-pr` into `psi.github.narrowing`
  - Both files have structurally identical narrowing logic; only the URL regex differs (`/issues/` vs `/pull/`)
  - Parameterise `extract-url-number` with the regex; move both into a new `psi.github.narrowing` ns
  - Rewire `find-issue` and `find-pr` to require and use `psi.github.narrowing`
  - Added `psi.github.narrowing-test` with 10 focused narrowing unit tests
- [x] Extract shared test helpers (`stub-shell`, `error-shell`, `capturing-shell`, `invoke`) into `psi.github.test-support`
  - `find-issue-test` and `find-pr-test` define identical stubs; `label-ops-test` has its own parallel set
  - Moved `stub-shell`, `error-shell`, `capturing-shell` (JSON variant), `stub-shell-ok`, `stub-shell-error`, `capturing-shell-ok` to `psi.github.test-support`
  - Each test ns now requires `[psi.github.test-support :as ts]` and uses `ts/` prefixed stubs; each keeps its own `invoke` wrapper
- [x] Remove `gh-edit-cmd` indirection in `label_ops.clj` — inline `target` directly
  - `gh-edit-cmd` maps `"pr"→"pr"` and `"issue"→"issue"` (identity); the default branch is unreachable
  - Deleted `gh-edit-cmd`; replaced `(gh-edit-cmd target)` with `target` in both handlers
  - Lint clean; 44 tests, 137 assertions, 0 failures

## Review pass 1 follow-up

- [x] Fix `gh-bug-triage-modular` broken `:map` type prompt-string in `post-repro` delegate step
  - Chose approach (a): implement `:map` type in `delegate-spec-schema` + `render-delegate-prompt-string`
  - Added `map-prompt-string-schema` to `ir.clj`; extended `delegate-prompt-string-schema` to `[:or :string template-contribution-schema map-prompt-string-schema]`
  - Updated `step-source-refs` in `ir.clj` to extract source refs from `:map` `:fields` values
  - Implemented `:map` branch in `render-delegate-prompt-string` in `source_resolution.clj`
  - Added `render-map-prompt-string-resolves-fields-to-map-test` in `source_resolution_test.clj`
  - Added `delegate-prompt-string-schema accepts :map type` assertions in `ir_test.clj`
  - 1677 unit tests, 11827 assertions, 0 failures; 131 extension tests, 509 assertions, 0 failures; lint clean

- [x] Fix `gh-issue-refine` `add-waiting-pr` key mismatch: `:path [:pr-number]` vs parsed `:pr_number`
  - Changed wiring to `:path [:pr_number]` in `gh-issue-refine.md` (`parse-markdown-handoff-data` converts `pr_number:` bullet to `:pr_number` keyword)
  - Verified: `parse-markdown-handoff-data` → `get-path*` → `github/add-label :number` is now non-nil

## Review pass 2 follow-up

- [x] Fix missing `"edit"` subcommand in `label_ops.clj`
  - `add-label`: `(shell-fn "gh" target "edit" (str number) "--add-label" csv)`
  - `remove-label`: `(shell-fn "gh" target "edit" (str number) "--remove-label" csv)`
  - Updated all 7 `label-ops-test` capturing assertions to include `"edit"` in expected command vectors
  - lint clean (0 errors, 0 warnings); 139 extension tests, 529 assertions, 0 failures
