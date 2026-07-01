# 236 — Split `work_on_test.clj` and Widen File-Length Lint to `extensions/`

## Goal

Bring `extensions/work-on/test/extensions/work_on_test.clj` back under the
project's 800-line-per-file standard, and make `bb commit-check:file-lengths`
actually able to catch violations like it in the future.

## Context

`extensions/work-on/test/extensions/work_on_test.clj` is 1292 lines (already
1277 lines before `munera/closed/234-complete-test-artifact-cleanup`'s
changes; that task's `delete-recursively!` helper + `try`/`finally` wrap
added ~15 more lines), well over the project's 800-line-per-file standard
(`clojure-coding-standards` SKILL.md: "File size limit: 800 lines maximum
per file").

`bb commit-check:file-lengths` does not catch this because it only scans
`components/` and `bases/` (`find components bases -type f ( -path */src/*
-o -path */test/* )`), not `extensions/`, so the violation goes
unenforced. This gap and the file's over-limit size were both flagged as an
implementation-review follow-up on task 234, but are out of that task's
scope (234's Out of Scope forbids restructuring beyond cleanup-path fixes,
and file-length enforcement is unrelated to that task's test-artifact-leak
Acceptance Criteria).

## Constraints

- Splitting `work_on_test.clj` must not change test behaviour, assertions,
  or which `deftest`s run — it is a pure reorganization (e.g. by grouping
  related `deftest`s into new sibling test namespaces/files).
- Follow `clojure-coding-standards` SKILL.md conventions for the resulting
  file(s) (naming, `ns` requires, no `use-fixtures`, etc.).
- Widening `commit-check:file-lengths` to scan `extensions/` must not
  break for repos/subtrees that don't have `src`/`test` dirs in that shape;
  verify the `find` pattern still behaves once `extensions/` is added.

## Acceptance Criteria

1. `extensions/work-on/test/extensions/work_on_test.clj` (and any file(s)
   it is split into) are each ≤ 800 lines.
2. All existing `deftest`s that were in `work_on_test.clj` still run and
   pass after the split (same pass/fail counts as before, modulo the
   split itself introducing no behaviour change).
3. `bb commit-check:file-lengths` scans `extensions/` in addition to
   `components/` and `bases/`, and fails (non-zero exit, listing the
   offending path) when a file under `extensions/`'s `src/`/`test/` paths
   exceeds 800 lines.
4. `bb commit-check:file-lengths` continues to pass (or correctly fail) as
   before for `components/`/`bases/` — no regression in existing coverage.

## Scope

### In Scope
- `extensions/work-on/test/extensions/work_on_test.clj` — split into
  multiple files under 800 lines each.
- `bb.edn`'s `commit-check:file-lengths` task — widen the `find` scope to
  include `extensions/`.

### Out of Scope
- Any other file exceeding (or approaching) the 800-line limit elsewhere
  in the repo — only `work_on_test.clj` is in scope for splitting.
- Changing the 800-line threshold itself.
- Test logic/assertion changes beyond what's needed to relocate `deftest`s
  across files (e.g. shared test fixtures/helpers may need to move to a
  shared namespace, but assertions themselves must not change).

## Key Questions

1. What's the natural split boundary for `work_on_test.clj`'s `deftest`s
   (e.g. by command/feature area under test), so the resulting files are
   cohesive rather than an arbitrary line-count split?
