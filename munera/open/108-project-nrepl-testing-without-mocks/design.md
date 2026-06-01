# 108 — Project nREPL testing without mocks

## Goal

Reshape the extracted `project-nrepl` component tests to follow the repository's `testing-without-mocks` pattern, so component-local tests are primarily sociable, state-based, and free of `with-redefs` seam patching.

## Why

Task `107-project-nrepl-component-extraction` successfully extracted the managed project nREPL subsystem into `components/project-nrepl/`, but the moved component-local test suite preserved several mock-style testing seams.

A focused audit of `components/project-nrepl/test/psi/project_nrepl/` shows that multiple tests currently use `with-redefs` to replace internal functions or external-library calls:

- `config_test.clj`
  - redefines `read-user-config`
  - redefines `read-project-preferences`
- `client_test.clj`
  - redefines `nrepl.core/connect`, `nrepl.core/client`, and `nrepl.core/client-session`
- `attach_test.clj`
  - redefines `psi.project-nrepl.client/connect-instance-in!`
- `started_test.clj`
  - redefines `start-process!`
  - redefines `psi.project-nrepl.client/connect-instance-in!`
- `commands_test.clj`
  - redefines `psi.project-nrepl.config/resolve-config`
  - redefines `psi.project-nrepl.ops/eval-op`
  - redefines `psi.project-nrepl.ops/interrupt`
- `ops_test.clj`
  - redefines `psi.project-nrepl.eval/eval-instance-in!` (success and interrupted
    contract proofs)

This conflicts with the repo's testing guidance:

- prefer state-based assertions over interaction assertions
- prefer real collaborators for logic
- use nullables / embedded stubs for infrastructure
- avoid mock-style replacement of collaborators with `with-redefs`

## Problem

The extracted `project-nrepl` test suite currently proves behavior through replaced vars rather than through visible behavior at the component boundary.

This has several costs:

- tests are more coupled to internal call structure than to behavior
- refactoring is harder because tests encode seam topology
- the extracted component does not yet demonstrate the intended nullable-wrapper testing style for infrastructure-heavy code
- the project-nREPL component is now a local pocket of testing style drift

## Intent

Refactor the `project-nrepl` component and its component-local tests so the tests mostly exercise real component behavior with:

- real in-memory session/runtime state
- real temp directories and files for config and `.nrepl-port` surfaces
- nullable or embedded-stub wrappers for nREPL/process infrastructure where real infrastructure is inappropriate for unit tests
- assertions on returned values and updated runtime state instead of patched function calls

This task should leave the component extraction boundary from `107` intact. It is a test-shaping and seam-shaping task, not a boundary-redesign task.

## Audit summary

### Keep largely as-is

- `runtime_test.clj`
  - already mostly state-based and sociable
- `eval_test.clj`
  - already close to the target style because it installs a real in-memory client-session function and asserts on result/state

### Improve

- `config_test.clj`
  - reduce or remove `with-redefs` around config readers
  - prefer file-backed tests or a lower explicit config source seam that can be exercised without var replacement

### Redesign around nullable infrastructure seams

- `client_test.clj`
  - replace direct `nrepl.core/*` redefinitions with a nullable/thin-wrapper approach around connection/client/session establishment
- `attach_test.clj`
  - stop redefining `connect-instance-in!`; instead exercise attach behavior through a nullable client/connect seam and assert on resulting instance state
- `started_test.clj`
  - stop redefining `start-process!` and `connect-instance-in!`; instead introduce or reuse a process-start wrapper and nullable connect seam so tests can drive startup behavior through visible state
- `commands_test.clj`
  - stop redefining `resolve-config`, `eval-op`, and `interrupt`; instead use real component behavior with temp config/runtime state plus the canonical `[:runtime-handle :client-session]` seam so command-layer operational routing is proven through real `ops`/`eval` behavior (see "Concrete target for `commands_test.clj` operational routing")
- `ops_test.clj`
  - stop redefining `psi.project-nrepl.eval/eval-instance-in!`; instead install a real managed instance with a deterministic in-memory `[:runtime-handle :client-session]` seam (the `eval_test.clj` pattern) and assert `eval-op`'s public success/interrupted contract through real `eval-instance-in!` behavior

## Scope

In scope:

- audit and reshape tests under `components/project-nrepl/test/psi/project_nrepl/`
- `ops_test.clj` is **in scope**: its `with-redefs` on `eval-instance-in!`
  replaces the same internal eval collaborator this task de-mocks elsewhere, and
  the canonical `[:runtime-handle :client-session]` seam built for
  `eval_test`/`commands_test` covers it directly, so excluding it would leave a
  residual mock pocket in the same namespace family. The six files de-mocked by
  this task are therefore: `config_test.clj`, `client_test.clj`, `attach_test.clj`,
  `started_test.clj`, `commands_test.clj`, and `ops_test.clj`.
- introduce minimal production seams needed to support nullable infrastructure testing
- prefer thin wrappers / embedded stubs over test-only helper layers
- keep tests narrow, state-based, and fast
- preserve current user-visible behavior
- update any higher-level tests only where component-local test ownership becomes clearer
- document the resulting nullable/testing shape in task notes

Out of scope:

- redesigning project nREPL user-facing behavior
- changing the component boundary created by `107`
- broad repo-wide test-style conversion outside the `project-nrepl` slice
- adding broad end-to-end nREPL integration tests unless a very small smoke proof is needed

## Design guidance

### Testing style target

Apply the `testing-without-mocks` skill directly:

- logic and state helpers should use real collaborators
- infrastructure should be wrapped behind thin, production-owned APIs
- wrappers should support nullable behavior or embedded stubs for unit tests
- tests should assert on outputs and state, not collaborator call counts or exact internal routing

### Seam-injection mechanism (canonical)

All infrastructure seams introduced by this task MUST use a single, consistent
injection mechanism: **the seam implementation is supplied as a function value
carried in per-instance runtime state (`runtime-handle`), seeded at instance
acquisition time, and resolved at call time with a real-implementation default
when absent.** This mirrors the already-proven idiom in `eval_test.clj`, where
the nREPL `client-session` function lives under
`[:runtime-handle :client-session]` and `psi.project-nrepl.eval` reads it from
state rather than resolving a var.

Concretely:

- Production code resolves each seam via a small helper of the form
  `(or (get-in instance [:runtime-handle <seam-key>]) <real-default-fn>)`.
- `ensure-instance-in!` / `replace-instance-in!` (and the acquisition entry
  points `attach-instance-in!`, `start-instance-in!`) accept an optional
  `:runtime-handle` (or seam-specific) seed so a test can install a deterministic
  seam fn into instance state before the code path runs.
- Tests install the deterministic seam fn through that seed (no `with-redefs`,
  no passed-through ad-hoc argument that production callers must thread).

Seam keys introduced or reused:

- `[:runtime-handle :client-session]` — already exists; reused unchanged by
  `eval`/`interrupt` paths.
- `[:runtime-handle :nrepl-connector]` — new; a single fn that performs the
  `connect → client → client-session` establishment for `connect-instance-in!`,
  returning `{:transport t :client c :client-session s :session-id id}`. The
  real default calls `nrepl.core/connect|client|client-session` (the inline
  `requiring-resolve` block currently in `client.clj` becomes the default
  implementation of this seam).
- `[:runtime-handle :process-launcher]` — new; a single fn
  `(fn [worktree-path command-vector] -> Process-like)` for `start-instance-in!`.
  The real default is the current private `start-process!` (promoted to be the
  default behind the seam). The launched object only needs the `Process`-shaped
  lifecycle surface already exercised by the `fake-process` proxy in
  `started_test.clj` (`isAlive`, `exitValue`, `pid`, `destroy`).

This mechanism is required identically across `client_test.clj`,
`started_test.clj`, and `attach_test.clj`; `attach_test.clj` drives the
`:nrepl-connector` seam transitively through `attach-instance-in! → connect-instance-in!`
rather than redefining `connect-instance-in!`.

Rationale for choosing runtime-state injection over a passed argument or options
map: it is the only mechanism already present and proven in this component
(`eval_test.clj`), it keeps production call sites unchanged for real callers
(seam defaults apply automatically), and it avoids threading test-only parameters
through `ops`/`commands` callers. A passed-argument or options-map mechanism was
rejected because it would force every intermediate caller (`ops`, `commands`) to
thread a seam parameter that only tests supply.

### Preferred seam shapes

#### nREPL client seam

Introduce one thin wrapper (`:nrepl-connector`, see Seam-injection mechanism)
around the external `nrepl.core` operations used by `psi.project-nrepl.client`.

Target properties:

- production path still calls real `nrepl.core` (as the seam default)
- nullable path provides deterministic transport/client/session behavior via the
  `[:runtime-handle :nrepl-connector]` seed
- tests assert on connected instance state and any visible request/response data
  rather than on direct function replacement

#### process-start seam

Introduce one thin wrapper (`:process-launcher`, see Seam-injection mechanism)
around process startup used by `psi.project-nrepl.started`.

Target properties:

- production path still launches a real process (as the seam default)
- nullable path provides a fake `Process`-shaped object with visible lifecycle
  state via the `[:runtime-handle :process-launcher]` seed
- started-mode tests can drive `.nrepl-port` appearance and readiness through
  state rather than var replacement

#### config source seam

Prefer one of these in order:

1. real file-backed config tests using the existing on-disk config format
2. a small explicit config-source seam passed as data or context
3. only if strictly necessary, a very narrow production-owned wrapper with a nullable implementation

Avoid simply moving existing `with-redefs` one layer lower.

##### Concrete target for `config_test.clj` `resolve-config`

Option 1 (real file-backed config) applies. The two `resolve-config` tests that
currently redefine `read-user-config` and `read-project-preferences` are
reshaped to drive `resolve-config` through real on-disk project config files in
a temp worktree, exactly as the already-file-backed `read-project-preferences-test`
does for the project layer.

Decisions:

- The user-scope reader (`read-user-config`) reads `~/.psi/agent/config.edn`,
  which a unit test must not depend on or mutate. For the merge-precedence proof,
  the user layer is provided through real project config files only when the
  precedence under test is system-vs-project; the user-vs-project precedence case
  is covered by writing a real shared `project.edn` (lower precedence) and a real
  local `project.local.edn` (higher precedence) in the temp worktree and asserting
  `resolve-config` returns the deep-merged `:project-nrepl` map. This exercises
  the same merge precedence the redef test asserted (project local overrides
  shared) without patching readers.
- `resolve-config` strips the `:version 1` key by design: it extracts only
  `(:project-nrepl (agent-session-map ...))`, so the real `:version 1` key the
  redef tests omitted is irrelevant to `resolve-config`'s output and the
  file-backed test asserts only the merged `:project-nrepl` map.
- The merge-precedence proof is **not** made redundant by the existing
  `read-project-preferences-test`: that test proves the project-config reader's
  shared/local merge, whereas the reshaped `resolve-config` test proves
  `resolve-config`'s own system < user < project layering and `:project-nrepl`
  extraction. Both proofs are retained; they cover different units.
- The "returns empty project-nrepl config" case is reshaped to a temp worktree
  with no project config files (and no user override available), asserting
  `resolve-config` returns `{:project-nrepl {}}` from `system-defaults`.

#### command tests

Prefer split-by-boundary tests:

- formatting/parsing tests that use real values and no seam patching
- operational tests that use real runtime/config state plus nullable infrastructure wrappers
- keep any broader routing proof above the component boundary in `agent-session`

##### Concrete target for `commands_test.clj` operational routing

The earlier "or" is resolved in favour of **real operational routing through the
canonical seam**, not reduction to formatting/parsing-only tests.

Decisions:

- The `/project-repl eval` and `/project-repl interrupt` dispatch tests currently
  redefine `psi.project-nrepl.ops/eval-op` and `psi.project-nrepl.ops/interrupt`.
  These are reshaped to install a real managed instance in runtime state (the
  `eval_test.clj` `install-instance!` pattern: a real in-memory `client-session`
  fn seeded under `[:runtime-handle :client-session]`) and then dispatch the real
  command string. The command flows through real `commands → ops → eval` code so
  the operational routing of `eval-op`/`interrupt` is proven end-to-end within the
  component, with assertions on the user-facing `{:type :text :message ...}` result.
- This makes command-layer operational routing genuinely covered (the redef
  version proved only that `commands` calls some `ops` fn, not that the routing
  produces correct results from real op behavior).
- Pure formatting/parsing concerns that do not need a live instance (status
  formatting, missing-start-command messaging, command-string parsing) remain as
  separate real-value tests with no seam at all — they already are.
- The single in-memory `client-session` seam reused here is the same canonical
  `[:runtime-handle :client-session]` seam from `eval_test.clj`; no command-layer
  seam parameter is introduced.

## Acceptance

- component-local `project-nrepl` tests no longer rely on `with-redefs` for the current mock-style seams in `config_test.clj`, `client_test.clj`, `attach_test.clj`, `started_test.clj`, `commands_test.clj`, and `ops_test.clj`
- any new production seams are thin, component-owned, and justified by infrastructure boundaries rather than test convenience
- `runtime_test.clj` and `eval_test.clj` remain state-based and green
- focused `project-nrepl` component tests are green after the reshaping
- `implementation.md` records the final nullable/wrapper strategy with a separate documented strategy note for each infrastructure seam — one for the nREPL client seam (`:nrepl-connector`) and one for the process-start seam (`:process-launcher`); a single combined note is **not** sufficient
- behavior remains unchanged at the component boundary

## Suggested execution order

1. classify each component-local test as keep / improve / redesign
2. shape the smallest production-owned infrastructure wrappers needed for nREPL connection and process startup
3. migrate `client_test.clj`
4. migrate `attach_test.clj`
5. migrate `started_test.clj`
6. migrate `config_test.clj`
7. migrate `commands_test.clj`
8. migrate `ops_test.clj`
9. re-run focused component verification
10. record the resulting testing strategy and any remaining justified exceptions

## Related work

- `105-agent-session-component-extraction-map` is the umbrella extraction map
- `107-project-nrepl-component-extraction` established the `project-nrepl` component boundary
- this task is a follow-on quality slice that aligns the extracted component with the repo's testing-without-mocks standard
