# Steps

## Slice 1: Parser and deterministic operations

- [ ] Locate the built-in deterministic workflow operation registration path and the current operation result conventions.
- [ ] Add a pure `PASS_STATUS` parser that extracts only explicit `PASS_STATUS:` lines from supplied text.
- [ ] Implement parser success mapping for exactly one `PASS_STATUS: REVIEW_COMPLETE` line to route `"DONE"`.
- [ ] Implement parser success mapping for exactly one `PASS_STATUS: ACTIONABLE_FEEDBACK` line to route `"REPEAT"`.
- [ ] Implement parser error handling for missing `PASS_STATUS:` lines with reason `:missing-pass-status`.
- [ ] Implement parser error handling for malformed `PASS_STATUS:` values with reason `:malformed-pass-status`.
- [ ] Implement parser error handling for multiple `PASS_STATUS:` lines, including identical duplicates, with reason `:ambiguous-pass-status`.
- [ ] Add and register deterministic operation `workflow/pass-status-routing` that accepts `{:text string}` and returns operation success/error maps.
- [ ] Add and register deterministic operation `workflow/constant-routing` that accepts `{:route string}` and returns the literal route on success.
- [ ] Add `workflow/constant-routing` invalid-route handling for missing or non-string route input.
- [ ] Add focused tests for `workflow/pass-status-routing` success cases.
- [ ] Add focused tests for `workflow/pass-status-routing` missing, malformed, duplicate, and ambiguous token errors.
- [ ] Add focused tests for `workflow/constant-routing` success and invalid-route errors.

## Slice 2: Invoke-judge IR and source resolution

- [x] Identify workflow IR validation for judge definitions, invoke steps, and source refs.
- [x] Extend judge IR validation to accept `:judge {:type :invoke ...}` with deterministic operation name and args.
- [x] Add validation that invoke-judge operation args may reference prior-step outputs as existing refs do.
- [x] Add the narrow validation exception allowing an invoke judge attached to step `S` to reference `S`'s declared actor outputs.
- [x] Preserve rejection of actor-prompt self refs, LLM-judge self refs, invoke-step self refs, future refs, and undeclared output refs.
- [x] Add source-resolution support for same-step invoke-judge refs against the accepted actor result.
- [x] Add IR/source-resolution tests for the allowed same-step invoke-judge final-reply ref.
- [x] Add IR/source-resolution tests proving non-allowed self refs remain rejected.

## Slice 3: Invoke-judge runtime execution and failure surface

- [ ] Locate the workflow runtime path that executes LLM/session judges and applies routing-table decisions.
- [ ] Add an invoke-judge execution path that runs after the actor result is recorded and accepted.
- [ ] Resolve invoke-judge args from workflow refs before operation invocation.
- [ ] Invoke deterministic operations through the deterministic operation registry without creating a child judge session or LLM turn.
- [ ] Feed successful operation `:data` values into the existing routing-table evaluator.
- [ ] Map operation errors to recorded judge output containing `:routing-result {:status :error ...}`.
- [ ] Map operation errors to terminal workflow failure with the operation reason/message/details.
- [ ] Ensure operation errors do not trigger no-match retry behavior.
- [ ] Ensure operation errors do not execute `follow-up`.
- [ ] Add workflow execution tests for invoke-judge success routing.
- [ ] Add workflow execution tests for invoke-judge operation-error terminal failure diagnostics.

## Slice 4: Review-step topology migration

- [x] Update `.psi/workflows/review-step.edn` so the `review` step keeps prose/text behavior and declared `:final-llm-reply`, `:transcript`, and `:result` outputs.
- [x] Attach an invoke judge to `review` that calls `workflow/pass-status-routing` with `:text` from `review` `:final-llm-reply`.
- [x] Route `review` result `"DONE"` to workflow completion.
- [x] Route `review` result `"REPEAT"` to `follow-up`.
- [x] Remove the old standalone LLM/session `review-status` step from `review-step.edn`.
- [x] Attach an invoke judge to `follow-up` that calls `workflow/constant-routing` with literal route `"REPEAT"`.
- [x] Route `follow-up` result `"REPEAT"` back to `review`.
- [x] Move or preserve `:max-iterations 6` on the `follow-up` to `review` loopback so the guarded target remains `review`.
- [x] Confirm follow-up prompts still receive the review prose/yield context needed to execute actionable items.

## Slice 5: Workflow behavior and definition proofs

- [x] Add a workflow execution test where `review` returns `PASS_STATUS: REVIEW_COMPLETE` and `follow-up` is not executed.
- [x] Add a workflow execution test where `review` returns `PASS_STATUS: ACTIONABLE_FEEDBACK`, `follow-up` executes, and control returns to `review` via `workflow/constant-routing`.
- [x] Add workflow execution coverage for malformed/missing/duplicate `PASS_STATUS` output failing deterministically before `follow-up`.
  - [x] Align `components/agent-session/test/psi/agent_session/workflow_review_step_routing_test.clj` with the real built-in `workflow/pass-status-routing` behavior before adding malformed/duplicate workflow assertions, either by reusing built-in operation registration or by making `register-review-routing-ops!` return the parser's distinct `:malformed-pass-status` / `:ambiguous-pass-status` error results.
  - [x] Extend the focused review-step routing execution proof so malformed `PASS_STATUS:` values and duplicate `PASS_STATUS:` lines fail terminally with recorded invoke-judge diagnostics and never execute `follow-up`.
  - [x] Add focused workflow-level execution assertions for the distinct parser failure classes: malformed single `PASS_STATUS:` values should fail with `:malformed-pass-status`, while duplicate status lines should fail with `:ambiguous-pass-status`, and both paths must prove `follow-up` never executes.
- [x] Update `components/agent-session/test/psi/agent_session/workflow_review_step_routing_test.clj` so actionable-feedback execution asserts the post-fix runtime shape (`follow-up` attempt recorded and loopback progression visible) instead of the stale `:running`/no-follow-up expectation.
- [x] Update `components/agent-session/test/psi/agent_session/workflow_review_step_routing_test.clj` so missing-pass-status execution asserts terminal deterministic failure with recorded invoke-judge `:judge-output {:routing-result ...}` rather than the stale `:running`/nil-diagnostics expectation.
- [x] Add focused execution tests that prove invoke-judge operation success advances through the routing table (`DONE` completes, `REPEAT` enters `follow-up`) instead of merely allowing run creation.
- [x] Add focused execution tests that prove invoke-judge operation errors are recorded as judge-shaped deterministic failure diagnostics (`:judge-output {:routing-result ...}` plus terminal `:routing-result {:action :fail ...}`) and that `follow-up` is not executed on those failures.
- [x] Replace the prior same-step invoke-judge blocker proof with focused execution/validation coverage now that same-step invoke-judge refs are accepted.
- [x] Add loader/definition coverage proving `review-step` uses deterministic invoke routing after `review`.
- [x] Add loader/definition coverage proving `review-step` includes the deterministic constant loopback judge.
- [x] Add loader/definition coverage proving `review-step` no longer includes the old LLM/session `review-status` step.
- [x] Add or update tests proving existing review workflows still load.
  - [x] Add a focused loader/bootstrap proof that the full review workflow set still loads together without compilation errors, not just individual definition spot checks.
- [x] Update `components/agent-session/test/psi/agent_session/workflow_delegate_review_step_live_test.clj` so the built-in delegated `review-task-implementation` proof stubs the current `review-step` routing prompts/results and proves the routed review-step stack completes under built-in bootstrap after the deterministic `review` → invoke-judge → `DONE` topology change.
- [x] Extract or reuse a shared nullable-model/live-workflow test fixture seam so `workflow_delegate_review_step_live_test.clj` does not own bespoke model-registry/bootstrap scaffolding just to prove deterministic review-step routing under built-in `/delegate`.
- [x] Update user-facing docs for the deterministic `review-step` workflow routing change, including `CHANGELOG.md` because the workflow's externally visible behavior changed.

## Slice 6: Verification and cleanup

- [x] Run `clojure -M:test --focus psi.workflow-loader.workflow-definitions-test`.
- [x] Run `clojure -M:test --focus psi.agent-session.workflow-execution-test`.
- [x] Run `clojure -M:test --focus psi.workflow-runtime.ir-test`.
- [x] Run targeted `clj-kondo` lint for touched workflow/runtime namespaces.
- [x] Fix any focused test or lint failures caused by this task.
- [x] Append implementation notes with final decisions, changed files, verification commands, and any follow-up work.
