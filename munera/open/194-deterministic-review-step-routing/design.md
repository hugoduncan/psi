# Deterministic review-step routing

## Goal

Make `review-step` stop review loops deterministically when a review pass reports no actionable feedback. Downstream workflow control should use a deterministic status value produced by a deterministic operation, not an LLM/session judge.

## Problem

`review-step` currently runs:

```text
review → follow-up → review-status → maybe repeat
```

This has two failure modes:

1. `follow-up` runs even when `review` already ended with `PASS_STATUS: REVIEW_COMPLETE`, creating no-op follow-up commits.
2. `review-status` is an LLM/session control step. It can misclassify a literal `PASS_STATUS: REVIEW_COMPLETE` token as `REPEAT`, causing a no-actionable-steps loop.

The observed failure on task `188-openai-codex-native-structured-output` produced repeated no-op follow-up commits because the control step returned `REPEAT` despite review outputs containing `PASS_STATUS: REVIEW_COMPLETE`.

## Desired behavior

The workflow topology should be:

```text
review → deterministic-status
  DONE   → done
  REPEAT → follow-up → review
```

`follow-up` must not run unless the immediately preceding `review` pass reported actionable feedback.

## Scope

In scope:

- Update `.psi/workflows/review-step.edn` so status routing occurs immediately after `review`, before `follow-up`.
- Preserve the `review` actor step as a prose/text step that emits exactly one legacy `PASS_STATUS:` token in its final reply.
- Add deterministic parsing/routing for review status:
  - `PASS_STATUS: REVIEW_COMPLETE` → `DONE`
  - `PASS_STATUS: ACTIONABLE_FEEDBACK` → `REPEAT`
  - both tokens, missing token, or malformed `PASS_STATUS:` token → block/fail with an actionable diagnostic, never infer.
- Add the minimal deterministic constant loopback operation needed by the new topology, `workflow/constant-routing`, so `follow-up` can return to `review` without an LLM/session judge.
- Ensure routing is deterministic and does not use an LLM/session judge for this two-token decision or for the `follow-up` → `review` loopback.
- Preserve existing review skill behavior: review notes still go to `implementation.md`; actionable follow-up items still go to `steps.md`; follow-up pass still executes newly added unchecked steps.
- Add tests proving no-action review completion does not run `follow-up`.

Out of scope:

- Redesigning all review workflows (`review-task-design`, `review-task-plan`, `implement-task`) unless a minimal shared primitive naturally applies.
- Changing individual review skills beyond preserving the existing `PASS_STATUS:` prompt contract needed by `review-step`.
- Removing the legacy `PASS_STATUS:` token immediately; it is the authoritative actor-output contract for this slice unless a separate migration task replaces it with a proven multi-channel structured/prose mechanism.
- Requiring provider-native structured output from the same review actor response; current structured-output provider mechanisms do not prove an independent human-readable final reply can coexist with a native structured payload.

## Design

### 1. Review actor output contract

The `review` step remains a prose session actor step. It must produce the human-readable review note and end that same final reply with exactly one legacy token:

```text
PASS_STATUS: ACTIONABLE_FEEDBACK
PASS_STATUS: REVIEW_COMPLETE
```

This slice does **not** require `:review-result` as provider-native structured output from the same actor response. That requirement was removed because the current provider-native structured-output mechanisms constrain or extract the model's structured response and do not prove an independent prose final-reply surface that can also end with `PASS_STATUS:`. Prompted-JSON fallback is also unsuitable because it makes the final reply JSON-only.

The deterministic structured status for workflow control is therefore the output of `workflow/pass-status-routing`, not a second model-produced channel from the review actor. The operation parses the accepted `:final-llm-reply` and returns a structured operation result with `:data` equal to `"DONE"` or `"REPEAT"`.

Adding deterministic routing must not remove existing text surfaces used by follow-up context and status parsing. The compiled `review` actor step must continue to expose at least:

```clojure
:outputs {:final-llm-reply {:source :session/final-llm-reply}
          :transcript {:source :session/transcript}
          :result {:source :session/result}}
:yields {:type :text :text :final-llm-reply}
```

`{:from {:step "review" :yield :text}}` therefore remains equivalent to the review actor's final prose reply, and follow-up prompts can continue to include the review note plus legacy `PASS_STATUS:` token as context.

This supersedes the earlier design choice that required same-step `:structured-status` / `:review-result` mismatch validation. That validation is now explicitly out of this slice because it depends on a second model-produced status channel that this design no longer requires.

### 2. Deterministic status operation

Add a deterministic operation, e.g. `workflow/pass-status-routing`, registered by the built-in workflow runtime.

Input:

```clojure
{:text string}
```

`:text` is the review actor's accepted `:final-llm-reply`.

Output on success:

```clojure
{:status :ok
 :data "DONE" | "REPEAT"
 :summary "DONE" | "REPEAT"}
```

Output on error:

```clojure
{:status :error
 :reason :missing-pass-status | :ambiguous-pass-status | :malformed-pass-status
 :message string
 :details map}
```

Parsing rules:

- Parse only explicit `PASS_STATUS:` lines from `:text`; do not inspect task files or use an LLM.
- A pass-status line has the form `PASS_STATUS:` followed by the rest of that line after optional whitespace.
- The only known values are `REVIEW_COMPLETE` and `ACTIONABLE_FEEDBACK`; trailing non-whitespace text on the same line makes that line malformed.
- If exactly one `PASS_STATUS: REVIEW_COMPLETE` line is present and no other `PASS_STATUS:` lines are present, the route is `DONE`.
- If exactly one `PASS_STATUS: ACTIONABLE_FEEDBACK` line is present and no other `PASS_STATUS:` lines are present, the route is `REPEAT`.
- If more than one `PASS_STATUS:` line is present, error `:ambiguous-pass-status` even when the duplicate lines contain the same known value. Duplicate identical known tokens are not accepted because the actor contract says exactly one legacy token and accepting duplicates would hide prompt/response drift.
- If any `PASS_STATUS:` line is present but its value is not exactly one known value, error `:malformed-pass-status`. A known token plus an extra malformed `PASS_STATUS:` line is also `:ambiguous-pass-status` because multiple status lines make the route non-singleton; diagnostics should include the malformed line details.
- If no `PASS_STATUS:` line is present, error `:missing-pass-status`.
- Do not compare against model-produced structured status in this slice; there is no second model-produced status channel in `review-step`.
- `:status-mismatch` is not a valid error reason in this slice.

### 3. Workflow topology

`review-step.edn` should use deterministic invoke judges rather than an LLM/session status step:

```text
review --workflow/pass-status-routing--> DONE   → done
review --workflow/pass-status-routing--> REPEAT → follow-up
follow-up --workflow/constant-routing(REPEAT, max-iterations 6)--> review
```

Concrete target-authored shape:

- `review` remains a `:session` actor step. It has `:judge {:type :invoke ...}` that calls `workflow/pass-status-routing` with:
  - `:text {:from {:step "review" :output :final-llm-reply}}`.
- No `:structured-status` argument is wired in this slice. The deterministic operation result is the structured control value.
- `review :on` routes `"DONE"` to `:done` and `"REPEAT"` to `"follow-up"` without the review-loop `:max-iterations` guard. This transition only decides whether a follow-up pass is needed.
- `follow-up` remains a `:session` actor step and uses a deterministic invoke judge for the loopback, `workflow/constant-routing`, so successful follow-up execution routes back to `review`; this loopback must not inspect model prose.
- `workflow/constant-routing` is in scope for this task as a small built-in deterministic operation registered by the built-in workflow runtime alongside `workflow/pass-status-routing`. It accepts `{:route string}` and returns `{:status :ok :data route :summary route}`. If `:route` is missing or not a string, it returns `{:status :error :reason :invalid-route :message string :details map}` and is mapped through the same invoke-judge operation-error surface described below. `review-step` uses it with the literal `"REPEAT"` only.
- The existing review-loop max-iteration protection belongs on the `follow-up` → `review` loopback route, not on `review` → `follow-up`. Runtime iteration counts are keyed by the transition target step, so placing `:max-iterations 6` on the loopback preserves the prior behavior of limiting entries into `review` across repeated review passes. Placing the guard on `review` → `follow-up` would instead count follow-up executions and would not protect the review actor from an extra pass under the same semantics.
- The old standalone `review-status` session/LLM step is removed.

This shape requires runtime support for `:judge {:type :invoke ...}` execution. Invoke judges run after the actor result is recorded, call the deterministic operation registry, and feed the operation `:data` (`"DONE"` / `"REPEAT"`) into the existing routing-table evaluator. No child judge session or LLM turn is created for an invoke judge.

Invoke-judge operation errors must fail the workflow deterministically and must not execute `follow-up`. If `workflow/pass-status-routing` returns `{:status :error ...}`, the invoke judge records a judge result shaped for the existing judge/progression surfaces:

```clojure
{:judge-session-id nil
 :judge-output {:routing-result {:status :error
                                 :reason reason
                                 :message message
                                 :details details}}
 :judge-event nil
 :routing-result {:action :fail
                  :reason reason
                  :output-key :routing-result
                  :details {:operation "workflow/pass-status-routing"
                            :message message
                            :operation-result operation-result}}}
```

The statechart therefore records the judge output, terminal run status is `:failed`, terminal outcome is `{:outcome :failed :reason reason ...}`, and diagnostics surface the operation message/details. The routing-table evaluator is not called for operation errors, no `:no-match` retry path is used, and the `review` → `follow-up` transition is not taken.

### 4. Runtime support options

Implement invoke-judge support; do not introduce a separate LLM/session control step.

IR validation must preserve prior-step-only refs for normal contributions and invoke-step args. The only new exception is narrow and explicit: an invoke judge attached to step `S` may source refs from step `S`'s declared actor outputs because the judge executes after `S`'s actor result is accepted. The exception does not allow future refs, self refs from actor prompts, self refs from LLM judges, invoke-step self refs, or refs to undeclared output keys.

Runtime source resolution for an invoke judge attached to step `S` must evaluate same-step refs against `S`'s accepted actor result before executing the deterministic operation. For this task, the required same-step ref is only `{:from {:step "review" :output :final-llm-reply}}`. Same-step structured-output refs are not required for `review-step` in this slice.

## Acceptance criteria

1. `review-step` status routing is deterministic; no LLM/session step decides `REPEAT` vs `DONE` from `PASS_STATUS`.
2. `follow-up` is not executed when the review actor returns `PASS_STATUS: REVIEW_COMPLETE`.
3. `follow-up` is executed when the review actor returns `PASS_STATUS: ACTIONABLE_FEEDBACK`, then control returns to `review` through `workflow/constant-routing` with the existing max-iteration protection applied to the `follow-up` → `review` route so the guarded target remains the `review` step.
4. Missing, malformed, duplicate, or ambiguous `PASS_STATUS` output blocks/fails with an actionable diagnostic rather than looping or guessing.
5. The deterministic operation returns a structured status result for workflow routing; the `review` actor remains a prose actor that preserves `:final-llm-reply` / `:yield :text`.
6. Tests cover:
   - parser/operation maps `REVIEW_COMPLETE` → `DONE`
   - parser/operation maps `ACTIONABLE_FEEDBACK` → `REPEAT`
   - ambiguous/missing/malformed/duplicate tokens error
   - workflow execution skips `follow-up` on `REVIEW_COMPLETE`
   - workflow execution runs `follow-up` on `ACTIONABLE_FEEDBACK` and then returns to `review` via `workflow/constant-routing`
   - `workflow/constant-routing` returns the literal configured route and errors on invalid route input
   - IR/source-resolution tests prove an invoke judge may reference same-step declared actor outputs, while other self refs remain rejected
   - loader/definition test proves `review-step` uses deterministic routing, includes the deterministic constant loopback judge, and does not include the old LLM/session `review-status` step
7. Existing review workflows still load.

## Verification

Run focused tests for:

```bash
clojure -M:test --focus psi.workflow-loader.workflow-definitions-test
clojure -M:test --focus psi.agent-session.workflow-execution-test
clojure -M:test --focus psi.workflow-runtime.ir-test
```

Also run targeted lint for touched workflow/runtime namespaces.

## Notes from reverted prototype

A prototype demonstrated the likely shape but was reverted because this task is design-only for now. Useful findings:

- A pure parser namespace under workflow-runtime is a good fit for token parsing.
- Built-in workflow runtime needs an explicit deterministic operation registration path if `workflow/pass-status-routing` is registered at bootstrap.
- If invoke judges are introduced, the workflow runtime needs a deterministic execution path separate from `psi.agent-session.workflow-judge/execute-judge!`.
- Session actor steps that override `:outputs` must retain declared `:final-llm-reply` if downstream refs use `:yield :text`.
- A future model-produced structured review result should be designed only after proving a native provider/runtime mechanism that preserves independent prose final text plus structured payload; that future slice would reintroduce structured/text mismatch validation and tests.
