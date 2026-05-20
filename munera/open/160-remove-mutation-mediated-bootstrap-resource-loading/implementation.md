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
