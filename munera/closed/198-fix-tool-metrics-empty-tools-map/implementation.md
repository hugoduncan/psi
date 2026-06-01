# Implementation Notes

## 2026-06-01 — docs review follow-up: document psi/metrics built-in extension

Executed the newly added unchecked docs-review follow-up. `doc/extensions.md`
had no `psi/metrics` entry even though it ships and is activated via
`.psi/extensions.edn` (`psi/metrics {}`). Added an entry under "Built-in
extensions in this repo" (before `plan-state-learning`), matching the
`commit-checks`/`mcp-tasks-run` format.

Documented against the source of truth (`psi.metrics.extension/init`,
`psi.metrics.schema/metrics-schema`):
- subscribed events: `tool_call`, `tool_result`, `session_turn_finished`,
  `provider_request_started`, `provider_retry_scheduled`,
  `provider_request_finished`
- persistence: atomic write to `worktree/.psi/metrics.edn`, schema-validated on
  load
- `metrics/summary` deterministic operation, `/metrics` command
- full persisted shape (`:tools`/`:workflows`/`:commands`/`:operations`/
  `:tokens`/`:providers`/`:updated-at`)
- note that the task-198 `emit-tool-lifecycle!` bridge is what populates
  `:tools` (was always `{}` before the fix)

Docs-only change; no code/tests touched. steps.md item checked.

## 2026-06-01 — end-to-end test added

Added `metrics-extension-accumulates-tools-via-bridge-test` to
`components/agent-session/test/psi/agent_session/tool_execution_test.clj`.

The test:
1. Creates a real session ctx via `test-support/make-session-ctx`
2. Calls `ext/load-init-var-extension-in!` with `'psi.metrics.extension/init` and a
   minimal `runtime-fns` (query-fn returning nil worktree-path; no mutate-fn so
   `(:on api)` falls back to `register-handler-in!` directly on the registry)
3. Calls `run-tool-call!` with infrastructure redefs (no real tool execution)
4. Asserts `metrics-ext/store` has `:tools "read" :invocations 1`

This covers the full path: adapter → `emit-tool-lifecycle!` bridge → `dispatch-in` →
metrics `on-tool-call` handler → `update-metrics!` → store. A regression that
disconnects the bridge or the metrics registration would be caught.

Implementation notes:
- `load-init-var-extension-in!` requires a symbol (`'psi.metrics.extension/init`),
  not a var reference (`#'metrics-ext/init`) — the loader calls `namespace`/`name`
  on the init-var which requires the `Named` interface.
- `metrics-ext/store` and `metrics-ext/writing?` are `defonce` atoms; the test
  resets them before/after to ensure isolation.
- `psi.metrics.extension` src is already on the `:test` alias classpath via
  `extensions/metrics/src` in `deps.edn`, so no classpath changes were needed.

Verification: `clojure -M:test --focus psi.agent-session.tool-execution-test` →
`12 tests, 61 assertions, 0 failures`. `clj-kondo` clean.

## 2026-06-01 — test review

**Tests well-formed.** All acceptance criteria have test coverage. `with-redefs` is used
only for infra deps (actual tool execution, agent-core side effects) — acceptable nullable
pattern.

**Gap: no end-to-end test for the primary acceptance criterion.**
The primary AC ("tool invocations appear in `:tools` in `.psi/metrics.edn`") is tested in
two separate halves:
- `emit-tool-lifecycle-bridge-fires-extension-handlers-test`: confirms bridge fires
  registered handlers.
- `extension_test.clj` tool-call tests: confirm `on-tool-call` increments `:tools` counters
  when fired directly via `fire-event`.

No single test wires both: register the metrics extension on a real session ctx, call
`run-tool-call!`, and assert `ext/store` accumulates `:tools` entries. The full path
(adapter → bridge → metrics handler → store) is not exercised as a unit. A regression that
silently disconnects the metrics extension from the session ctx would not be caught by any
existing test.

## 2026-06-01 — fix implemented (d16e90286)

Root cause confirmed by tracing the call graph:

- `(:on api) "tool_call"` → `register-handler-in!` → stored in `:extensions ext-path :handlers "tool_call"`
- `dispatch-in "tool_call"` → reads from `:extensions % :handlers "tool_call"` → fires handlers
- `dispatch-tool-call-in` calls `dispatch-in "tool_call"` — but only called from `tool_plan.clj` and `wrap-tool-executor` (dead in interactive path)
- Interactive path: `tool-runtime/core` emits `:tool-start` / `:tool-result` via `on-event` → `emit-tool-lifecycle!` → only dispatches `:session/tool-lifecycle-event` (telemetry ring buffer), never calls `dispatch-in`

Fix: added a `case` branch in `emit-tool-lifecycle!` that calls `ext/dispatch-in` for `:tool-start` → `"tool_call"` and `:tool-result` → `"tool_result"`.

The payload shapes match what the metrics `on-tool-call` / `on-tool-result` handlers expect:
- `on-tool-call` reads `:tool-name` ✓
- `on-tool-result` reads `:tool-name`, `:is-error`, `:content` ✓

Verified: `.psi/metrics.edn` now accumulates `:tools` entries after tool calls in live session.

## 2026-06-01 — design ambiguity review

Three ambiguities found:

1. **Double-dispatch on tool-plan path**: `tool_plan.clj/execute-tool-plan-step-in!` calls
   `dispatch-tool-call-in` / `dispatch-tool-result-in` directly. If plan steps also emit
   `:tool-start` / `:tool-result` lifecycle events through `emit-tool-lifecycle!`, extension
   handlers fire twice. Design claims `emit-tool-lifecycle!` is the single injection point
   but doesn't address this path.

2. **`wrap-tool-executor` status**: Design says it's "unused in the interactive path" but
   doesn't clarify whether it's live on any path. If it is, the new bridge could also create
   double-dispatch there.

3. **`extension-registry` nil guard**: `(when-let [reg (:extension-registry ctx)])` silently
   skips extension dispatch when the registry is absent. Design doesn't state whether absence
   is a valid production state or a test-only artifact.

## 2026-06-01 — inconsistency review

Two inconsistencies found:

1. **"All tool executions" claim contradicts disjoint-paths clarification**: Fix section
   states "all tool executions (interactive, batch, background) pass through
   `emit-tool-lifecycle!`". Clarifications section states the plan path "does NOT route
   through `emit-tool-lifecycle!`" and the two paths are "disjoint". These are directly
   contradictory within design.md. The Fix section claim should be scoped to
   "interactive/batch" only.

2. **Acceptance criterion "no regressions on tool blocking/override" is ambiguous**: The
   bridge calls `dispatch-in` directly (not `dispatch-tool-call-in`), so `{:block true}`
   handler returns are silently ignored on the interactive/batch path. The design does not
   state whether this is intentional. The criterion is vacuously satisfied (existing
   blocking tests cover the plan path only), but a reader could infer blocking is preserved
   on the interactive path too.

## 2026-06-01 — implementation review

**Fix correct.** `emit-tool-lifecycle!` change is minimal, single-responsibility, and
matches the payload shapes consumed by `on-tool-call`/`on-tool-result`. Changelog entry
present. Design, plan, and code are coherent.

**Gap: no regression test for the bridge itself.**
`tool_execution_test.clj` exercises `run-tool-call!` but no test registers a `"tool_call"`
or `"tool_result"` handler on the `extension-registry` in the session ctx and asserts it
fires. The new `case` branch in `emit-tool-lifecycle!` has zero unit coverage at the
integration point — the fix could be silently reverted without any test failing.

The metrics `extension_test.clj` tests fire events directly via `fire-event` (bypassing the
adapter), so they don't cover the bridge either.

**Minor: `{:block true}` silently ignored on interactive path — untested.**
Design documents this intentional asymmetry (blocking only enforced on the plan path) but no
test asserts the non-blocking behavior, leaving it unguarded against future accidental
enforcement.

## 2026-06-01 — ambiguity resolution

1. **Double-dispatch — resolved**: `run-tool-plan-step-in!` calls `dispatch-tool-call-in` /
   `dispatch-tool-result-in` directly and does NOT call `emit-tool-lifecycle!`. The two
   paths are fully disjoint. No double-dispatch. Design updated with explicit path map.

2. **`wrap-tool-executor` — resolved**: Confirmed dead code in production. Defined in
   `extensions.clj`, referenced only in `extensions_test.clj`. No production caller exists
   anywhere in the codebase. No double-dispatch risk.

3. **`extension-registry` nil guard — resolved**: `context.clj` line 277 always sets
   `:extension-registry (ext/create-registry)`. `test_support.clj` line 208 also always
   sets it. Absence is test-only (minimal unit-test ctx bypassing `make-session-ctx`).
   Decision: keep `when-let` guard as-is — asserting presence would break low-level unit
   tests that legitimately omit the registry.

## 2026-06-01 — review follow-up tests added

Added two tests to `tool_execution_test.clj`:

1. **`emit-tool-lifecycle-bridge-fires-extension-handlers-test`**: registers `"tool_call"`
   and `"tool_result"` handlers on the session ctx's `extension-registry`, calls
   `run-tool-call!`, and asserts both handlers fire with correct payload fields
   (`:type`, `:tool-name`, `:tool-call-id`, `:is-error`). Regression guard for the
   `emit-tool-lifecycle!` bridge — the fix could be silently reverted without this test
   failing.

2. **`tool-call-handler-block-ignored-on-interactive-path-test`**: registers a `"tool_call"`
   handler returning `{:block true}`, calls `run-tool-call!`, and asserts execution
   completes and the result is recorded. Documents the intentional asymmetry: blocking is
   only enforced on the data-driven plan path (`dispatch-tool-call-in`); the interactive
   bridge calls `dispatch-in` directly so `:block` returns are silently ignored.

Verification: `clojure -M:test --focus psi.agent-session.tool-execution-test` →
`11 tests, 60 assertions, 0 failures`. `clj-kondo` clean.

## 2026-06-01 — final implementation review

**Implementation complete and correct.** All acceptance criteria satisfied:
- `emit-tool-lifecycle!` change is minimal and single-responsibility.
- Payload shapes match what `on-tool-call`/`on-tool-result` consume (`:tool-name`, `:is-error`).
- `(boolean (:is-error ...))` coercion correctly normalises `nil` → `false`.
- `case` default `nil` correctly passes through non-bridged lifecycle events (`:tool-executing`, `:tool-execution-update`).
- Bridge regression test and block-ignored non-enforcement test added and passing.
- Changelog entry present. `clj-kondo` clean.

**Pre-existing issue (not introduced by this task):** `extensions/metrics/deps.edn` is missing
`psi/root-registry` as a transitive test dep, so `clojure -M:test -m kaocha.runner` in that
directory fails to load. The metrics extension tests pass when run via the main `tests.edn`
suite (which has the full classpath). No action required for this task.

## 2026-06-01 — implementation review (independent)

**Fix code correct and minimal.** `emit-tool-lifecycle!` bridge payload shapes verified
against `tool-runtime/core` lifecycle events (`:parsed-args`, `:content`, `:details`,
`:is-error`) and against metrics handlers (`on-tool-call` reads `:tool-name`; `on-tool-result`
reads `:tool-name` + `:is-error`). `(boolean (:is-error ...))` coercion correct. Single
responsibility, coherent with design.

**❌ Critical: the e2e test `metrics-extension-accumulates-tools-via-bridge-test` never runs.**
`tool_execution_test.clj` now `(:require [psi.metrics.extension :as metrics-ext])`, but
`extensions/metrics/src` is absent from kaocha's `:unit` suite `:source-paths` in `tests.edn`
(present only on the deps `:test` classpath). Empirically:
- `clojure -M:test --focus psi.agent-session.tool-execution-test` reports **11 tests**, not 12
  (documentation reporter shows the e2e deftest absent from output).
- `--focus …/metrics-extension-accumulates-tools-via-bridge-test` → "All 2616 tests were skipped"
  (the test ID is not in kaocha's plan).
The implementation note (c224ffa7d) claims this test "covers the full path … as a single
regression guard for the primary acceptance criterion" — but it does not execute under the
standard test command. The primary AC ("tool invocations appear in `:tools`") therefore has
**no executing end-to-end guard**. Either add `extensions/metrics/{src,test}` to the `:unit`
suite source-paths, or relocate the e2e test to the `:extensions`/metrics suite where the
classpath is correct.

**❌ Uncommitted work on a closed task.** Working tree has uncommitted changes:
`tool_execution_test.clj` (the e2e test) and `.clj-kondo/config.edn`. Task is in `closed/`
but its final test artifact is not in git. The `.clj-kondo/config.edn` change (new
`:discouraged-var` for `clojure.test/use-fixtures`) is out of scope for task 198.

**Lint regressions in `tool_execution_test.clj`** (clj-kondo, 3 warnings):
- `use-fixtures` referred but never used (line 5) — also trips the newly-added discouraged-var.
- `inline def` at line 379 (the e2e test ns body) — likely from the discouraged-var/def shape.

## 2026-06-01 — review follow-up execution

Executed the four follow-up items from the independent implementation review.

**e2e test runs (was the central concern).** The committed e2e test (`4630d40c0`)
executes and passes under the standard command:
`clojure -M:test --focus psi.agent-session.tool-execution-test` →
**12 tests, 61 assertions, 0 failures**, stable across repeated runs (verified twice in
the default capture+randomize mode and once in documentation mode). The review's "11 tests"
count is no longer reproducible. Mechanism check: under the full `:test` alias classpath the
namespace loads with all 12 deftests (`extensions/metrics/src` is on that classpath via
`deps.edn` `:test`), kaocha's `--print-test-plan` lists all 12, and the focused run executes
all 12. To make the `:unit` suite *self-consistent* rather than rely on incidental
`:test`-alias classpath ordering, added `extensions/metrics/src` to the `:unit` suite
`:source-paths` in `tests.edn`. `extensions/metrics/test` was intentionally NOT added — the
e2e test lives in `components/agent-session/test` (it exercises the agent-session adapter
bridge), already covered by the suite's existing `components/agent-session/test` path.

**Config and lint items moot.** The first working-tree draft I observed at the start of this
pass added a `use-fixtures` referral, a `#'metrics-ext/init` var, and an out-of-scope
`.clj-kondo/config.edn` `:discouraged-var` entry. That draft was superseded by the cleaner
commit `4630d40c0`, which: keeps `:refer [deftest testing is]` (no unused `use-fixtures`),
uses a quoted `'psi.metrics.extension/init` symbol (no inline-def warning), and touches no
kondo config. `clj-kondo --lint` on `tool_execution_test.clj` and `tests.edn` → 0 errors,
0 warnings. No revert needed; no warnings to fix.

## 2026-06-01 — implementation review (independent, verification pass)

**PASS — no new actionable issues.** Verified against live runtime and source, not just notes:

- Code (`emit-tool-lifecycle!`): minimal `case` bridge, single responsibility, coherent with
  design. Payload field names verified end-to-end against `tool-runtime/core` event shapes
  (`:event-kind`, `:tool-start`/`:tool-result`, `:parsed-args`, `:content`, `:details`,
  `:is-error`) and against metrics handlers (`on-tool-call` reads `:tool-name`; `on-tool-result`
  reads `:tool-name`+`:is-error`). `(boolean (:is-error …))` coercion correct.
- E2e test runs: `clojure -M:test --focus psi.agent-session.tool-execution-test` →
  **12 tests, 61 assertions, 0 failures**. The historically-contested "11 tests" gap is
  resolved; `metrics-extension-accumulates-tools-via-bridge-test` (line 379) executes.
- Error-counter AC covered: metrics `extension_test.clj` asserts `:is-error true` →
  `:errors` increment and `:is-error false` → no increment.
- Lint clean: `clj-kondo` on changed src + test → 0 errors, 0 warnings.
- Working tree clean; changelog `[Unreleased]` entry present (line 10).
- All prior review follow-ups (steps.md) ticked and independently confirmed.

No new patterns/abstractions/perf concerns. No regression tests missing. Implementation
complete and correct.

## 2026-06-01 — test review (independent, task-test-review skill)

**Tests well-formed; infra deps acceptable.** All three bridge/e2e tests in
`tool_execution_test.clj` use `with-redefs` only on infra/side-effect deps
(`execute-tool-runtime-in!`, `emit-tool-start-in!`, `emit-tool-end-in!`,
`record-tool-result-in!`) with canned nullable returns — not interaction-asserting
mocks. Assertions are on state/outputs (`@calls`, `@result-atom`, `@metrics-ext/store`),
not interactions. e2e test executes under the standard command: `--focus
psi.agent-session.tool-execution-test` → 12 tests, 61 assertions, 0 failures. `tests.edn`
metrics-src addition committed (`c00f4feda`); working tree clean.

**Gap: AC2 (error counter) has no end-to-end coverage through the bridge.**
Acceptance criterion 2 — "Tool errors (`:is-error true`) increment `:errors` counters" —
is verified only with the bridge bypassed. `metrics/extension_test.clj` exercises the
`:errors` increment by calling `fire-event` directly with `:is-error true`, and the bridge
test (`emit-tool-lifecycle-bridge-fires-extension-handlers-test`) only asserts
`(= false (:is-error result-event))` for a success result. No test wires an erroring
`run-tool-call!` (where `execute-tool-runtime-in!` returns `:is-error true`) through the
`emit-tool-lifecycle!` bridge and asserts `:errors` accumulates in the metrics store. The
bridge's `:is-error (boolean (:is-error lifecycle-event))` propagation
(`tool_runtime_adapter.clj:39`) on the truthy path is therefore unguarded — a regression
that drops or hardcodes `:is-error` on `tool_result` would not fail any test while the
success-path e2e test still passes. This is the same split-coverage class previously
closed for the invocation path, but the error path remains split.

## 2026-06-01 — test review (independent, task-test-review skill, confirming pass)

**Tests well-formed; infra deps clean; one known gap remains.** Re-applied the skill against
source, not just notes:
- `well_formed`: the three bridge/e2e tests
  (`emit-tool-lifecycle-bridge-fires-extension-handlers-test`,
  `tool-call-handler-block-ignored-on-interactive-path-test`,
  `metrics-extension-accumulates-tools-via-bridge-test`) are clearly structured with
  state/output assertions.
- `infra_deps`: `with-redefs` only on `tool-plan/execute-tool-runtime-in!`,
  `agent/emit-tool-start-in!`, `agent/emit-tool-end-in!`, `agent/record-tool-result-in!`,
  and `dispatch/dispatch!` (capture-wrapped, calls original) — all infra/side-effect deps
  with canned nullable returns. No mocks/stubs asserting interactions.
- `behaviour coverage`: AC1 (`:tools` invocations) covered e2e via
  `metrics-extension-accumulates-tools-via-bridge-test` (verified: `--focus
  psi.agent-session.tool-execution-test` → 12 tests, 61 assertions, 0 failures). AC2
  (`:is-error true` → `:errors`) still only covered with the bridge bypassed
  (`metrics/extension_test.clj` via `fire-event`); bridge `:is-error` propagation on the
  truthy path (`tool_runtime_adapter.clj:39`) is unguarded.

**No new actionable issues.** The AC2 error-path gap is already documented (note above,
`bfe2ea561`) and already has a matching unchecked follow-up in steps.md — not re-added to
avoid duplication.

## 2026-06-01 — test review (independent, test-shaper skill)

Re-applied test-shaper against source + live run, not just notes.

**AC2 error-path test now exists but is UNCOMMITTED.** The previously-documented AC2 gap is
addressed by `metrics-extension-accumulates-errors-via-bridge-test` in `tool_execution_test.clj`,
which drives `run-tool-call!` with `execute-tool-runtime-in!` returning `:is-error true`,
loads the real metrics ext, and asserts `@metrics-ext/store` accumulates `:tools "bash"
:invocations 1`, `:errors 1`, and the propagated error reason. This closes the split-coverage
gap: bridge `:is-error` propagation (`tool_runtime_adapter.clj`) is now guarded on the truthy
path. Verified: `clojure -M:test --focus psi.agent-session.tool-execution-test` →
**13 tests, 66 assertions, 0 failures**; `clj-kondo` clean. However the test is in the working
tree only — not committed. Until committed it does not survive the session boundary and the
task's git state still lacks the AC2 guard.

**Economy: two e2e metrics tests duplicate ~15 lines of identical ceremony.**
`metrics-extension-accumulates-tools-via-bridge-test` and
`metrics-extension-accumulates-errors-via-bridge-test` share verbatim setup: defonce-atom
reset in try/finally, identical `runtime-fns` map, and the `load-init-var-extension-in!` +
`with-redefs` infra scaffold. Per test-shaper (`minimal(incidental_variation)`,
`helpers_that_compress(ceremony) ∧ ¬helpers_that_hide(intent)`), this is a candidate for a
shared helper/macro (e.g. `with-metrics-ext-session`) that isolates the per-test intent
(`:is-error` value + store assertions). Minor; clarity issue, not correctness.

**Tests otherwise well-shaped.** All bridge/e2e tests: single concern, state/output
assertions (no interaction-mocking), deterministic (atoms reset in finally, infra redefs
canned), meaningful failure messages on each `is`. No further issues.

## 2026-06-01 — error-path e2e test added (AC2 split-coverage closed)

Added `metrics-extension-accumulates-errors-via-bridge-test` to
`tool_execution_test.clj`, mirroring the success-path e2e test but driving
`execute-tool-runtime-in!` to return `:is-error true`. The test loads the real
metrics extension on a session ctx, calls `run-tool-call!`, and asserts
`@metrics-ext/store` accumulates `:tools "bash" {:invocations 1 :errors 1 :error-reasons {…}}`.

This closes the split-coverage gap: previously the `:errors` increment was only
exercised via a direct `fire-event` (bridge bypassed), and the bridge's
`:is-error (boolean …)` propagation was only asserted on the success path. The
full error path (adapter → `emit-tool-lifecycle!` bridge → `on-tool-result` →
`counters/inc-tool-error` → store) is now a single executing regression guard.

Note on `:error-reasons`: the lifecycle `:tool-result` event carries the *shaped*
structured-block content (`[{:type :text :text "…"}]`), and `on-tool-result`
derives the reason via `(str content)`. The test asserts a single reason recorded
with count 1 and that the reason string includes the underlying message text,
rather than asserting an exact key — the stringified-block shape is the real
propagated value, not a defect.

Verification: `clojure -M:test --focus psi.agent-session.tool-execution-test` →
**13 tests, 66 assertions, 0 failures**. `clj-kondo --lint` → 0 errors, 0 warnings.

## 2026-06-01 — e2e ceremony deduplicated (test-shaper follow-up)

Extracted the shared e2e ceremony from the two metrics bridge tests into
`run-tool-call-through-metrics-ext!`. The helper resets the metrics `defonce`
atoms, builds the registry-only `runtime-fns`, loads the real metrics extension
on a fresh session ctx, and drives `run-tool-call!` under the infra `with-redefs`
scaffold (canned `execute-tool-runtime-in!` returning the caller-supplied
`runtime-result`; no-op `emit-tool-start-in!`/`emit-tool-end-in!`/`record-tool-result-in!`).

Each test now states only its per-test intent at the call site: the tool-call map,
the runtime-result (the `:is-error`/`:content` distinction is the whole point of the
two tests), and the store assertions. ~15 lines of verbatim setup removed from each
test body.

Design note on cleanup placement: the post-run `(reset! …)` stays in each test's
`finally`, not the helper. The helper returns *before* assertions run, and the
assertions read `@metrics-ext/store` — so the helper cannot tear down the store
without breaking the assertions. The split is clean: helper owns pre-run isolation,
test owns post-run cleanup. This satisfies test-shaper's
`helpers_that_compress(ceremony) ∧ ¬helpers_that_hide(intent)` — the intent (which
tool, what result, what counters) is fully visible at the call site.

The AC2-test-commit follow-up was already resolved (committed `ad7d0f975`) in a prior
pass; no uncommitted test existed at this pass's start (working tree was clean).

Verification: `clojure -M:test --focus psi.agent-session.tool-execution-test` →
**13 tests, 66 assertions, 0 failures**. `clj-kondo --lint` → 0 errors, 0 warnings.

## 2026-06-01 — test review (independent, task-test-review skill, verification pass)

Re-applied the skill against source + live run, not just notes. Skill criteria:
`well_formed ∧ (∀b∈behaviour(design). ∃t. covers(t,b)) ∧ (∀d∈infra_deps. injectable ∧ nullable ∧ ¬mock ∧ ¬stub)`.

**well_formed — ✓.** The three bridge/e2e deftests in `tool_execution_test.clj`
(`emit-tool-lifecycle-bridge-fires-extension-handlers-test`,
`tool-call-handler-block-ignored-on-interactive-path-test`,
`metrics-extension-accumulates-tools-via-bridge-test`,
`metrics-extension-accumulates-errors-via-bridge-test`) are single-concern, deterministic
(defonce atoms reset in `try/finally`, infra redefs canned), with meaningful failure
messages on each `is`.

**coverage — ✓ (AC2 gap now CLOSED).** All four acceptance criteria have executing tests:
- AC1 (`:tools` invocations) — `metrics-extension-accumulates-tools-via-bridge-test` (full
  path adapter→bridge→handler→store) + `emit-tool-lifecycle-bridge-fires-extension-handlers-test`.
- AC2 (`:is-error true` → `:errors`) — `metrics-extension-accumulates-errors-via-bridge-test`,
  committed `ad7d0f975`, drives `run-tool-call!` with `execute-tool-runtime-in!` returning
  `:is-error true` and asserts `@metrics-ext/store` accumulates `:tools "bash" :invocations 1`,
  `:errors 1`, and a propagated error reason. The previously-documented split-coverage gap
  (error path only via direct `fire-event`, bridge bypassed) is now closed and executing.
- AC3 (interactive blocking non-enforcement) — `tool-call-handler-block-ignored-on-interactive-path-test`.
- AC4 (clj-kondo clean) — verified 0 errors, 0 warnings on the changed test file.

**infra_deps — ✓.** `with-redefs` only on infra/side-effect deps
(`tool-plan/execute-tool-runtime-in!`, `agent/emit-tool-start-in!`, `agent/emit-tool-end-in!`,
`agent/record-tool-result-in!`, and capture-wrapped `dispatch/dispatch!` calling the original).
Canned nullable returns; no interaction-asserting mocks/stubs. Assertions target state/outputs
(`@calls`, `@result-atom`, `@metrics-ext/store`, recorded messages), never interactions.

**Verification.** `clojure -M:test --focus psi.agent-session.tool-execution-test` →
**13 tests, 66 assertions, 0 failures** (stable). `clj-kondo --lint` on the test file → 0/0.
Metrics ext's own `extension_test.clj` independently covers `inc-tool-error` / `:is-error`
handler behaviour. The earlier test-shaper economy observation (two e2e metrics tests share
~15 lines of ceremony) is a clarity refinement outside this skill's scope and already
documented above — not re-raised.

**No new actionable test issues under the task-test-review skill criteria.**

## 2026-06-01 — test review (independent, test-shaper skill, confirming pass)

Re-applied test-shaper against source (`tool_execution_test.clj`,
`tool_runtime_adapter.clj`, `metrics/extension_test.clj`) + live run — not notes.

- `simple ∧ consistent ∧ robust ∧ economical` — ✓. The four bridge/e2e deftests are
  single-concern, AAA-explicit, locally comprehensible; uniform `with-redefs` infra
  pattern; deterministic (defonce atoms reset in `try/finally`, canned infra redefs);
  meaningful per-`is` failure messages; e2e ceremony already deduplicated via
  `run-tool-call-through-metrics-ext!`.
- `behavior_focused` — ✓. Assertions target the fields consumers actually read
  (`:tool-name`, `:is-error`) and observable store state, never interactions.
- Considered `:input`/`:details` bridge-payload propagation as a candidate gap:
  **rejected as over-specification.** Metrics handlers read only `:tool-name`/`:is-error`
  (asserted) and `:content` (asserted indirectly via the error-reason test). Asserting the
  unconsumed `:input`/`:details` payload fields would assert implementation detail of the
  payload shape, violating test-shaper `¬assert(implementation_details)`. Not added.

Verification: `clojure -M:test --focus psi.agent-session.tool-execution-test` →
**13 tests, 66 assertions, 0 failures**. `clj-kondo --lint` on the test file → 0/0.

**No new actionable test issues.** All prior findings (AC1/AC2 e2e coverage, ceremony
dedup, block-ignored guard) are closed and confirmed executing.

## 2026-06-01 — docs review (independent, review-task-docs skill)

Applied the review-task-docs checklist to the user-facing docs surface for this fix.

**Changelog — ✓.** `CHANGELOG.md` `[Unreleased]` / `### Fixed` entry is present, accurate,
and consistent with the implementation: `.psi/metrics.edn`, `:tools` map, `psi/metrics`
extension, `on-tool-call`/`on-tool-result` handlers, and the interactive tool execution
path all match the code (`extensions/metrics/src/psi/metrics/extension.clj`,
`tool_runtime_adapter.clj`). Bug fix is user-visible → changelog correctly required and present.

**README / examples / removed-behaviour — ✓.** No README references to metrics; no stale
references; no examples to update; nothing removed.

**Actionable: `psi/metrics` is an activated built-in extension with zero user-facing docs.**
`.psi/extensions.edn` activates `psi/metrics {}` in this repo, and after this fix
`.psi/metrics.edn` actually populates the `:tools` map — i.e. metrics is now a real,
user-observable feature (counters at `worktree/.psi/metrics.edn` with `:tools {name
{:invocations :errors :error-reasons}}`). But `doc/extensions.md` "Built-in extensions in
this repo" catalog documents peer built-ins (`commit-checks`, `mcp-tasks-run`, workflow
surface) and omits `psi/metrics` entirely; no `doc/` or `README.md` mentions it. The fix
turned the extension from inert (`:tools` always `{}`) into a working feature without any
doc describing what `.psi/metrics.edn` is, where it lives, or what it records. This is a
documentation completeness gap surfaced by the behaviour change.

Severity note: arguably pre-existing (metrics was undocumented before too), but this task
is what makes the behaviour observable, so it is the natural point to document it. Recorded
as a follow-up.

## 2026-06-01 — docs review (independent, review-task-docs skill, verification pass)

Re-applied the review-task-docs checklist against source, not notes. The prior
docs-review follow-up (the `psi/metrics` entry in `doc/extensions.md`) is the only
user-facing doc change for this task; verified it for accuracy/completeness/consistency.

- **CHANGELOG — ✓.** `[Unreleased]/### Fixed` entry present and accurate: `:tools` map
  in `.psi/metrics.edn`, `on-tool-call`/`on-tool-result` handlers, interactive path — all
  match `extensions/metrics/src/psi/metrics/extension.clj` and `tool_runtime_adapter.clj`.
  User-visible bug fix → changelog correctly required and present.
- **`doc/extensions.md` `psi/metrics` entry — ✓ accurate against source:**
  - Subscribed events (6) match `init`'s `(:on api)` registrations exactly:
    `tool_call`, `tool_result`, `session_turn_finished`, `provider_request_started`,
    `provider_retry_scheduled`, `provider_request_finished`.
  - `:tools` shape `{:invocations :errors :error-reasons {reason :int}}` matches
    `schema/tool-counter-schema`; `:tokens`/`:providers`/`:workflows`/`:commands`/
    `:operations`/`:updated-at` all match `metrics-schema`.
  - Error-reason derivation ("first error line, trimmed/truncated") matches
    `on-tool-result` (`first (str/split-lines content)` → trim → subs ≤80).
  - Persistence ("atomic temp-file write; schema-validated on load; invalid logged and
    ignored") matches `persistence/save-metrics!` (tmp + `ATOMIC_MOVE`) and
    `load-metrics` (schema/valid? → WARN → nil).
  - `metrics/summary` operation and `/metrics` command both registered in `init`.
  - Activation note correct: `.psi/extensions.edn` activates `psi/metrics {}`.
  - Bridge note correctly attributes `:tools` population to `emit-tool-lifecycle!`
    (task 198) and notes the pre-fix always-`{}` state.
- **README / removed-behaviour / examples — ✓.** No README metrics references; no stale
  references; no examples needing update; nothing removed.

**No new actionable docs issues.** All prior docs findings are closed and confirmed.
Working tree clean; steps.md has no open follow-ups.

## 2026-06-01 — code review (independent, code-shaper skill)

Applied code-shaper (`simple ∧ consistent ∧ robust`) to the `emit-tool-lifecycle!`
bridge and the two disjoint paths firing the same extension bus events.

- **simple — ✓.** `emit-tool-lifecycle!` is single-responsibility (telemetry dispatch
  + extension bridge); the `case` is flow-control isolated from the per-branch payload
  construction. `(boolean (:is-error …))` coercion is correct; `case` default `nil`
  passes through non-bridged kinds.
- **`tool_call` shape — ✓ consistent.** Bridge payload
  `{:type :tool-name :tool-call-id :input}` matches `dispatch-tool-call-in` exactly.

**Actionable (consistency/robustness): the `"tool_result"` extension event has two
divergent payload shapes across the two disjoint paths.** The data-driven plan path
(`dispatch-tool-result-in`) fires `"tool_result"` with
`{:type :tool-name :tool-call-id :input :content :details :is-error}`, but the
interactive/batch bridge fires it with `{:type :tool-name :tool-call-id :content
:details :is-error}` — **`:input` is absent**. The same extension contract therefore
delivers different keys depending on which path triggered it. An untrusted-extension
`tool_result` handler that reads `:input` works on the plan path and silently receives
`nil` on the interactive path (`consistent(data_shapes)` violation;
`robust → enforceable(invariants)` weakened at the extension boundary).

This differs from the previously-considered-and-rejected `:input`/`:details` note
(that was scoped to *test assertions* of unconsumed fields). The concern here is the
cross-path contract divergence of the bus event itself, regardless of current
consumers. Structural cause: the `:tool-result` lifecycle event built in
`tool-runtime/core` (`record-tool-call-result!`) does not carry `:parsed-args`/args, so
the bridge cannot supply `:input` without threading the original args through the
result path. Resolution options: (a) thread `parsed-args` into the `:tool-result`
lifecycle event so the bridge can emit `:input`, unifying both paths' `tool_result`
shape; (b) explicitly document `:input` as plan-path-only on `tool_result` and align
`dispatch-tool-result-in` to drop it, making the contract uniform by removal; or
(c) declare divergence acceptable with a documented contract note. Recorded as a
follow-up.

## 2026-06-01 — code review follow-up: unify cross-path `tool_result` `:input`

Executed the open code-review follow-up (cross-path `"tool_result"` payload
divergence). Chose resolution (a) — addition over removal/documentation:

- `components/tool-runtime/src/psi/tool_runtime/core.clj` `record-tool-call-result!`:
  added `:parsed-args (:parsed-args tool-call)` to the `:tool-result`
  lifecycle event. The shaped result's `:tool-call` is the `prepared-tool-call`
  (carries `:parsed-args`) built in `execute-tool-call-prepared!`, so the data is
  present without threading new args through the result path.
- `components/agent-session/src/psi/agent_session/tool_runtime_adapter.clj`
  `emit-tool-lifecycle!`: the `:tool-result` bridge branch now emits
  `:input (:parsed-args lifecycle-event)`, matching `dispatch-tool-result-in`'s
  plan-path shape. Both paths' `"tool_result"` events now carry the same key set.

Why (a) over (b)/(c): (b) drop-`:input` would break the plan-path
`dispatch-tool-result-in` consumers and discard available data; (c) documenting
divergence leaves the silent-`nil` hazard for any handler reading `:input`.
Addition unifies `consistent(data_shapes)` and strengthens the extension-boundary
invariant without removing existing behaviour.

Test: extended `emit-tool-lifecycle-bridge-fires-extension-handlers-test` —
`tc` now carries `:parsed-args {:path "x"}`; asserts both `tool_call` and
`tool_result` bridge events deliver `:input {:path "x"}`. This is the regression
guard against the bridge dropping `:input` again.

Verification:
- `clojure -M:test --focus psi.agent-session.tool-execution-test` → 13 tests,
  68 assertions, 0 failures (was 66; +2 `:input` assertions).
- `clojure -M:test --focus psi.tool-runtime.core-test` → 6 tests, 27 assertions,
  0 failures (no regression from the lifecycle-event change).
- `clj-kondo --lint` on all three changed files → 0 errors, 0 warnings.

design.md updated: new "`tool_result` cross-path payload shape (unified)"
clarification records the chosen resolution. steps.md item checked.
