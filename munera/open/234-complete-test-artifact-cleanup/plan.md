# Plan — 234 Complete Test Artifact Cleanup

## Approach

Work through the 4 in-scope files, confirming or fixing cleanup for each of
the 10 listed prefixes, per design.md's Root Cause Analysis (Patterns A-D)
and the Scope Decision (shutdown-hook safety net for `temp-cwd`/
`temp-session-root`).

1. `components/agent-session/test/psi/agent_session/test_support.clj`
   (Pattern B, prefixes `psi-agent-session-test-` / `psi-agent-session-store-`):
   add a JVM shutdown hook in `temp-cwd` and `temp-session-root` that calls
   `delete-recursively!` on the created directory. This is the only
   functional code change required by this task — see design.md's Scope
   Decision section for rationale.

2. `components/history/test/psi/history/git_worktree_test.clj`
   (Pattern A, prefixes `existing-path`, `feature-attached`,
   `feature-diverged`, `feature-merge`, `feature-rebase`,
   `legacy-create-branch`): verify (already done during design/plan
   research — see implementation.md) that every test creating a worktree
   via `linked-worktree-path` does so inside `with-null-context`, whose
   `finally` recursively deletes `:repo-dir` (which contains
   `repo-dir/worktrees/...`). Confirmed: no gaps found. No code change.

3. `components/agent-session/test/psi/agent_session/query_graph_test.clj`
   (Pattern C, prefixes `ext-mutation-worktree`, `fix-repeated-thinking`):
   verify both worktree-creating test bodies already wrap their work in
   `try`/`finally` + `test-support/delete-recursively!` on `repo-dir`.
   Confirmed: both already do. No code change.

4. `extensions/work-on/test/extensions/work_on_test.clj` (Pattern D,
   `fix-repeated-thinking-output`): confirmed all occurrences are string
   literals (`/repo/fix-repeated-thinking-output`) in stubbed assertion
   data — no real filesystem paths created. No code change.

## Verification

- `bb lint` (or `clj-kondo --lint` on changed files) — no new
  errors/warnings in changed files (AC5).
- Run the affected test namespaces directly (`clojure -M:test --focus ...`)
  to confirm green and to inspect `/tmp` before/after for leaked
  `psi-agent-session-*` dirs (AC1).
- `git worktree list` before/after running `git_worktree_test.clj` and
  `query_graph_test.clj` to confirm no test-created worktrees remain (AC2).
- Full `bb test` run is the authoritative whole-suite check for AC1/AC4, but
  given runtime cost, the targeted run above plus a final full `bb test`
  pass (already required for AC4) double as the AC1 verification.

## Risks

- Shutdown hooks do not fire on `kill -9`/JVM crash, but this is an
  accepted limitation of any process-exit-based cleanup and is consistent
  with the existing `finally`-based cleanup, which also does not run on a
  hard kill.
- Shutdown hooks registered per-call could accumulate if a long-lived REPL
  JVM calls `temp-cwd` many times across a session; acceptable since the
  hooks are lightweight (only run at JVM exit) and `bb test` is a
  short-lived process per run.
