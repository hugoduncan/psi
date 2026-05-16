# Steps

- [x] IR schema: add `:temperature` to `session-spec-schema`
- [x] Target IR compiler: include `:temperature` in `select-keys`
- [x] Step session config: propagate `:temperature` with `contains?` guard
- [x] Child session contract: add `:temperature` to `request-schema`
- [x] Statechart runtime: propagate `:temperature` with `contains?` guard
- [x] Child session state: store `:temperature` with `some?` guard
- [x] Session state model: add `:temperature` to `agent-session-schema`
- [x] Prompt request: project `:temperature` into request options
- [x] Tests: `prompt_request_test` — absent + explicit cases
- [x] Tests: `workflow_step_session_config/core_test` — absent + explicit (0.0) cases

## Review follow-up

- [x] Add CHANGELOG entry under `[Unreleased] → Added` for `:temperature` workflow step config option
- [x] Add `:temperature` to `doc/workflow-grammar.md` `session-config-entry` and `judge-session-config-entry` non-terminals
- [x] Add `:temperature` to `doc/workflow-ir.md` `session-spec` and `judge-session-spec` non-terminals
- [x] Add IR-level validation tests: in-range accepted, out-of-range (-0.1, 2.1) rejected, nil accepted, absent accepted (`session-spec-schema-temperature-validation-test` in `ir_test.clj`) — 1 test, 7 assertions, 0 failures
- [x] Verify nil-means-absent consistency: `step-session-config` uses `contains?` (passes nil through), `context.clj` uses `(some? temperature)` (drops nil before child-opts), `child_session_state.clj` uses `(some? temperature)` (consistent with context.clj). Nil temperature is correctly absent from session state. No correctness issue.

## Test-review follow-up (pass 1)

- [x] `child_session_state_test.clj`: add temperature tests — non-nil stored, nil/absent dropped from base state
- [x] `child_session_contract_test.clj`: add temperature tests — `request-schema` accepts optional `:temperature`
- [x] `target_ir_compiler_test.clj`: add temperature tests — `:temperature` preserved through session/judge step compilation
- [x] `session_state/model_test.clj`: add temperature tests — `agent-session-schema` accepts optional `:temperature`
- [x] `prompt_request_test.clj`: add nil-temperature case — `{:temperature nil}` session-data does not inject `:temperature` into request options

## Test-shaper follow-up (pass 2)

- [x] `openai_test.clj` `openai-temperature-defaults-to-zero-test`: add a `testing` block for absent-temperature (no `:temperature` in options) confirming `build-request` emits `"temperature": 0` — or rename the test to `openai-temperature-explicit-override-test` if the default-zero case is considered sufficiently covered by the broader completions test at line 568

## Test-shaper follow-up (pass 1)

- [x] `workflow_step_session_config/core_test.clj`: add non-zero temperature test — authored `:temperature 1.5` on a step resolves to `1.5` in config (complements the 0.0 edge-case test with a representative non-edge value)
- [x] `session_state/model_test.clj`: add out-of-range rejection tests — `{:temperature -0.1}` and `{:temperature 2.1}` fail `valid-session?` (mirrors IR rejection tests; makes schema coverage symmetric)
- [x] `child_session_contract_test.clj`: add a comment (or test) documenting that the contract schema uses `number?` (no range) intentionally — range is enforced at IR; contract intentionally permissive

## Code-shaper follow-up (pass 1)

- [x] `anthropic_test.clj`: add explicit temperature override testing block to `build-request-no-thinking-test` (or a new `anthropic-temperature-explicit-override-test`) — confirm that `{:temperature 1.0}` in options produces `1.0` in the request body (symmetric with `openai-temperature-defaults-to-zero-test` explicit-override block)
