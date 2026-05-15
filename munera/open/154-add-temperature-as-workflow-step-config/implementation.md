# Implementation notes

## 2026-05-15 — follow-up execution after preloaded review

Verified the preloaded review result against task artifacts and referenced code/tests/docs.

Completed/confirmed as already landed:
- docs/changelog follow-ups present: `CHANGELOG.md`, `doc/workflow-grammar.md`, `doc/workflow-ir.md`
- IR validation coverage present in `components/workflow-runtime/test/psi/workflow_runtime/ir_test.clj`
- pipeline tests present for step-session-config, prompt-request, child-session-state, child-session-contract, target-ir-compiler, session-state model
- provider-layer temperature tests present for both OpenAI and Anthropic

Verification:
- `clojure -M:test --focus psi.workflow-runtime.ir-test --focus psi.workflow-step-session-config.core-test --focus psi.agent-session.prompt-request-test --focus psi.agent-session.child-session-state-test --focus psi.workflow-runtime.child-session-contract-test --focus psi.workflow-runtime.target-ir-compiler-test --focus psi.session-state.model-test --focus psi.ai.providers.anthropic-test --focus psi.ai.providers.openai-test`
- Result: 102 tests, 534 assertions, 0 failures

No newly added unchecked actionable steps remained to execute from the preceding review pass.

Also confirmed the user-provided task path `fix-workflow-max-iterations` does not resolve to any Munera task directory in this worktree. Used the preloaded review result plus current open-task inventory to identify the intended reviewed task as `munera/open/154-add-temperature-as-workflow-step-config`, whose newly added follow-up items were already complete before this pass.

## 2026-05-15 — task-implementation-review pass 2

**Overall**: Re-reviewed task artifacts plus referenced code/tests/docs. All previously recorded implementation, test, and documentation follow-ups are present and complete. No new actionable implementation-quality issues found.

**Issues found**

None. No new actionable feedback beyond the already-completed follow-up history in this task.

## 2026-05-15 — code-shaper follow-up execution

Added `anthropic-temperature-explicit-override-test` (new deftest after `build-request-no-thinking-test`):
- `testing "explicit temperature override flows through to request body"` — `{:temperature 1.0}` → body `:temperature` = 1.0
- `testing "absent temperature uses provider default (0.7)"` — no `:temperature` in options → body `:temperature` = 0.7
1 test, 2 assertions, 0 failures. Symmetric with `openai-temperature-defaults-to-zero-test` explicit-override block.

## 2026-05-15 — code-shaper review pass 1

**Overall**: Implementation is clean across all 8 pipeline layers. Guard-style asymmetry (`contains?` vs `some?`) is intentional and correct. One new provider-layer test gap found.

### Issues found

**[TEST] `anthropic_test.clj` — no explicit temperature override test** — `anthropic/build-request` uses `(or (:temperature options) 0.7)` for non-thinking, non-adaptive models. The existing test only confirms `(some? (:temperature body))` (presence when thinking is off). No test verifies that an explicit workflow-authored temperature (e.g. `1.0`) flows through to the Anthropic request body with the correct value. Symmetry with `openai-temperature-defaults-to-zero-test` (which has an explicit-override testing block) requires a matching Anthropic case.

### What is correct

- All guard styles are intentional: `contains?` at `step-session-config` and `statechart_runtime` (distinguishes absent from nil); `some?` at `context.clj` and `child_session_state` (nil temperature not stored — consistent with context.clj pre-dropping nil before child-opts).
- `select-keys` lists in `target_ir_compiler` are identical for session step and judge step — no drift.
- `(or (:temperature options) 0.7)` in Anthropic is correct: `0.0` is truthy in Clojure, so explicit `0.0` flows through correctly.
- All docs updated: CHANGELOG, `doc/workflow-grammar.md`, `doc/workflow-ir.md`.
- All previously identified follow-up items are complete.

## 2026-05-15 — test-shaper review pass 2

**Overall**: All previous follow-up items are complete. One residual signal gap found.

### Issues found

**[TEST] `openai_test.clj` `openai-temperature-defaults-to-zero-test` — name/body mismatch** — The test name asserts "defaults to zero" but the body only proves explicit override (`0.2`). The `(or (:temperature options) 0)` default path is not exercised within this test. The default-to-zero behavior is incidentally covered by an earlier broader completions test (line 568) but that test is not focused on this contract. A reader relying on the test name gets false confidence. Either add a `testing` block for the absent-temperature default case, or rename the test to `openai-temperature-explicit-override-test`.

### What is correct

- All test-shaper pass 1 items implemented: non-zero step-session-config case, model out-of-range rejection, child-session-contract comment.
- Temperature tests across all layers are single-concern, state-based, deterministic, no mocks.
- Coverage is symmetric: IR, model, step-session-config, prompt-request, child-session-state, target-IR-compiler, child-session-contract all have temperature tests.

## 2026-05-15 — task-implementation-review pass 1

**Overall**: Implementation is clean and consistent with how `logprobs`, `response-mode`, and `thinking-level` are threaded. The opt-in/absent-means-provider-default contract is correctly implemented.

### Issues found

**[DOC] CHANGELOG missing** — `:temperature` workflow step config is a user-visible authoring feature; no entry added to `[Unreleased]` in `CHANGELOG.md`. Per project convention, user-visible additions require a changelog entry.

**[DOC] `doc/workflow-grammar.md` not updated** — `session-config-entry` and `judge-session-config-entry` non-terminals reference `session-config-extension` / `judge-session-config-extension` as undefined placeholders; `:temperature` should be documented explicitly here alongside `:model`, `:tools`, `:skills`.

**[DOC] `doc/workflow-ir.md` not updated** — `session-spec` uses `session-extension*` as an undefined placeholder; `:temperature` (and `:thinking-level`, `:response-mode`, `:logprobs`, `:top-logprobs`) should be enumerated in the `session-spec` and `judge-session-spec` grammar entries.

**[TEST] No IR-layer schema validation tests for `:temperature`** — `session-spec-schema` now has `[:double {:min 0.0 :max 2.0}]` but there are no tests asserting that out-of-range values (e.g. -0.1, 2.1) are rejected and in-range values (e.g. 1.0) are accepted at the IR validation boundary.

**[MINOR] `child_session_state.clj` uses `(some? temperature)` while `statechart_runtime.clj` and `step-session-config` use `(contains? ...)` guard** — `some?` is semantically correct here because `context.clj` already drops nil temperature before passing `child-opts`. However the inconsistency in guard style across the pipeline is a minor readability issue. Not a correctness bug.

### What is correct

- `(or (:temperature options) 0)` in `openai/chat_completions` does not clobber explicit 0.0 — `0.0` is truthy in Clojure, so the authored value flows through correctly.
- `(contains? session-spec :temperature)` in `step-session-config` correctly distinguishes absent from explicit nil.
- `(some? temperature)` in `context.clj` correctly drops nil before building `child-opts`.
- Tests cover the two most important cases: absent (key not present) and explicit 0.0 (falsy double that must flow through).
- Schema range `[0.0, 2.0]` is consistent between IR and `session-state/model`.

## 2026-05-15 — test-shaper review pass 1

**Overall**: All test-review pass 1 follow-up items are complete. Tests are well-structured, use real state, no mocks. Three residual gaps found.

### Issues found

**[TEST] `workflow_step_session_config/core_test.clj` — only tests explicit 0.0 temperature** — `resolve-step-session-config-explicit-temperature-test` uses `0.0` (the falsy-double edge case) as the sole explicit value. No test for a non-zero temperature (e.g. 1.5) confirms the general propagation path independently of the edge case. The 0.0 test is correct and important, but a second case improves partition coverage.

**[TEST] `session_state/model_test.clj` — no out-of-range rejection test** — `agent-session-schema` has `[:double {:min 0.0 :max 2.0}]` (same range as IR schema) but the test only covers acceptance (1.0, 0.0, 2.0, absent). IR test has rejection cases; model test does not. Inconsistent coverage across symmetrical schemas.

**[DESIGN] `child_session_contract.clj` uses `[:maybe number?]` (no range)** — IR and session-state model both enforce `[:double {:min 0.0 :max 2.0}]`; the child-session contract schema uses `number?` with no range constraint. No test documents or calls out this asymmetry. Not a correctness bug (range is enforced upstream at IR), but the contract layer silently accepts out-of-range values.

### What is correct

- All test-review pass 1 items implemented: child-session-state, child-session-contract, target-ir-compiler, session-state model, prompt-request nil-temperature cases.
- Tests are single-concern, state-based, deterministic, no mocks.
- 0.0 falsy-double edge case explicitly tested and documented in child-session-state and step-session-config.

## 2026-05-15 — task-test-review pass 1

**Overall**: Acceptance-criteria tests (step-session-config + prompt-request layers) are well-formed, use real state, and cover the primary opt-in contract. Several intermediate pipeline layers added in the implementation have no temperature-specific tests.

### Issues found

**[TEST] `child_session_state_test.clj` — no temperature coverage** — `child-session-base-state` stores `:temperature` with `(some? temperature)` guard (plan step 6). No test verifies that a non-nil temperature is stored and a nil/absent temperature is dropped at this layer.

**[TEST] `child_session_contract_test.clj` — no temperature coverage** — `request-schema` accepts optional `:temperature` (plan step 4). No test validates schema acceptance/rejection of the temperature field at the contract boundary.

**[TEST] `target_ir_compiler_test.clj` — no temperature coverage** — `select-keys` includes `:temperature` for session and judge steps (plan step 2). No test verifies that temperature is preserved through IR compilation.

**[TEST] `session_state/model_test.clj` — no temperature coverage** — `agent-session-schema` accepts optional `:temperature` (plan step 7). No test validates schema acceptance of the temperature field.

**[TEST] `prompt_request_test.clj` — nil-temperature case untested** — `session->request-options` uses `(some? (:temperature session-data))` which drops nil. The absent-key test uses a map with no `:temperature` key; there is no test for `{:temperature nil}` (key present, value nil) confirming the key is also absent from options in that case.

### What is correct

- All design acceptance-criteria cases are covered at the two layers specified in the design.
- IR schema validation tests (in-range, out-of-range, nil, absent) are present and correct.
- No mocks or stubs used in any temperature tests.
