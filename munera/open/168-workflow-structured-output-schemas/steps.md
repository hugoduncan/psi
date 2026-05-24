# Implementation steps

- [x] Locate current workflow grammar, IR normalization, runtime output storage, source-ref resolution, and judge execution code.
- [x] Add schema/validation support for structured session output entries under step-local `:outputs`.
- [x] Add schema/validation support for structured LLM judge output entries under judge-local `:outputs`.
- [x] Implement canonical structured-output result envelopes that retain raw output and record strategy/status/value/errors.
- [x] Implement fail-fast behavior for invalid structured output so downstream control flow cannot consume invalid values.
- [x] Update downstream source-ref resolution so `{:from {:step ... :output ...} :path [...]}` reads validated structured values and fails clearly on invalid/missing/non-structured cases.
- [x] Add the standard `:psi.workflow/judge-review-result` schema and representative example/test fixture.
- [x] Add focused tests for text-mode compatibility, valid structured session output, valid structured judge output, invalid output, downstream structured references, and provider strategy recording.
- [x] Update workflow grammar/IR/user docs for structured output authoring, runtime state, validation failure, downstream references, and provider strategy.
- [x] Run focused workflow tests and any broader practical verification.
- [x] Run a broader workflow/runtime test set after this first implementation slice.
- [x] Fix invalid session structured-output fail-fast handling so raw output and structured validation errors are recorded in the designed blocked envelope instead of escaping through `step-output-surfaces` as a generic failure.
