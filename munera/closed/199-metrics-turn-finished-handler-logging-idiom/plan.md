# Plan — 199 metrics turn-finished handler logging idiom

## Approach

Single-file behavioural-equivalence change plus one dependency declaration.
Replace the raw `println` swallowed-exception log in
`make-turn-finished-handler` with the project-standard `taoensso.timbre/warn`,
passing the exception `e` as timbre's structured first (Throwable) argument.

Implementation strategy follows the change_chain, but this task carries no spec
or test artifacts of its own (it is a pure logging-idiom alignment with an
unchanged behavioural contract):

- **meta/spec**: no new spec — the behavioural contract (exception swallowed,
  handler returns `nil`, success path unchanged) is unchanged. The design.md is
  the authoritative intent.
- **dependency**: add `com.taoensso/timbre {:mvn/version "6.8.0"}` to
  `extensions/metrics/deps.edn` `:deps`. This is required because the standalone
  `src` classpath has no timbre; the `:test` alias resolves it transitively
  (agent-session → statecharts → timbre, also `6.8.0`, so no version conflict).
- **code**: add `[taoensso.timbre :as timbre]` to the `psi.metrics.extension`
  ns `:require`; replace the `catch` body `println` with
  `(timbre/warn e "skipping token tracking for session" session-id)`.
- **verify**: confirm standalone `src` load and `:test` alias both resolve
  timbre; `clj-kondo` clean on the changed file; no token-tracking
  success-path or `nil`-return behaviour change.

Key decisions (already pinned by design, restated for execution clarity):

- Level is **`warn`** — pinned by the project-wide swallowed-non-fatal idiom,
  not inferred from in-file `println` siblings.
- Exception `e` passed **structurally** (first arg) to preserve the stack trace
  under timbre routing; `session-id` is a structured trailing arg; the
  hand-written `"DEBUG [psi/metrics]"` prefix is dropped (timbre supplies level
  + namespace).
- Version pin **`6.8.0`** matches the newest project declaration (state-kernel
  / root) and the transitive `:test`-classpath version — avoids conflict.
- Sibling `persistence.clj:47,51` `println "WARN [psi/metrics]"` sites are an
  **excluded counter-example** — out of scope.

## Risks

- **Dependency-resolution drift**: explicit `6.8.0` pin must match the
  transitive `:test`-classpath version to avoid a version conflict. Verified in
  the design inconsistency pass (both `6.8.0`); re-confirm at execution time.
- **Standalone vs test-alias divergence**: timbre is only transitive on
  `:test`; the explicit `:deps` entry is what makes the standalone `src` load
  work. Both paths must be checked — checking only `:test` would hide a missing
  standalone dep.
- **Output-routing behaviour shift (accepted, not a regression)**: switching
  from `println` (stdout, always) to `timbre/warn` (level-gated, routed) is the
  intended change; it is governed by the project log-min-level. No test asserts
  on the old stdout string, so no test breakage is expected.
- Low overall risk: single `catch`-branch line + one dep line; no control-flow
  or success-path change.

## Slice order

One vertical slice (the change is atomic and cannot be meaningfully decomposed
into independently shippable sub-slices):

1. **Slice 1 — timbre logging idiom**: add dep + require, replace the
   `println` with `timbre/warn`, verify resolution/lint/behaviour.

Documentation: no user-facing doc or CHANGELOG entry — this is an internal
idiom/refactor change (`¬user_visible`), per the changelog policy.
