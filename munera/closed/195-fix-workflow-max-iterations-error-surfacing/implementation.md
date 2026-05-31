# Implementation Notes

## Review — 2026-05-15 (ψ, task-implementation-review)

**Overall**: Solid three-layer fix. Statechart compiler, runtime handler, and mutation error extraction are coherent. Tests pass (26 assertions statechart, 59 assertions canonical-workflows). Lint clean.

### Findings

1. **`terminal-outcome-error-message` NPE on nil `:reason`** — The `case` fallback calls `(name (:reason terminal-outcome))`. If a `terminal-outcome` map reaches this function with nil `:reason`, `(name nil)` throws NPE. In practice `:reason` is always set for failed runs, but the function accepts any map and the fallback is the defensive catch-all — it should be defensive itself. Severity: low (defensive edge).

2. **Empty-string `last-result-text` produces dangling header** — `when-let` binds `""` (truthy), producing `"\n\nLast result:\n"` with no content. Should use `not-empty` or `seq` guard. Severity: cosmetic.

3. **No test for `terminal-outcome` with nil `:reason`** — Tests cover nil outcome but not a map missing `:reason`. Adding one would document the defensive contract. Severity: low.

4. **`:iteration/exhausted` handler duplicates working-memory cleanup shape** — The `(swap! working-memory* ...)` block clearing `:pending-judge-result`, `:pending-routing`, `:updated-at` is identical to `:judge/record`'s cleanup. Could extract a shared helper. Severity: minor duplication, not blocking.

5. **Architecture match**: New code follows existing patterns — dispatch-action in statechart compiler, action handler in runtime, error extraction in mutation layer. No new abstractions or patterns introduced. ✓

6. **Consumer compatibility**: All `terminal-outcome` consumers (psi-tool projection, delegate, resolver, terminal-contract) treat it as opaque data or fall through gracefully for the new `:iteration-limit-reached` reason. ✓

## Follow-up execution — 2026-05-15 (ψ)

All 4 review follow-ups resolved:

1. **nil `:reason` guard** — `(name ...)` → `(some-> ... name)` in generic fallback case. Prevents NPE on malformed terminal-outcome maps.
2. **Empty `last-result-text` guard** — `when-let [text (:last-result-text ...)]` → `when-let [text (not-empty (:last-result-text ...))]`. No dangling "Last result:" header on empty string.
3. **nil `:reason` test** — New test case: outcome with `nil` reason asserts string result, contains step-id, no "null" string leakage. Canonical-workflows tests: 9 tests, 62 assertions, 0 failures.
4. **Shared cleanup helper** — Extracted `clear-pending-judge-state!` (private) in `statechart_runtime.clj`. Both `:judge/record` and `:iteration/exhausted` handlers now call it. All statechart tests: 25 tests, 113 assertions, 0 failures.

## Test review — 2026-05-15 (ψ, task-test-review)

**Overall**: Good unit coverage of the three layers. Statechart tests (26 assertions) verify action firing and routing guards. Canonical-workflow tests (62 assertions) cover `terminal-outcome-error-message` formatting for all reason branches, nil-outcome, nil-reason, and `run-failure-error` fallback chain. No mocks/stubs; infrastructure deps are injectable (ctx atoms). All tests pass.

### Findings

1. **`:judge/no-match` statechart path produces no `terminal-outcome`** — AC says `:judge-no-match` should produce actionable messages. `terminal-outcome-error-message` has a `:judge-no-match` branch. But the statechart's `:judge/no-match` event transitions to `:failed` without dispatching an action that records `terminal-outcome`. The `:judge-no-match` message branch is only reachable via `:judge/record`'s fallback (unknown routing action), not via the `:judge/no-match` statechart event. No test exercises the `:judge/no-match` → error-message end-to-end path. Severity: medium — this is the same class of silent-failure the task was designed to fix.

2. **No test for empty-string `last-result-text`** — The `not-empty` guard was added as a follow-up fix but has no dedicated test. The "without optional fields" test omits the key entirely (nil path), not the empty-string path. Severity: low — the guard is correct but undocumented by test.

3. **No test for >2000-char truncation** — The truncation branch in `terminal-outcome-error-message` has no test coverage. Severity: low — simple string logic, but unverified.

4. **No test for `[:failed]` vector form in `judged-routing-transition`** — Defensive guard for `(= target [:failed])` is untested. `compile-routing-transitions` only produces `:failed` keyword, so the vector path is never exercised. Severity: low — defensive code without a regression anchor.

5. **No integration test connecting handler output shape to message formatter** — Statechart test verifies action fires; mutation test verifies formatting on hand-crafted maps. No test verifies that the `:iteration/exhausted` handler's actual `terminal-outcome` map shape is consumable by `terminal-outcome-error-message`. Severity: low — the shapes are consistent today but could drift silently.

## Renumber (2026-05-31 audit)

Task id changed from `154-fix-workflow-max-iterations-error-surfacing` to `195-fix-workflow-max-iterations-error-surfacing` to resolve a Munera NNN collision (old number `154` was reused across concurrent branches). Slug and content unchanged; task remains open/active.

## Closure (2026-05-31)

Closed by 刀 decision. Core max-iterations error-surfacing fix is implemented (15 steps checked). The remaining unchecked items are deferred edge-case test coverage (empty/truncated last-result-text, `[:failed]` vector guard, `:judge/no-match` nil-error path); future issues will be tracked as new tasks rather than blocking closure.
