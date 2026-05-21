# 160 — Implementation notes

(append-only)

## Review: ambiguity pass (design/plan/steps)

1. **Extension-path loading via mutations is unaddressed.** `load-startup-resources-via-mutations-in!` also calls `run-mutation-in!` for `extension-paths` (via `psi.extension/add-extension`). Design says "Extension loading via `ext/load-extension-init-in!` is already direct" — true only for `:init-var` extension-targets. The `extension-paths` loop also round-trips through mutations. Plan step 1 only converts templates/skills/tools. Production passes `extension-paths []` so low-risk, but the code path remains and the summary's `:mutations` vector includes `'psi.extension/add-extension`. Design/plan should state whether extension-path loading is converted to direct `ext-rt/add-extension-in!` or left as dead code.

2. **`add-extension` mutation is not dispatch-mediated.** Unlike template/skill/tool mutations that delegate to `dispatch/dispatch!`, `add-extension` calls `ext-rt/add-extension-in!` directly. The design frames the problem as "round-trips through the entire Pathom graph layer to perform what are direct dispatch calls" — this is accurate for templates/skills/tools but not for extension-paths. Clarify in design that extension-path loading is a Pathom round-trip to a direct runtime call (not dispatch).

3. **`:origin` for new direct dispatch calls is unspecified.** Mutations currently pass `{:origin :mutations}` to `dispatch!`. The replacement direct calls need an `:origin` value. Existing bootstrap dispatch calls use `{:origin :core}`. Plan should specify `:origin :core` (or `:bootstrap`) for the replacement calls.

4. **Dispatch return values are discarded — confirm intentional.** The current code uses `doseq` for template/skill/tool loops, discarding `run-mutation-in!` return values. The dispatch handlers return `{:return {:added? ... :count ...}}`. Plan should confirm the replacement direct dispatch calls also discard returns (they're only used for the final count read from session-data).

## Follow-up execution: ambiguity design-steps

All 4 design-steps completed:

1. **Extension-paths → direct `ext-rt/add-extension-in!`**: design.md scope item 2 added; constraints updated; plan.md Decisions documents rationale. The `add-extension` mutation wraps `ext-rt/add-extension-in!` with no dispatch — replacement calls the same function directly.
2. **Framing corrected**: design.md Intent now distinguishes template/skill/tool mutations (Pathom→dispatch round-trip) from `add-extension` mutation (Pathom→direct runtime call).
3. **`:origin :core`**: plan.md Decisions specifies `:origin :core` for replacement dispatch calls, consistent with existing bootstrap dispatch calls in `bootstrap-in!`.
4. **Return discard confirmed**: plan.md Decisions documents intentional `doseq` discard; steps.md step 1 updated with explicit note.

## Review: inconsistency pass (design/plan/steps)

1. **Dispatch function mismatch: `dispatch/dispatch!` vs `session/dispatch-in!`.** Plan says "direct `dispatch/dispatch!` calls" and steps say to add require `psi.agent-session.dispatch`. But `bootstrap-in!` exclusively uses `session/dispatch-in!` (from `psi.agent-session.core`, already required as `session`). The existing calls in `bootstrap-in!` — `:session/bootstrap-prompt-state`, `:session/refresh-system-prompt`, `:session/set-active-tools`, `:session/set-startup-bootstrap-summary` — all go through `session/dispatch-in!`. Using `dispatch/dispatch!` directly for the new template/skill/tool calls would be inconsistent with the same function's existing pattern. Plan/steps should specify `session/dispatch-in!` and drop the `psi.agent-session.dispatch` require addition.

2. **Missing require for `ext-rt`.** Steps say "Replace extension-path loop: `ext-rt/add-extension-in!`" but `bootstrap.clj` does not require `psi.agent-session.extension-runtime`. Steps list removing `psi.agent-session.mutations` and `psi.query.core`, and adding `psi.agent-session.dispatch`, but omit adding `psi.agent-session.extension-runtime` (as `ext-rt`). This require is needed for the extension-path replacement to compile.

## Follow-up execution: inconsistency design-steps

Both design-steps completed:

1. **Dispatch function → `session/dispatch-in!`**: plan.md approach and decisions updated to specify `session/dispatch-in!` instead of `dispatch/dispatch!`; removed the `psi.agent-session.dispatch` require addition from plan and steps; steps.md step 1 now uses `session/dispatch-in!` for all three resource loops; design.md acceptance criterion 2 updated. No new require needed — `psi.agent-session.core` already required as `session`.
2. **Added `ext-rt` require**: steps.md step 1 now lists adding `psi.agent-session.extension-runtime` (as `ext-rt`); plan.md decisions documents this require addition alongside the removals.

## Review: implementation review (pre-execution)

1. **Extension-path return key mismatch.** `bootstrap-in!` reads extension results using namespaced keys (`:psi.extension/loaded?`, `:psi.extension/path`, `:psi.extension/error`) — see lines 146–148 and 156. The mutation `add-extension` returns these namespaced keys. But `ext-rt/add-extension-in!` returns **unnamespaced** keys (`{:loaded? ... :path ... :error ...}`). Step 1 replaces the mutation call with direct `ext-rt/add-extension-in!` but doesn't address the key translation. The init-var path already manually constructs namespaced keys (lines 68–70), so it's unaffected. The extension-path replacement must either: (a) wrap `ext-rt/add-extension-in!` results to produce namespaced keys matching the init-var path shape, or (b) normalize both paths to unnamespaced keys and update `bootstrap-in!` to read unnamespaced keys. Option (a) is simpler and consistent with the existing init-var pattern.

## Follow-up execution: implementation review step 6

Resolved extension-path return key mismatch:
- Chose option (a): wrap `ext-rt/add-extension-in!` result to namespaced keys in step 1's extension-path replacement
- Pattern matches both the init-var path (lines 68–70) and the mutation it replaces (`mutations/extensions.clj` `add-extension`)
- Key detail: mutation uses input `path` param for `:psi.extension/path`, not result `:path` (which is the extension object); step 1 sub-item updated to use input `p`
- Updated: steps.md step 1 (extension-path sub-item with key translation), steps.md step 6 (marked done), plan.md (new decision bullet)

## Review: task-test-review (pre-execution)

Reviewed existing tests against design acceptance criteria. Key findings:

1. **No test exercises skill or tool registration through `bootstrap-in!`.** The only test that calls `bootstrap-in!` with non-empty resources is `startup-bootstrap-introspection-test` — it passes 1 template but `:skills []` and `:tools []` (via `:base-tools []`). After the mechanism change from mutation-mediated to direct dispatch, a shape mismatch in `:session/register-skill` or `:session/add-tool` dispatch event params would go undetected. The `model_dispatch_test` calls `bootstrap-in!` with no templates/skills/tools at all.

2. **No test asserts dispatch events for resource registration during bootstrap.** `interrupt-and-bootstrap-prompt-dispatch-test` checks the event log for `:session/bootstrap-prompt-state` but not for `:session/register-prompt-template`, `:session/register-skill`, or `:session/add-tool`. After the change, verifying that bootstrap produces the expected dispatch events (with `:origin :core`) would confirm the mechanism replacement works correctly.

3. **Step 3 test updates are necessary but insufficient.** Removing `:psi.startup/mutations` from the introspection test query/assertion is correct. But step 3 only removes — it doesn't add coverage for the new mechanism. The introspection test should continue to assert prompt-count = 1, and ideally also exercise non-zero skill-count and tool-count.

4. **Extension-path bootstrap path remains untested.** Design acknowledges production passes `extension-paths []`. The key translation wrapper (step 6 fix) is untested through bootstrap. The `extensions_io_test.clj` tests `ext-rt/add-extension-in!` directly but not the namespaced-key wrapping in the bootstrap path. Low priority given production usage, but noted.

5. **`bootstrap-in!` return shape not tested.** No test asserts on the return value of `bootstrap-in!`. The `:mutations` key removal from the return map (AC3) is only indirectly tested via the introspection resolver reading from session-data. The `app_runtime_bootstrap_test.clj` stubs `bootstrap-in!` entirely. Callers (`adopt-startup-plan-into-session!`) use `merge-startup-summary` — if the return shape changes unexpectedly, no test catches it at the bootstrap level.

## Follow-up execution: task-test-review steps 7 and 8

Both tests added to `model_dispatch_test.clj`:

1. **`bootstrap-resource-registration-test`** (step 7): calls `bootstrap-in!` with 1 template, 1 skill, 1 tool. Asserts all three appear in session-data/agent-ctx after bootstrap — prompt-templates, skills, and agent tools. Verifies resources by name. Covers the skill/tool registration gap identified in review finding 1.

2. **`bootstrap-dispatch-event-log-test`** (step 8): clears event log, calls `bootstrap-in!` with all three resource types, asserts `:session/register-prompt-template`, `:session/register-skill`, `:session/add-tool` events appear in the dispatch event log. Currently asserts `:origin :mutations` (matching current mutation-mediated path). Comment notes the origin should be updated to `:origin :core` after step 1 converts to direct dispatch. Covers review finding 2.

Both tests green, lint clean.

## Review: test-shaper pass

Applied test-shaper skill (clarity ∧ signal ∧ robustness ∧ economical) to all tests touching bootstrap.

1. **Redundant weak assertion in `bootstrap-resource-registration-test`.** `(pos? (count (:tools agent-data)))` is subsumed by the stronger `(some #(= "test-tool" (:name %)) (:tools agent-data))` assertion that follows. The weak assertion adds no signal — it passes even if the specific tool wasn't registered (e.g. default tools from `refresh-active-tools-in!`). Remove for clarity.

2. **`bootstrap-resource-registration-test` doesn't assert return summary counts.** The test verifies resources exist in session-data but discards `bootstrap-in!`'s return value. Adding `(let [summary (bootstrap/bootstrap-in! ...)] (is (= 1 (:prompt-count summary))) ...)` would close the return-shape coverage gap with minimal effort. This partially addresses the existing finding 5 (return shape untested) at the bootstrap level rather than only through introspection resolvers.

3. **Missing step: update `bootstrap-dispatch-event-log-test` origin from `:mutations` to `:core`.** Step 8 documents the intent ("update to `:origin :core` after step 1") but no unchecked step exists to perform the update. Step 3 scans for `:mutations` in the startup summary key — that won't catch the dispatch event `:origin :mutations` assertion. Without an explicit step, the test will fail after step 1 converts to direct dispatch (good — it's a mechanism-change detector) but the fix could be missed or done ad-hoc.

## Follow-up execution: test-shaper steps 9, 10, 11

All three steps completed in `bootstrap-resource-registration-test`:

1. **Step 9 — removed redundant weak assertion**: `(is (pos? (count (:tools agent-data))) ...)` removed; subsumed by the `(some #(= "test-tool" ...) ...)` assertion. Also flattened nested `let` into a single binding block (eliminated kondo redundant-let warning).

2. **Step 10 — return summary count assertions**: captured `bootstrap-in!` return value as `summary`; added three assertions: `(:prompt-count summary)` = 1, `(:skill-count summary)` = 1, `(:tool-count summary)` ≥ 1. Closes return-shape coverage gap at bootstrap level.

3. **Step 11 — explicit origin update step**: added step 12 to steps.md — update `bootstrap-dispatch-event-log-test` origin from `:mutations` to `:core` after step 1 executes. Separate from step 3's `:mutations` summary key scan.

## Review: code-shaper pass (pre-execution)

Applied code-shaper skill (simplicity ∧ consistency ∧ robustness) to bootstrap.clj, task artifacts, and referenced code.

1. **Typo in steps.md step 6 done-note: `:psi.parameter/path` should be `:psi.extension/path`.** The done-note for step 6 records `{:psi.extension/loaded? loaded? :psi.parameter/path p :psi.extension/error error}` — the middle key uses the wrong namespace `:psi.parameter` instead of `:psi.extension`. Plan.md correctly specifies `:psi.extension/path`. If the typo propagates into the step 1 implementation (copy-paste from the step 6 note), `bootstrap-in!` will silently fail to find `:psi.extension/path` in extension-path results, breaking error reporting in `ext-errors` (line ~139) and `extension-loaded-count` filtering (line ~143). The step 1 sub-item text is correct (`{:psi.extension/loaded? loaded? :psi.extension/path p :psi.extension/error error}`), so the risk is limited to copy-paste from the wrong location.

2. **`bootstrap-in!` docstring stale after rewrite.** The docstring step 2 says "load prompts/skills/tools/extensions via EQL mutations" — after the change this is wrong. No step addresses updating the docstring. Consistency requires the docstring to match the mechanism.

3. **`refresh-active-tools-in!` missing docstring.** Every other public fn in `bootstrap.clj` has a docstring. Pre-existing but the task rewrites this file — natural opportunity to add one for consistency.

## Follow-up execution: code-shaper steps 13, 14, 15

- **Step 13 — typo fix**: corrected `:psi.parameter/path` → `:psi.extension/path` in step 6 done-note. Prevents copy-paste propagation into step 1 implementation.
- **Step 14 — docstring update**: blocked — step 1 (the rewrite) has not executed yet; the current docstring accurately describes the current code. Must be done as part of or immediately after step 1.
- **Step 15 — `refresh-active-tools-in!` docstring**: added. Describes merging extension-registry tools into the active tool set. Lint clean.

## Review: task-test-review (post-execution)

Reviewed final test suite against design acceptance criteria and skill dimensions (well-formed, behaviour coverage, infra deps).

**All ACs covered:**
- AC1 (no Pathom/mutations): structural — verified by code inspection; `load-startup-resources-in!` has no Pathom references
- AC2 (direct dispatch): `bootstrap-resource-registration-test` (session-data), `bootstrap-dispatch-event-log-test` (event types + `:origin :core`)
- AC3 (`:mutations` removed): schema enforces absence; introspection test queries without it; return summary asserted in `bootstrap-resource-registration-test`
- AC4 (existing tests pass): 11 tests, 130 assertions, 0 failures in model_dispatch_test; 5 tests, 30 assertions in introspection
- AC5 (lint clean): 0 errors, 0 warnings

**Infra deps:** all bootstrap tests use real dispatch with `{:persist? false}` — no mocks/stubs in bootstrap-specific tests. `with-redefs` in model_dispatch_test only touches user-config persistence (unrelated to this task).

**Known gap (pre-existing, already documented):** extension-path bootstrap path untested through `bootstrap-in!` — production passes `extension-paths []`, low priority.

**No new actionable feedback.** Previous review passes (task-test-review pre-execution, test-shaper, code-shaper) identified and resolved all significant gaps.

## Review: test-shaper pass 2

Applied test-shaper skill (clarity ∧ signal ∧ robustness ∧ economical) to all bootstrap tests post-execution.

1. **`bootstrap-resource-registration-test` summary assertions incomplete.** Step 10 added assertions for `prompt-count`, `skill-count`, `tool-count` and claims to "close return-shape coverage gap". But the summary contains 7 fields; `extension-loaded-count`, `extension-error-count`, and `extension-errors` remain unasserted. Since the test passes `extension-paths []`, asserting `(= 0 (:extension-loaded-count summary))`, `(= 0 (:extension-error-count summary))`, `(= [] (:extension-errors summary))` is trivial and completes the return-shape contract. Without these, a regression that breaks extension summary construction goes undetected at the bootstrap level.

2. **`startup-bootstrap-introspection-test` queries `extension-error-count` but never asserts it.** The test queries 5 fields (`prompt-count`, `skill-count`, `tool-count`, `extension-loaded-count`, `extension-error-count`) but only asserts 4 — `extension-error-count` is silently dropped. Adding `(is (= 0 (:psi.startup/extension-error-count r)))` completes the query contract.

## Follow-up execution: test-shaper pass 2 steps 16 and 17

Both steps completed:

1. **Step 16 — extension summary fields in `bootstrap-resource-registration-test`**: added 3 assertions for `extension-loaded-count` (= 0), `extension-error-count` (= 0), `extension-errors` (= []). Completes the return-shape contract — all 7 summary fields now asserted.

2. **Step 17 — `extension-error-count` in `startup-bootstrap-introspection-test`**: added `(is (= 0 (:psi.startup/extension-error-count r)))`. All 5 queried fields now asserted.

Both tests green (11 assertions in bootstrap-resource-registration, 5 in startup-bootstrap-introspection). Lint clean.

## Review: code-shaper pass 2 (post-execution)

Applied code-shaper skill (simplicity ∧ consistency ∧ robustness) to final bootstrap.clj, resolvers, schema, and tests.

1. **Repeated state reads in `load-startup-resources-in!` return map (simplicity).** Lines 58–60 call `ss/get-session-data-in` twice and `ss/agent-ctx-in` + `agent/get-data-in` once to build the return counts. A single `let` binding for session-data and agent-data would eliminate the redundant lookups and improve local comprehensibility. Low-risk (counts are informational, state is settled after all dispatches), but easy to fix.

2. **Duplicate startup-bootstrap resolver logic across components (consistency).** `startup-bootstrap-resolver` (session.clj:542) and `startup-bootstrap-summary` (introspection/resolvers.clj:165) project identical output attributes with near-identical body logic. The introspection version uses a fragile session-lookup heuristic (`active-session` fallback to `first vals`) instead of the explicit `session-id` input the session version uses. Any future projection change must be applied in both places. Pre-existing but surfaced by this task touching both to remove `:mutations`. Follow-on: extract shared projection fn or have introspection delegate to the session resolver.
