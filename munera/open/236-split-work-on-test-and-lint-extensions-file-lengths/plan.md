# Plan

## Approach

This is a behaviour-preserving test reorganization plus a small lint-scope change.

1. Establish a baseline for `extensions/work-on/test/extensions/work_on_test.clj`: record the current `deftest` names and focused test result before moving code.
2. Extract all shared helper forms from the top of `work_on_test.clj` into `extensions.work-on-test-support`, keeping that namespace support-only with no `deftest`s.
3. Replace the monolithic `extensions.work-on-test` namespace with the three cohesive target test namespaces named in `design.md`:
   - `extensions.work-on-command-test` for slug/path helpers, command registration/session-switch behavior, `/work-on` command behavior, command parsing/usage errors, active-session command routing, and remote-base-ref command integration.
   - `extensions.work-on-tool-test` for work-on tool happy-path, usage-error, reuse/session parity, and active-session tool routing tests.
   - `extensions.work-done-test` for `/work-done`, `/work-rebase`, and `/work-status` coverage.
4. Keep each original `deftest` name unchanged and move assertions without behavioural edits. Update namespace requires only as needed for relocated helpers and direct dependencies.
5. Delete the old monolithic `work_on_test.clj` after its tests have been moved, unless a final verification shows some test runner configuration still requires a compatibility file; any compatibility file would still need to remain under 800 lines and define no duplicate tests.
6. Widen `bb.edn`'s `commit-check:file-lengths` task so the existing `components/` and `bases/` scan also includes `extensions/`, and update its task docstring to match the new scope.
7. Validate by comparing test names/results before and after the split, checking all resulting file lengths, running the focused work-on tests, and exercising `bb commit-check:file-lengths` enough to prove that oversized scanned files under `extensions/*/{src,test}/` are reported without breaking existing `components/`/`bases/` scanning.

## Key decisions

- Split by responsibility, not by mechanically cutting the file near 800 lines.
- Use a test-only support namespace for helpers to avoid helper duplication across split test files.
- Preserve all `deftest` symbols exactly; only their containing namespaces change.
- Treat user documentation and changelog updates as out of scope for this implementation slice, per `design.md`, until the recorded `SCOPE_QUESTION` is answered.
- `bb commit-check:file-lengths` is allowed to expose real pre-existing oversized `extensions/` files outside this task. To keep commit checks usable, those exact legacy files may be documented as a ratcheted baseline: they pass at their current line counts, fail if they grow, and do not exempt any new oversized file.

## Risks

- Moving helpers can accidentally change private visibility or omit required dependencies in a split namespace.
- Test fixtures create temporary worktrees/repos; a failed move could leak filesystem state or hide cleanup behavior changes.
- The widened `find` invocation may fail if one of the scanned roots is absent in a different checkout shape, or if the expression grouping changes unintentionally.
- `bb commit-check:file-lengths` may expose unrelated oversized extension files; this is expected if they are real violations, but it can make the command non-green for reasons outside the split.
- Leaving the old namespace around with duplicated `deftest`s would change test counts and violate the behaviour-preservation requirement.

## Slice order

1. **Baseline and inventory** — capture current test names, helper forms, focused test result, and line counts.
2. **Support namespace extraction** — create `work_on_test_support.clj` and update moved helper call sites.
3. **Command test split** — create `work_on_command_test.clj` and move command-related tests unchanged.
4. **Tool test split** — create `work_on_tool_test.clj` and move tool-related tests unchanged.
5. **Done/rebase/status test split** — create `work_done_test.clj` and move completion/rebase/status tests unchanged.
6. **Remove monolith and verify split** — delete or empty the old monolithic file only after all tests are accounted for; verify no duplicate/missing `deftest`s and all split files are ≤ 800 lines.
7. **File-length lint widening** — update `bb.edn` so `commit-check:file-lengths` scans `extensions/` as well as `components/` and `bases/`.
8. **Final validation and notes** — run focused tests, line-count checks, and lint-scope checks; record any expected out-of-scope oversized-extension failures in `implementation.md`.
