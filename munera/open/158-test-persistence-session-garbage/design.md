# Task 158 — Test persistence session garbage

## Intent
Stop automated tests from writing persisted session artifacts for temporary test worktrees into the user session store under `~/.psi/agent/sessions/`, especially directories derived from macOS temporary paths such as `--var-folders-*` and `--private-var-folders-*`.

## Context
Persisted session directories are keyed by encoded worktree path. The session journal store currently derives directory names from the session worktree path by replacing path separators with `-`, so a temporary test worktree like `/var/folders/...` becomes a persisted directory under `~/.psi/agent/sessions/--var-folders-...--`.

This is correct for normal runtime behavior, but some tests still create real runtime or session contexts with temporary working directories while persistence remains enabled. Those tests unintentionally write session files into the developer’s real home-directory session store and leave local garbage behind.

The system already has a `:persist? false` seam used by many test helpers. In particular:
- `psi.agent-session.test-support/create-test-session` already defaults to `:persist? false`
- many session-focused tests already route through safe helpers with temp cwd + persistence disabled
- at least some runtime/bootstrap-oriented tests still bypass that discipline and call real runtime bootstrap with temp `:cwd` while leaving persistence enabled

A likely current leak path is the app-runtime startup/bootstrap test seam, where helper code creates a real runtime session against a temp cwd in order to prove startup behavior but does not make non-persistence explicit.

There is a second requirement for tests that *do* intentionally prove persistence behavior: they must not use the real default session root either. Explicit persistence tests should isolate their session storage under a test-owned temporary session root and clean that root up when the test completes, so persistence coverage remains real without leaking artifacts into the developer environment.

## Definitions

### Explicit persistence test
An explicit persistence test is a test whose assertions require real on-disk session journal or session-file behavior.

Examples include tests whose proof depends on one or more of:
- creation of real session files on disk
- journal append/flush behavior on disk
- loading or resuming from a real persisted session file
- fork behavior that depends on persisted parent/child session-file relationships

A test is **not** an explicit persistence test merely because the runtime happens to create a session, or because a `:session-file` field exists in state, if the test’s actual acceptance does not depend on real on-disk persistence behavior.

### Temporary session root
A temporary session root is a test-owned filesystem root used in place of the default home-derived session storage location. It is the canonical isolation mechanism for tests that intentionally require persistence.

### Real default session store
The real default session store is the default home-derived session root used by normal runtime behavior, currently the location under the current `user.home` that resolves to `~/.psi/agent/sessions/` when no explicit test-owned override is configured.

### Guardrail rule
No test may persist into the real default session store.

If a test does not require real on-disk persistence behavior, it must run with persistence disabled.

If a test does require real on-disk persistence behavior, it must provide an explicit isolated temporary session root rather than falling through to the real default session store.

## Problem statement
The project needs a clear test-time rule:
- tests proving persistence behavior may opt into persistence explicitly
- all other tests that use temp worktrees or temp cwd must not write persisted sessions into the user’s real session store
- tests that intentionally prove persistence must use isolated temporary session roots and clean them up

The missing piece is enforcing that rule at the right seam so runtime/bootstrap tests do not silently regress and explicit persistence tests stay isolated.

## Scope
This task covers test-time behavior only.

In scope:
- identify test entrypoints/helpers that still allow incidental persisted session creation against temp test worktrees
- tighten the shared test seam so non-persistence is the default for tests that do not explicitly prove persistence
- patch runtime/bootstrap-oriented tests that currently create real persisted sessions incidentally
- preserve explicit persistence tests and keep them clear about why persistence is enabled
- ensure explicit persistence tests use isolated temporary session roots rather than the real default session store
- ensure explicit persistence tests clean up their temporary session roots after themselves
- ensure routine test execution no longer writes temp-worktree session directories into the real default session store

Out of scope:
- changing normal non-test runtime persistence behavior
- redesigning the session journal storage layout or directory naming scheme
- changing persistence semantics for tests whose purpose is to verify on-disk persistence or resume/fork behavior
- adding automatic cleanup of historical garbage already present in a developer’s home directory
- redirecting production persistence into a test-specific root during ordinary runtime use

## Design choices to settle
The task should refine and choose among these seams, preferring the narrowest shared seam that prevents recurrence:

1. **Test helper defaulting**
   - ensure app-runtime/runtime-bootstrap test helpers explicitly propagate `:persist? false`
   - use the same discipline already present in `psi.agent-session.test-support`
   - treat the app-runtime startup/bootstrap helper path as the first shared seam to converge unless inventory proves a better common seam

2. **Guardrails for unsafe persisted test contexts**
   - extend existing safety checks so tests that attempt persistence with a temp cwd must do so deliberately
   - fail fast when a test attempts persisted session creation against the real default session store without explicit isolated test configuration
   - prefer helper discipline plus a test-only hard guardrail over helper discipline alone

3. **Explicit opt-in for true persistence tests**
   - tests that verify session files, resume, fork, or journal persistence stay explicit about persistence being required
   - those tests must also choose an isolated temporary session root explicitly rather than falling through to the real default session store

4. **Persistence test isolation and cleanup**
   - provide or standardize a helper for creating a temporary session root for persistence tests
   - prefer per-test helper-owned temporary session roots unless inventory proves a broader fixture scope is necessary
   - make helper-owned cleanup explicit so test-created temporary session roots are removed after the test lifecycle completes
   - prefer one obvious helper/pattern over ad hoc local temp-dir handling spread across tests
   - cleanup should be robust in the face of test failure, using helper-owned lifecycle control such as fixture or `try`/`finally` semantics rather than manual best-effort cleanup in each test body

The preferred outcome is to fix shared test bootstrap seams first, then standardize isolated persistence-test helpers, and only patch individual tests directly where that is genuinely the clearest place.

## Required decisions during implementation
Implementation must make these decisions explicit in code and tests:
- the exact seam used to select an isolated temporary session root for explicit persistence tests
- the exact helper or lifecycle owner responsible for cleanup of that temporary session root
- the exact condition that causes fail-fast when a test tries to persist into the real default session store
- the first shared helper/namespace chosen to converge app-runtime/bootstrap tests onto non-persisting defaults

## Acceptance
- routine test runs that use temp worktrees or temp cwd do not create new `~/.psi/agent/sessions/--var-folders-*` or `--private-var-folders-*` directories as incidental side effects
- app-runtime/bootstrap-oriented tests that do not prove persistence run with persistence disabled
- session/runtime test helpers make the non-persisting default obvious and hard to bypass accidentally
- tests that do require persistence remain explicit about that requirement and continue to verify the intended on-disk behavior
- explicit persistence tests use isolated temporary session roots instead of the real default session store
- explicit persistence tests may inspect files inside their isolated temporary session root during the test body when needed for assertions
- explicit persistence tests clean up the temporary session roots they create after the test-owned lifecycle completes
- the fix is enforced at shared seams that make future regressions less likely than one-off local test edits alone

## Verification ideas
Examples of acceptable verification approaches:
- focused tests around the affected runtime/bootstrap helper seam showing that temp-cwd test bootstraps do not allocate persisted session files under the real default session store
- a targeted regression test demonstrating that a temp-cwd startup/bootstrap path leaves session persistence disabled unless explicitly requested
- focused proof that explicit persistence tests can still create session files when pointed at an isolated temporary session root
- focused proof or helper-level test that temporary session roots created for tests are cleaned up by the helper-owned lifecycle after the test body finishes
- focused proof that unsafe persisted test contexts targeting the real default session store fail fast
- manual spot-check before/after against `~/.psi/agent/sessions/` during the focused test run may be used as exploratory confirmation, but it is not an acceptance requirement

## Constraints
- keep the fix narrowly focused on test persistence behavior
- prefer fixing shared test helpers or bootstrap seams over scattered one-off test patches when that preserves clarity
- do not weaken or remove coverage for real persistence behavior
- avoid any change that redirects production persistence away from the real user session store during normal runtime use
- preserve local comprehensibility: a future test author should be able to tell from the helper or call site whether persistence is intended and whether persistence is isolated
- keep guardrails test-only: production runtime behavior must remain unchanged
- prefer per-test isolated temporary session roots over namespace-scoped or suite-scoped roots unless a broader scope is required and justified
