# 236 — Split `work_on_test.clj` and Widen File-Length Lint to `extensions/`

## Goal

Bring `extensions/work-on/test/extensions/work_on_test.clj` back under the
project's 800-line-per-file standard, and make `bb commit-check:file-lengths`
actually able to catch violations like it in the future.

## Context

`extensions/work-on/test/extensions/work_on_test.clj` is currently 1298 lines
(already 1277 lines before `munera/closed/234-complete-test-artifact-cleanup`'s
changes; that task's local cleanup helper + `try`/`finally` cleanup wiring
increased it further), well over the project's 800-line-per-file standard
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
  or which `deftest`s run — it is a pure reorganization by cohesive test
  responsibility.
- Preserve the existing `deftest` names so review can compare the before/after
  test set directly.
- Follow `clojure-coding-standards` SKILL.md conventions for the resulting
  file(s) (naming, `ns` requires, no `use-fixtures`, etc.).
- Widening `commit-check:file-lengths` to scan `extensions/` must not
  break for repos/subtrees that don't have `src`/`test` dirs in that shape;
  verify the `find` pattern still behaves once `extensions/` is added.
- This task may introduce a test-only support namespace if shared helpers would
  otherwise be duplicated across the split test namespaces.

## Design Clarifications

### Split boundary and target names

Split by work-on responsibility, not by raw line count. Use these target test
namespaces/files under `extensions/work-on/test/extensions/`:

- `work_on_test_support.clj` / `extensions.work-on-test-support` — shared
  helpers currently at the top of `work_on_test.clj` and any extracted
  runtime-test helper setup. This namespace is support only and should not
  define `deftest`s.
- `work_on_command_test.clj` / `extensions.work-on-command-test` — mechanical
  slug/path helpers, extension command registration/session-switch behavior,
  `/work-on` command creation/reuse, command argument parsing/usage errors,
  active-session command routing, and the remote-base-ref command integration
  test.
- `work_on_tool_test.clj` / `extensions.work-on-tool-test` — work-on tool
  happy-path, usage-error, reuse/session parity, and active-session tool
  routing tests.
- `work_done_test.clj` / `extensions.work-done-test` — `/work-done`,
  `/work-rebase`, and `/work-status` tests, including auto-rebase,
  verification-failure, main-worktree guard, and status rendering coverage.

All resulting files, including the support file, must remain at or below 800
lines. Prefer leaving headroom rather than creating a file just under the
limit.

### Expected `commit-check:file-lengths` outcome

After this task, `bb commit-check:file-lengths` is expected to scan
`extensions/` in addition to `components/` and `bases/`. Task success does not
require making every pre-existing oversized `extensions/` `src`/`test` file in
the repository compliant, because those files are explicitly out of scope.

Therefore, against the current repository, the widened command may either
report remaining pre-existing out-of-scope oversized extension files or carry
an explicit legacy baseline for them so commit checks remain usable. If a
legacy baseline is used, it must be a ratchet: the recorded out-of-scope files
may pass at their current line counts but fail if they grow, and any new
scanned file over 800 lines must still fail. It must no longer report
`extensions/work-on/test/extensions/work_on_test.clj` or any file created by
this split.

## Acceptance Criteria

1. `extensions/work-on/test/extensions/work_on_test.clj` is split into the
   cohesive test/support namespaces named above, and each resulting file is
   ≤ 800 lines.
2. All existing `deftest`s that were in `work_on_test.clj` still run and
   pass after the split (same pass/fail counts as before, modulo the
   namespace split itself introducing no behaviour change).
3. `bb commit-check:file-lengths` scans `extensions/` in addition to
   `components/` and `bases/`, and fails (non-zero exit, listing the
   offending path) when a scanned file under `extensions/`'s `src`/`test`
   paths exceeds 800 lines, except for explicitly recorded pre-existing
   out-of-scope oversized extension files that are ratcheted to fail on
   growth.
4. `bb commit-check:file-lengths` continues to evaluate `components/` and
   `bases/` as before, and any failure after adding `extensions/` is limited
   to real oversized scanned files rather than a broken `find` pattern or
   regression in existing coverage.

## Scope

### In Scope
- `extensions/work-on/test/extensions/work_on_test.clj` — split into
  multiple cohesive files under 800 lines each.
- `bb.edn`'s `commit-check:file-lengths` task — widen the `find` scope to
  include `extensions/`.

### Out of Scope
- Any other file exceeding (or approaching) the 800-line limit elsewhere
  in the repo — only `work_on_test.clj` is in scope for splitting.
- Changing the 800-line threshold itself.
- Test logic/assertion changes beyond what's needed to relocate `deftest`s
  across files (e.g. shared test helpers may need to move to a shared
  namespace, but assertions themselves must not change).
- Documentation or changelog scope changes for `bb commit-check:file-lengths`
  until the open `SCOPE_QUESTION` in `design-steps.md` is answered by the user.

## Key Questions

1. Should the `bb commit-check:file-lengths` behavior change require user-doc
   and changelog updates in this task, or is this commit-check task considered
   internal enough to omit them? See the deferred `SCOPE_QUESTION` in
   `design-steps.md`.
