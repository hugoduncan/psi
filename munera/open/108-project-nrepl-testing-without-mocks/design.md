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
  - stop redefining `resolve-config`, `eval-op`, and `interrupt`; instead use real component behavior with temp config/runtime state, or split lower formatting/parsing tests from higher operational integration tests so each test owns a real boundary

## Scope

In scope:

- audit and reshape tests under `components/project-nrepl/test/psi/project_nrepl/`
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

### Preferred seam shapes

#### nREPL client seam

Introduce or refine one thin wrapper around the external `nrepl.core` operations used by `psi.project-nrepl.client`.

Target properties:

- production path still calls real `nrepl.core`
- nullable path can provide deterministic transport/client/session behavior
- tests assert on connected instance state and any visible request/response data rather than on direct function replacement

#### process-start seam

Introduce or refine one thin wrapper around process startup used by `psi.project-nrepl.started`.

Target properties:

- production path still launches a real process
- nullable path can provide a fake process object with visible lifecycle state
- started-mode tests can drive `.nrepl-port` appearance and readiness through state rather than var replacement

#### config source seam

Prefer one of these in order:

1. real file-backed config tests using the existing on-disk config format
2. a small explicit config-source seam passed as data or context
3. only if strictly necessary, a very narrow production-owned wrapper with a nullable implementation

Avoid simply moving existing `with-redefs` one layer lower.

#### command tests

Prefer split-by-boundary tests:

- formatting/parsing tests that use real values and no seam patching
- operational tests that use real runtime/config state plus nullable infrastructure wrappers
- keep any broader routing proof above the component boundary in `agent-session`

## Acceptance

- component-local `project-nrepl` tests no longer rely on `with-redefs` for the current mock-style seams in `config_test.clj`, `client_test.clj`, `attach_test.clj`, `started_test.clj`, and `commands_test.clj`
- any new production seams are thin, component-owned, and justified by infrastructure boundaries rather than test convenience
- `runtime_test.clj` and `eval_test.clj` remain state-based and green
- focused `project-nrepl` component tests are green after the reshaping
- at least one note in `implementation.md` records the final nullable/wrapper strategy used for nREPL and process infrastructure
- behavior remains unchanged at the component boundary

## Suggested execution order

1. classify each component-local test as keep / improve / redesign
2. shape the smallest production-owned infrastructure wrappers needed for nREPL connection and process startup
3. migrate `client_test.clj`
4. migrate `attach_test.clj`
5. migrate `started_test.clj`
6. migrate `config_test.clj`
7. migrate `commands_test.clj`
8. re-run focused component verification
9. record the resulting testing strategy and any remaining justified exceptions

## Related work

- `105-agent-session-component-extraction-map` is the umbrella extraction map
- `107-project-nrepl-component-extraction` established the `project-nrepl` component boundary
- this task is a follow-on quality slice that aligns the extracted component with the repo's testing-without-mocks standard
