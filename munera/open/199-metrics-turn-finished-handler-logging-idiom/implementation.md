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
