# Implementation steps

- [ ] Locate current workflow grammar, IR normalization, runtime output storage, source-ref resolution, and judge execution code.
- [ ] Add schema/validation support for structured session output entries under step-local `:outputs`.
- [ ] Add schema/validation support for structured LLM judge output entries under judge-local `:outputs`.
- [ ] Implement canonical structured-output result envelopes that retain raw output and record strategy/status/value/errors.
- [ ] Implement fail-fast behavior for invalid structured output so downstream control flow cannot consume invalid values.
- [ ] Update downstream source-ref resolution so `{:from {:step ... :output ...} :path [...]}` reads validated structured values and fails clearly on invalid/missing/non-structured cases.
- [ ] Add the standard `:psi.workflow/judge-review-result` schema and representative example/test fixture.
- [ ] Add focused tests for text-mode compatibility, valid structured session output, valid structured judge output, invalid output, downstream structured references, and provider strategy recording.
- [ ] Update workflow grammar/IR/user docs for structured output authoring, runtime state, validation failure, downstream references, and provider strategy.
- [ ] Run focused workflow tests and any broader practical verification.
