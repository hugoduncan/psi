# Plan

## Approach

Implement deterministic review-step routing as a small vertical slice: pure parser and deterministic operations first, invoke-judge runtime support second, workflow topology third, then focused proofs.

Key decisions:

- Keep the `review` step as a prose session actor. Its accepted `:final-llm-reply` remains the authoritative source and must contain exactly one `PASS_STATUS:` line.
- Add `workflow/pass-status-routing` as a deterministic operation that parses only explicit `PASS_STATUS:` lines and returns route data `"DONE"` or `"REPEAT"`.
- Add `workflow/constant-routing` as a deterministic operation for literal non-LLM loopback from `follow-up` to `review`.
- Add invoke-judge execution for `:judge {:type :invoke ...}` so deterministic operations can produce routing values without creating a child judge session or LLM turn.
- Allow only the narrow same-step source-reference exception needed by invoke judges: an invoke judge attached to step `S` may read declared actor outputs from `S` after the actor result is accepted.
- Update `.psi/workflows/review-step.edn` to remove the old LLM/session `review-status` step and route `review -> DONE|REPEAT` before `follow-up` runs.
- Put the existing max-iteration protection on `follow-up -> review`, preserving the guarded target as the `review` step.

## Risks

- Invoke-judge support touches workflow runtime routing and source-resolution paths; overly broad self-reference allowance could weaken IR validation.
- Existing workflow execution/result surfaces may assume judge results come from session/LLM judges; invoke-judge errors must be shaped compatibly for history, diagnostics, and terminal failure.
- `review-step.edn` must preserve existing review-note and follow-up context behavior while changing control topology.
- Parser diagnostics must be strict enough to avoid silent loops but clear enough to make malformed review output actionable.

## Slice order

1. **Parser and deterministic operations**
   - Add pure `PASS_STATUS` parsing with strict singleton-token cardinality.
   - Register `workflow/pass-status-routing` and `workflow/constant-routing` in the built-in deterministic operation surface.
   - Prove success and error cases with focused unit tests.

2. **Invoke-judge IR and source resolution**
   - Extend workflow IR/validation to accept `:judge {:type :invoke ...}`.
   - Add the same-step declared actor-output reference exception only for invoke judges.
   - Prove allowed same-step invoke-judge refs and rejected non-allowed self refs.

3. **Invoke-judge runtime execution and failure surface**
   - Execute invoke judges after accepted actor results.
   - Resolve invoke args, call the deterministic operation registry, and feed successful `:data` into existing route evaluation.
   - Map operation errors to deterministic judge output and terminal workflow failure without no-match retries or follow-up execution.

4. **Review-step topology migration**
   - Update `.psi/workflows/review-step.edn` so `review` invokes `workflow/pass-status-routing` and routes `DONE -> done`, `REPEAT -> follow-up`.
   - Update `follow-up` to use `workflow/constant-routing` with literal `"REPEAT"` and `:max-iterations 6` on the loopback to `review`.
   - Remove the old LLM/session `review-status` step while preserving `review` text outputs/yields.

5. **Workflow behavior and definition proofs**
   - Add workflow execution tests for no-action completion skipping `follow-up` and actionable feedback executing `follow-up` before looping.
   - Add loader/definition tests proving deterministic routing, constant loopback, and absence of the old LLM/session status step.
   - Verify existing review workflows still load.

6. **Verification and cleanup**
   - Run focused workflow definition, execution, and IR tests.
   - Run targeted lint for touched workflow/runtime namespaces.
   - Update implementation notes with decisions, verification, and any follow-up discovered during implementation.
