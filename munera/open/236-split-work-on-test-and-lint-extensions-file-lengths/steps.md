# Steps

## Slice 1 — Baseline and inventory

- [x] Record the current `wc -l` result for `extensions/work-on/test/extensions/work_on_test.clj` and the planned target files.
- [x] Capture the ordered list of existing `deftest` names in `work_on_test.clj` for before/after comparison.
- [x] Run the focused work-on extension test namespace before the split and record the structured result or failure context in `implementation.md`.
- [x] Identify every top-level helper form in `work_on_test.clj` and assign it either to `work_on_test_support.clj` or to the one split test namespace that exclusively uses it.

## Slice 2 — Support namespace extraction

- [x] Create `extensions/work-on/test/extensions/work_on_test_support.clj` with namespace `extensions.work-on-test-support`.
- [x] Move shared helper constants/functions from `work_on_test.clj` into the support namespace without changing helper behavior.
- [x] Export helpers that are used by multiple split namespaces with non-private `def`/`defn`; keep single-namespace-only helpers local to their target test namespace.
  - `run-git!` and `delete-recursively!` are local to `work_on_command_test.clj` because only the remote-base-ref integration test uses them after the split.
- [x] Ensure `work_on_test_support.clj` contains no `deftest` forms.
- [x] Update requires in split test namespaces to alias the support namespace where shared helpers are needed.

## Slice 3 — Command test split

- [x] Create `extensions/work-on/test/extensions/work_on_command_test.clj` with namespace `extensions.work-on-command-test`.
- [x] Move `mechanical-slug-test` unchanged into `work_on_command_test.clj`.
- [x] Move `target-worktree-path-test` unchanged into `work_on_command_test.clj`.
- [x] Move `init-registers-work-commands-and-tool-test` unchanged into `work_on_command_test.clj`.
- [x] Move `session-switch-handler-returns-nil-test` unchanged into `work_on_command_test.clj`.
- [x] Move `work-on-command-happy-path-test` unchanged into `work_on_command_test.clj`.
- [x] Move `work-on-command-nested-linked-layout-test` unchanged into `work_on_command_test.clj`.
- [x] Move `work-on-command-reuses-existing-worktree-test` unchanged into `work_on_command_test.clj`.
- [x] Move `parse-work-on-command-args-test` unchanged into `work_on_command_test.clj`.
- [x] Move `work-on-command-usage-error-test` unchanged into `work_on_command_test.clj`.
- [x] Move `work-on-command-follows-active-session-after-new-test` unchanged into `work_on_command_test.clj`.
- [x] Move `work-on-command-with-remote-base-ref-integration-test` unchanged into `work_on_command_test.clj`.
- [x] Add only the namespace requires needed by the moved command tests and their helpers.

## Slice 4 — Tool test split

- [x] Create `extensions/work-on/test/extensions/work_on_tool_test.clj` with namespace `extensions.work-on-tool-test`.
- [x] Move `work-on-tool-happy-path-test` unchanged into `work_on_tool_test.clj`.
- [x] Move `work-on-tool-usage-error-test` unchanged into `work_on_tool_test.clj`.
- [x] Move `work-on-tool-reuses-existing-worktree-session-test` unchanged into `work_on_tool_test.clj`.
- [x] Move `work-on-tool-follows-active-session-after-new-test` unchanged into `work_on_tool_test.clj`.
- [x] Add only the namespace requires needed by the moved tool tests and their helpers.

## Slice 5 — Done/rebase/status test split

- [x] Create `extensions/work-on/test/extensions/work_done_test.clj` with namespace `extensions.work-done-test`.
- [x] Move `work-done-and-rebase-commands-test` unchanged into `work_done_test.clj`.
- [x] Move `work-done-auto-rebase-success-test` unchanged into `work_done_test.clj`.
- [x] Move `work-done-auto-rebase-failure-test` unchanged into `work_done_test.clj`.
- [x] Move `work-done-merge-verification-failure-test` unchanged into `work_done_test.clj`.
- [x] Move `work-done-main-worktree-guard-test` unchanged into `work_done_test.clj`.
- [x] Move `work-main-worktree-guards-and-status-test` unchanged into `work_done_test.clj`.
- [x] Add only the namespace requires needed by the moved done/rebase/status tests and their helpers.

## Slice 6 — Remove monolith and verify split

- [x] Remove `extensions/work-on/test/extensions/work_on_test.clj` after all listed tests have been moved, unless a verified test-runner requirement demands a non-duplicating compatibility file.
- [x] Compare the before/after `deftest` name sets and confirm there are no missing or duplicate original test names.
- [x] Run `wc -l` on `work_on_test_support.clj`, `work_on_command_test.clj`, `work_on_tool_test.clj`, and `work_done_test.clj`; confirm each is ≤ 800 lines with headroom.
- [x] Run `clj-paren-repair` on all created or edited Clojure test files.
- [x] Run `clj-kondo --lint` on the created or edited work-on test files and fix any introduced lint issues.
- [x] Run the focused split work-on extension tests and confirm all moved tests pass.

## Slice 7 — File-length lint widening

- [x] Update `bb.edn` `commit-check:file-lengths` doc text to say it scans `components/`, `bases/`, and `extensions/`.
- [x] Update the `find` root arguments in `commit-check:file-lengths` to include `extensions` while preserving the existing `src`/`test` path filter.
- [x] Verify the widened `find` expression lists files under `extensions/*/src/*` and `extensions/*/test/*` without changing the intended `components/`/`bases/` matches.
- [x] Create or use a controlled oversized file under an `extensions/*/test/` or `extensions/*/src/` path and confirm `bb commit-check:file-lengths` exits non-zero and reports that path, then remove the controlled file if one was created.
- [x] Run `bb commit-check:file-lengths` against the real tree and record whether it passes, fails only on real out-of-scope oversized scanned files, or uses an explicit legacy ratchet for those files.

## Slice 8 — Final validation and notes

- [x] Run the focused work-on extension tests one final time and record the result in `implementation.md`.
- [x] Run final line-count checks for all new split files and record the results in `implementation.md`.
- [x] Record any expected out-of-scope `bb commit-check:file-lengths` failures or legacy ratchet baselines in `implementation.md`.
- [x] Confirm `git status --short` contains only intended changes for this task before committing the implementation work.

## Test review follow-ups

- [x] TT1: Add executable coverage for `bb commit-check:file-lengths`'s widened `extensions/` scan, using a controlled oversized file under `extensions/*/{src,test}/` and asserting the task exits non-zero and reports the offending path with the 800-line limit; if legacy oversized extension files remain ratcheted, assert each recorded baseline path passes at its current count but fails if it grows.
- [x] TT2: Add executable coverage that `bb commit-check:file-lengths` still scans `components/*/{src,test}/` and `bases/*/{src,test}/` after the `extensions/` widening, using controlled oversized files or an equivalent isolated fixture so regressions in the original roots fail.
