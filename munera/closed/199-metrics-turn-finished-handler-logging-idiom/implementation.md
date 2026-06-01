# Implementation notes

## Design review — ambiguity pass (2026-06-01)

Reviewed `design.md` only (not plan/steps). Actionable ambiguities found:

- **Sibling-site reference contradicts timbre framing.** Acceptance pins the log
  level to be "consistent with sibling swallowed-error log sites", but the
  nearest siblings — same `psi/metrics` extension, `persistence.clj:47,51` —
  use raw `println` with hand-written `"WARN [psi/metrics]"` prefixes, *not*
  timbre. So the intent's `consistent(idioms)` is itself ambiguous: consistent
  with the project (→ timbre) or with the local file (→ println). Design does
  not say whether persistence.clj's println sites are the reference, an
  excluded counter-example, or also in-scope to convert.
- **timbre dependency not declared.** `extensions/metrics/deps.edn` declares
  only clojure + malli; it does NOT depend on `com.taoensso/timbre` (nor
  statecharts, the transitive source). The only timbre-using extension
  (`mcp-tasks-run`) declares timbre explicitly in its own deps.edn. Design says
  "add the require" but is silent on adding the dep, so the extension's `:test`
  alias / standalone load would lack timbre.
- **Level non-determinable as written.** Design says "choose debug or warn to
  match sibling sites", but the in-extension siblings carry no timbre level
  (they're println), so there is no in-extension timbre precedent to match —
  the disambiguation rule resolves to nothing.
- **Message/exception shape unspecified.** Whether to preserve the
  session-id + ex-message content and whether to pass the exception `e` as a
  structured timbre arg (e.g. `(timbre/warn e ...)`) vs string interpolation is
  not stated.

## Ambiguity follow-up execution (2026-06-01)

Resolved all four ambiguity items in `design.md` from codebase evidence:

- **Sibling contradiction → project-standard consistency.** `consistent(idioms)`
  resolved to the **project** idiom (timbre), not local-file. The
  `persistence.clj:47,51` `println "WARN [psi/metrics]"` sites are an **excluded
  counter-example** sharing the same drift; converting them stays out of scope.
  Reference idiom: swallowed-exception timbre sites in agent-session
  (`loader.clj`, `tool_output.clj`, `runtime.clj`).
- **timbre dep → must be added.** `extensions/metrics/deps.edn` `:deps` has only
  clojure + malli. Design now requires adding
  `com.taoensso/timbre {:mvn/version "6.8.0"}` (newest project decl, from
  `state-kernel`; `mcp-tasks-run` uses older 6.5.0). `:test` alias gets timbre
  transitively via the `agent-session` local dep (statecharts → timbre), but the
  standalone `src` load needs the explicit dep.
- **Level → pinned to `warn`.** No in-extension timbre precedent exists (siblings
  are `println`), so the disambiguation rule is the project-wide
  swallowed-non-fatal idiom = `warn`. Pinned outright in design.
- **Content contract → structured exception arg.** Preserve session-id +
  ex-message content; pass `e` as the structured first timbre arg
  (`(timbre/warn e "skipping token tracking for session" session-id)`), matching
  `runtime.clj:217` / `app_runtime.clj:222`. Drops the `"DEBUG [psi/metrics]"`
  prefix (timbre supplies level + ns).

All four design-steps marked done. No items left blocked.

## Design review — inconsistency pass (2026-06-01)

Reviewed `design.md` only (not plan/steps) for internal inconsistency and
inconsistency against referenced artifacts. Verified against the codebase:

- Code matches: `extension.clj:98-100` `println "DEBUG [psi/metrics] ..."` in the
  `catch`; ns requires lack timbre; `deps.edn :deps` = clojure 1.12.0 + malli only.
- Sibling sites match: `persistence.clj:47,51` `println "WARN [psi/metrics]"`.
- Version pin consistent: state-kernel + root pin timbre `6.8.0`; transitive
  timbre on the `:test` classpath is also `6.8.0` (no conflict with the proposed
  metrics `:deps` pin).
- Dep claims verified empirically: `:test` alias resolves timbre `6.8.0`
  transitively (agent-session → statecharts); standalone `src` classpath has no
  timbre — confirming the explicit-dep requirement.
- Reference idiom sites exist: `runtime.clj:217 (timbre/warn e "Extension run-fn
  failed")`, `app-runtime/.../app_runtime.clj:222 (timbre/warn e ...)`,
  `loader.clj:113`, `tool_output.clj:282`.
- Out-of-scope cross-ref valid: task `200-...` exists.

No new actionable inconsistency. Minor (non-actionable) note: the design cites
`app_runtime.clj:222` with a bare filename; the file lives in the `app-runtime`
component, not `agent-session` — but the cited site exists and supports the
structured-`e` claim, so it does not mislead an implementer.

## Implementation pass (2026-06-01)

Executed Slice 1 (the sole vertical slice). No deviations from the plan:

- `deps.edn`: added `com.taoensso/timbre {:mvn/version "6.8.0"}` to `:deps`.
- `extension.clj`: added `[taoensso.timbre :as timbre]` require; replaced the
  `catch` branch `println` with
  `(timbre/warn e "skipping token tracking for session" session-id)`.

Verification (all green):

- Standalone `src` classpath resolves `timbre-6.8.0.jar`.
- `:test` alias classpath resolves `timbre-6.8.0.jar` — same version, no conflict.
- `clojure -e "(require 'psi.metrics.extension)"` loads standalone without error.
- `clj-kondo --lint ...extension.clj` clean (0 errors, 0 warnings).
- `catch` branch re-read: no `println`/`"DEBUG"` prefix remains, handler outer
  `nil` return preserved, token-tracking success path untouched.

No spec/test artifacts added — behavioural contract unchanged (exception
swallowed, `nil` returned). No CHANGELOG/doc entry (internal `¬user_visible`
idiom alignment). Implementation complete pending review/closure.

## Implementation review — code pass (task-implementation-review, 2026-06-01)

Reviewed the committed change (`a9d814c37`) against design/plan/steps and the
codebase. **PASS — no new actionable issues.**

- **Matches design**: `catch` body is the lone
  `(timbre/warn e "skipping token tracking for session" session-id)`; `println`
  + `"DEBUG"` prefix removed; outer `nil` return + success path untouched.
  `deps.edn :deps` has `com.taoensso/timbre {:mvn/version "6.8.0"}`; ns requires
  `[taoensso.timbre :as timbre]` (alphabetical order).
- **Idiom corroborated**: Throwable-first + trailing structured-data-arg shape
  verified against `runtime.clj:217`, `app_runtime.clj:222`, and
  `tui_session_nav.clj:50` (`timbre/error e "Resume failed:" session-path`).
- **No new pattern / abstraction / perf issue**: single-line idiom alignment.
- **No orphaned require**: `clojure.string :as str` still used (str/join etc.).
- **Lint clean** (0/0); timbre `6.8.0` resolves on both standalone `src` and
  `:test` classpaths (no version conflict).
- **No test breakage**: no test asserts on the old stdout `"DEBUG ..."` string;
  the swallowing catch branch has no dedicated test, which is pre-existing and
  out of scope (no behavioural change).

No follow-up items added.

## Test review — task-test-review (2026-06-01)

Reviewed `extension_test.clj` (the relevant suite) against the design's
behavioural contract. **One actionable test gap.**

- **Well-formed / nullable-compliant** ✓: tests use
  `nullable/create-nullable-extension-api` and inject `query-session` as a plain
  fn — no mocks/stubs; assertions are state/output, not interactions. Satisfies
  `injectable ∧ nullable ∧ ¬mock ∧ ¬stub`.
- **Success-path coverage** ✓: `turn-finished-accumulates-...`,
  `...computes-delta-on-second-turn`, `...uses-unknown-when-model-id-nil`
  exercise `make-turn-finished-handler`'s happy path.
- **Gap — catch-branch contract untested**: design Acceptance pins the
  swallow-and-return-`nil` contract ("the exception remains swallowed and the
  handler still returns `nil`"), but **no test drives the `catch` branch**. The
  injection seam already exists: passing a `query-session` that throws would let
  a test assert (a) the handler returns `nil`, (b) no exception escapes, (c) the
  metrics store is unmodified. This is a trivial, nullable-friendly test of an
  explicit acceptance criterion — the prior implementation-review dismissed it
  as "pre-existing / out of scope" without noting how cheaply the existing seam
  covers it. The behaviour the task touched (the catch body) has zero coverage;
  a regression that broke swallowing (e.g. re-throwing) would not be caught.
  Actionable: add the catch-branch test.

## Test review follow-up execution (2026-06-01)

Added the catch-branch test flagged by the test-review pass:
`turn-finished-swallows-query-error-and-returns-nil-test` in
`extensions/metrics/test/psi/metrics/extension_test.clj`.

- Reuses the existing `make-api` + `:query-session` injection seam (no mocks):
  injects a `query-session` fn that throws `(ex-info "boom" {})`.
- Captures the registered `session_turn_finished` handler directly
  (`(first (get-in @state [:handlers "session_turn_finished"]))`) rather than
  via the `fire-event` helper, since the swallow-and-`nil`-return assertion
  needs the handler's return value (the helper discards it).
- Asserts (a) the handler returns `nil` despite the throwing query, and
  (b) `(:metrics @ext/store)` is identical before/after — token tracking is
  skipped, store unchanged. No exception escapes (the `is` forms would error
  otherwise).

Verification:
- Full Kaocha `:extensions` suite green: 228 tests, 788 assertions,
  0 failures, 0 errors (the new test is listed and passing).
- `clj-kondo --lint extension_test.clj` clean (0/0).
- `clj-paren-repair` applied after the edit (balanced/formatted).

Note: the standalone-directory `clojure -A:test` invocation cannot run this
suite — `psi.extension-test-helpers.nullable-api` transitively requires
`psi.prompt-registry.root-storage` → `psi/root_registry/registry`, which is
absent on the metrics-local `:test` classpath. This is pre-existing (every
test in the file errors the same way under that invocation) and unrelated to
this change; the canonical run path is the root `bb clojure:test:extensions`
suite, which passes.

No blocked items remain.

## Test review — re-pass (task-test-review, 2026-06-01)

Re-applied the skill's three criteria against the post-follow-up suite.
**PASS — no new actionable test issues.**

- **well-formed** ✓: suite green (228 tests, 788 assertions, 0 failures/0
  errors via `bb clojure:test:extensions`); `clj-kondo` clean on
  `extension_test.clj` (0/0).
- **behaviour coverage** ✓: the design's observable acceptance criteria are
  covered — success path (`turn-finished-accumulates-...`,
  `...computes-delta-on-second-turn`, `...uses-unknown-when-model-id-nil`) and
  the previously-flagged swallow-and-return-`nil` contract
  (`turn-finished-swallows-query-error-and-returns-nil-test`). The
  logging-mechanism specifics (timbre `warn` level, structured-`e` first arg)
  are correctly **not** asserted: doing so would require interaction assertions
  on the logger (violates `¬mock`) and the log mechanism is non-behavioural /
  `¬user_visible`.
- **¬mock/¬stub** ✓: `query-session` injected as a plain fn via the existing
  nullable seam; the throwing-fn double drives the error path without
  interaction assertions — assertions are on return value (`nil`) and store
  state, not on calls. Compliant with `injectable ∧ nullable ∧ ¬mock ∧ ¬stub`.
- **store-unchanged assertion is sound**: the injected query throws on the
  first `try`-body line, before any `swap!` to `:session-usage-cache` or
  `:metrics`, so asserting `(:metrics @ext/store)` is unchanged is a valid
  swallow proof.

No follow-up items added.

## Test review — test-shaper (2026-06-01)

Applied test-shaper (clarity ∧ signal ∧ robustness ∧ consistency ∧ economy) to
`extensions/metrics/test/psi/metrics/extension_test.clj`. Suite is well-formed,
deterministic, behaviour-focused, and `¬mock`-compliant. **Two actionable
shaping issues** (both `consistent ∧ minimal_incidental_setup`, neither a
correctness defect):

- **Incidental dead `:query-fn` setup (3 sites).** The three turn-finished
  tests do `(make-api {:query-fn (fn [_q] {})})` then `(assoc api :query-session
  …)`. Overriding `:query-session` directly bypasses `query*`/`:query-fn`
  entirely (see `nullable_api.clj`: `:query-session` is built from `query*`, but
  the `assoc` replaces it wholesale), so the `{:query-fn (fn [_q] {})}` opt is
  never exercised — it is incidental noise repeated verbatim across
  `turn-finished-accumulates-…`, `…computes-delta-on-second-turn`,
  `…swallows-query-error-and-returns-nil`, and `…uses-unknown-when-model-id-nil`
  (4 sites). Violates `minimal_incidental_setup`; drop to `(make-api)` + the
  `:query-session` `assoc`.
- **Two handler-invocation idioms.** Most tests invoke handlers via the
  `fire-event` helper (discards the return value); the catch-branch test reaches
  in via `(first (get-in @state [:handlers "session_turn_finished"]))` because
  it needs the handler's `nil` return. The divergence is justified but
  introduces a second invocation seam (`consistent(test_abstractions)` drift). A
  `fire-event` variant that returns the last handler result (or a single
  documented seam) would unify the idiom and let the catch-branch test use the
  same helper.

`helpers_that_compress` note (non-blocking): the four turn-finished tests repeat
the same `query-session-fn` + `make-api` + `assoc` + `init` arrange ceremony; a
small `init-with-query-session` helper would compress the ceremony without
hiding intent. Left as observation, not a required follow-up.

The logging-mechanism specifics (timbre `warn`, structured-`e` arg) remain
correctly unasserted (would require interaction assertions / is `¬user_visible`).

## Test-shaper follow-up execution (2026-06-01)

Executed both test-shaper follow-up items in
`extensions/metrics/test/psi/metrics/extension_test.clj`:

- **Dead `:query-fn` removed (4 sites).** Replaced
  `(make-api {:query-fn (fn [_q] {})})` with `(make-api)` in the four
  turn-finished tests. Confirmed against `nullable_api.clj`: `:query-session` is
  built from `query*` (the `:query-fn` override target), but each test
  `(assoc api :query-session …)` replaces it wholesale, so `:query-fn` was never
  on a live path — pure incidental noise. The `:query-session` `assoc` remains
  the sole live injection seam.
- **Single handler-invocation seam.** Extended `fire-event` to return the last
  handler's result (switched the `doseq` to a `reduce` seeded with `nil`),
  documenting that most callers discard the value. The catch-branch test
  (`turn-finished-swallows-query-error-and-returns-nil-test`) now asserts on
  `(fire-event state "session_turn_finished" …)` directly instead of reaching in
  via `(first (get-in @state [:handlers …]))`. All handler invocations in the
  suite now flow through `fire-event`. Behaviour of the existing discard-callers
  is unchanged (they ignore the new return value).

Verification (all green):

- `bb clojure:test:extensions`: 228 tests, 788 assertions, 0 failures, 0 errors.
- `clj-kondo --lint …extension_test.clj`: 0 errors, 0 warnings.
- `clj-paren-repair` applied (balanced/formatted).

No blocked items remain. Both test-shaper follow-up steps complete.

## Test review — test-shaper re-pass (2026-06-01)

Re-applied test-shaper (clarity ∧ signal ∧ robustness ∧ consistency ∧ economy)
to `extensions/metrics/test/psi/metrics/extension_test.clj` against the
post-follow-up state (`99121a290`). **PASS — no new actionable test issues.**

- Both prior test-shaper follow-ups are present and effective: the four
  turn-finished tests use `(make-api)` (dead `:query-fn` removed), and all
  handler invocations flow through the single `fire-event` seam (extended to
  return the last handler's result; the catch-branch test asserts on its `nil`
  return directly — no `[:handlers …]` reach-in remains).
- `simple ∧ behavior_focused ∧ deterministic ∧ ¬mock` all hold: nullable seam +
  injected plain fns; assertions on store state / return values, not
  interactions; no time/randomness/io/concurrency. Logging specifics
  (timbre `warn`, structured-`e`) correctly unasserted (`¬user_visible`).
- Suite green: `bb clojure:test:extensions` 228 tests, 788 assertions,
  0 failures/0 errors.
- Below-threshold observations (not raised as follow-ups): the
  `make-api` + `(assoc api :query-session …)` + `init` arrange ceremony repeats
  in 4 turn-finished tests, and the `metrics/summary` op-handler lookup
  `(first (filter #(= "metrics/summary" (:id %)) @ops))` repeats in 2 tests.
  The first was already weighed and explicitly left as a non-blocking
  observation by the prior test-shaper pass; the second (2 sites, clear) sits
  below the consolidation threshold the prior pass applied to the handler seam.
  Re-raising either would duplicate prior judgment.

No follow-up items added.

## Docs review — review-task-docs (2026-06-01)

Applied review-task-docs (README ∧ doc/ ∧ CHANGELOG accuracy ∧ completeness ∧
consistency). **PASS — no new actionable doc issues.**

- **New/changed behaviour**: change is `println` → `timbre/warn` in the metrics
  turn-finished catch branch. `doc/extensions.md`'s metrics section
  (`extension.clj` entry, ~L203) documents triggers, persistence, `metrics/summary`,
  `/metrics`, and the persisted `metrics.edn` shape — it does **not** document the
  internal swallowed-exception logging, so nothing needs adding (the swallow
  contract is unchanged: exception caught, `nil` returned).
- **Removed behaviour / stale refs**: the old `println "DEBUG [psi/metrics] ..."`
  line had no doc reference. `grep -rni "DEBUG \[psi"` / `psi/metrics` across
  `README.md`, `doc/`, `CHANGELOG.md` finds no stale mention of the removed
  println. The `println` occurrences in `doc/extensions.md` (L23,28,359,929) are
  illustrative *user-extension* example handlers, unrelated to the metrics
  extension's internal logging — not affected.
- **CHANGELOG**: design classifies this `¬user_visible` (internal idiom
  alignment). Consistent with the policy boundary
  (`{commands ∨ flags ∨ behaviours ∨ breaking ∨ bug_fix ∨ extension_capability}`):
  no command/flag/behaviour/bugfix/capability changes; the stdout→timbre
  output-routing shift is an internal logging-mechanism detail, not a documented
  behaviour. The no-CHANGELOG decision is sound — no entry required.
- **Examples / consistency**: metrics-section names + paths (`metrics.edn`,
  `worktree/.psi/metrics.edn`, `metrics/summary`, `/metrics`) remain accurate
  against `extension.clj`. No doc example references the changed logging.

No follow-up items added.

## Code review — code-shaper (2026-06-01)

Applied code-shaper (simple ∧ consistent ∧ robust) to the code under change:
the `make-turn-finished-handler` `catch` body in `extension.clj`, the
`taoensso.timbre` require, and the `deps.edn` `:deps` entry. **PASS — no new
actionable shaping issues.**

- **simple** ✓: `catch` body is the lone
  `(timbre/warn e "skipping token tracking for session" session-id)` —
  single responsibility (log + swallow), locally comprehensible, no control-flow
  branching; outer `nil` return + success path untouched.
- **consistent** ✓: argument order + idiom match the project Throwable-first +
  trailing-structured-arg convention — verified against
  `tui_session_nav.clj:50` (`timbre/error e "Resume failed:" session-path`),
  `runtime.clj:217` (`timbre/warn e "Extension run-fn failed"`), and
  `app_runtime.clj:222`. The `[taoensso.timbre :as timbre]` require is in
  alphabetical order within `:require`. `deps.edn` pins `6.8.0`, matching the
  project-wide declaration (state-kernel/root) and the transitive `:test`
  classpath version (no conflict).
- **robust** ✓: behaviourally equivalent (exception still swallowed, handler
  still returns `nil`); no orthogonality regression; the log mechanism is now
  enforceable under timbre level/routing control (the `println` bypass removed),
  and the structured `e` arg preserves the stack trace.
- `clj-kondo --lint extension.clj`: clean (0 errors, 0 warnings).

No follow-up items added.
