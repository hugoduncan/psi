# Implementation notes

## 2026-05-23 — ambiguity review

Found actionable ambiguity feedback: `plan.md` and `steps.md` are absent, so the implementation approach and executable checklist are not yet reviewable; the design also leaves the concrete IR/authored syntax unresolved between conceptual `:output` examples and existing workflow `:outputs` surfaces, and leaves the first standard schema/example choice underspecified.

## 2026-05-23 — ambiguity follow-up

Completed all ambiguity follow-ups: created `plan.md` and `steps.md`; resolved authored/IR syntax to extend existing step-local `:outputs` rather than introduce singular `:output`; chose `:psi.workflow/judge-review-result` as the first standard schema for reusable/tests/docs with no existing workflow migration in this slice.

## 2026-05-23 — inconsistency review

Found one actionable inconsistency: the task design/plan choose step-local `:outputs` for session steps and judge-local `:outputs` for LLM judges, but the referenced authored grammar currently exposes `outputs?` only on delegate steps and defines `outputs` only as delegate handoff. The implementation checklist has a broad docs item, but no explicit follow-up to align the grammar productions/nonterminal with the chosen session/judge structured-output surface.

## 2026-05-23 — ambiguity review repeat

Found one new actionable ambiguity: the design requires prompted JSON fallback plus Malli schemas using keyword enums, but does not specify the wire format/coercion boundary (JSON strings vs EDN keywords, parse format preference, coercion before validation, and failure recording when coercion cannot map values).

## 2026-05-23 — ambiguity follow-up repeat

Completed newly added ambiguity follow-ups only: aligned `doc/workflow-grammar.md` so session steps and LLM judges can declare structured `outputs?` beyond delegate handoffs, and specified prompted fallback as JSON wire format with schema-guided coercion into Malli-domain values before validation. Marked both design-steps done. Did not execute `steps.md` implementation items.

## 2026-05-23 — inconsistency review repeat

Found one new actionable inconsistency: `design.md`, `plan.md`, and `doc/workflow-grammar.md` now define session-step and LLM-judge structured outputs under `:outputs`, but the referenced user guide `doc/workflows.md` still describes `:output :handoff` as the only standardized structured export key and says the guide intentionally avoids a broader author-facing output menu. That guide wording conflicts with the chosen task contract and would mislead authors away from the new session/judge structured-output surface.

## 2026-05-23 — inconsistency follow-up repeat

Completed the newly added inconsistency follow-up: aligned `doc/workflows.md` with the structured-output contract by documenting session-step and LLM-judge structured `:outputs` as validated machine-facing data surfaces, adding representative `:path` reference examples, and pointing authors to grammar/IR docs for formal envelope, validation, and provider strategy details. Marked the design-step done. Did not execute `steps.md` implementation items.

## 2026-05-23 — ambiguity review repeat 2

Found one new actionable ambiguity: `doc/workflow-ir.md` remains generic about `:outputs` and does not specify the normalized IR shape for session structured outputs or judge-local structured outputs, even though the task design and user guide point implementers to IR docs for the formal envelope. This leaves implementers guessing where judge `:outputs` live in normalized IR and what runtime envelope/source metadata the IR validator should accept.

## 2026-05-23 — ambiguity follow-up repeat 2

Completed the newly added ambiguity follow-up: aligned `doc/workflow-ir.md` with the structured-output contract by documenting normalized session-step structured `:outputs`, judge-local structured `:outputs`, canonical structured-output runtime envelopes, provider strategy/coercion metadata, and downstream reference failure semantics. Marked the design-step done. Did not execute `steps.md` implementation items.

## 2026-05-23 — inconsistency review repeat 2

Found one new actionable inconsistency: judge structured outputs are described as judge-local data for transition evaluation, but the task docs also imply downstream `{:from {:step ... :output ...}}` references can read those judge-local fields. The source-ref grammar only has step output refs, and the task does not specify whether judge-local outputs are transition-context-only or promoted onto the parent step output surface.


## 2026-05-23 — inconsistency follow-up repeat 2

Completed the newly added inconsistency follow-up: specified judge-local structured outputs as transition-evaluation data only, not implicitly promoted through the parent step `{:step ... :output ...}` surface; aligned `design.md`, `doc/workflow-grammar.md`, `doc/workflow-ir.md`, and `doc/workflows.md`; marked the design-step done. Did not execute `steps.md` implementation items.

## 2026-05-23 — ambiguity review repeat 3

Found new actionable ambiguity feedback: the task allows one or more structured `:outputs`, but does not define how a single model/judge response maps to multiple structured output keys in prompted JSON/provider-native modes; it also names the first reusable schema without specifying its code ownership/export surface for `:schema-id` reuse.


## 2026-05-23 — ambiguity follow-up repeat 3

Completed newly added ambiguity follow-ups only: specified one structured-output key per session step or LLM judge, with one raw response mapping to one prompted-JSON/provider-native structured envelope; specified workflow-runtime ownership for reusable schema ids, including `psi.workflow-runtime.structured-output-schemas` and `:psi.workflow/judge-review-result` version 1. Aligned design and workflow docs. Marked both design-steps done. Did not execute `steps.md` implementation items.

## 2026-05-23 — inconsistency review repeat 3

Found one new actionable inconsistency: `doc/workflow-ir.md` documents LLM judges as able to declare judge-local structured `:outputs`, and the design/grammar/user guide agree, but the same IR doc's compact suggested grammar still defines `llm-judge` without `outputs?`. That internal mismatch would lead implementers using the grammar block to reject the judge-local structured output surface described elsewhere.

## 2026-05-23 — inconsistency follow-up repeat 3

Completed the newly added inconsistency follow-up: aligned `doc/workflow-ir.md` suggested documentation grammar with the documented judge-local structured `:outputs` contract by adding `outputs?` to the `llm-judge` production. Marked the design-step done. Did not execute `steps.md` implementation items.

## 2026-05-23 — implementation slice

Implemented the first runtime structured-output slice: runtime-owned reusable `:psi.workflow/judge-review-result` schema, canonical prompted-JSON parse/coerce/validate envelopes, IR schema/semantic validation for session and judge `:outputs`, session-step fail-fast blocked result on invalid structured output, judge-local structured output routing via validated `:decision`, and downstream source refs that expose only valid structured `:value` fields. Added focused structured-output, source-resolution, and structured judge tests. Focused verification green: `clojure -M:test --focus psi.workflow-runtime.ir-test --focus psi.workflow-runtime.structured-output-test --focus psi.workflow-step-materialization.structured-source-resolution-test --focus psi.agent-session.workflow-judge-test`.

## 2026-05-23 — broader verification pass

Completed the remaining broad verification step after the first structured-output implementation slice. Ran `bb clojure:test:unit`; all unit tests passed, covering workflow runtime/loader/materialization/judge namespaces along with the broader unit suite. No implementation deviations discovered.

## 2026-05-23 — implementation review

Found one new actionable implementation issue: invalid session structured output currently calls `workflow-ir/step-output-surfaces` before the fail-fast blocked envelope is built, so `step-output-value` throws on the invalid result and `execute-actor-step!` degrades the step to a generic failure without preserving the designed blocked payload/raw structured-output errors.

## 2026-05-23 — implementation-review follow-up

Completed the newly added implementation-review follow-up: invalid session structured output now bypasses logical output surface normalization before blocking, preserving the raw assistant text and canonical invalid structured-output envelope in the blocked payload instead of being caught as a generic execution failure. Added regression coverage in `psi.workflow-runtime.statechart-runtime.step-execution-test`. Focused verification green: `clojure -M:test --focus psi.workflow-runtime.structured-output-test --focus psi.workflow-runtime.statechart-runtime.step-execution-test --focus psi.workflow-runtime.ir-test --focus psi.workflow-step-materialization.structured-source-resolution-test --focus psi.agent-session.workflow-judge-test`.

## 2026-05-23 — test review

Found new actionable test feedback: the implemented structured-output tests cover valid/invalid envelopes, invalid session fail-fast, one valid downstream path, and valid judge routing, but they do not yet prove all testing requirements from the task. Missing coverage includes text-mode compatibility, IR rejection of multiple structured-output entries and reusable schema mismatches, invalid structured judge fail-fast behavior, semantically negative-but-schema-valid judge routing, and source-ref failures for missing path/non-structured source outputs.

## 2026-05-23 — test-review follow-up

Completed newly added test-review follow-ups: added text-mode session compatibility coverage, IR semantic validation tests for multiple structured outputs and reusable schema mismatch, structured judge invalid-local-failure and schema-valid `:needs-work` branch coverage, and downstream structured source-ref missing-path/non-structured-output coverage. Tightened structured source path resolution to fail clearly on missing paths for structured outputs and on path traversal against scalar declared step outputs, while preserving legacy nil behavior for unstructured missing nested collection paths. Focused verification green: `clojure -M:test --focus psi.workflow-runtime.ir-test --focus psi.workflow-runtime.statechart-runtime.step-execution-test --focus psi.workflow-step-materialization.structured-source-resolution-test --focus psi.agent-session.workflow-judge-test`.

## 2026-05-23 — test-shaper review

Found one new actionable test-shaping issue: prompted JSON tests cover malformed JSON and schema-invalid objects, but not syntactically valid non-object JSON. The single-JSON-object boundary should have a focused boundary test proving arrays/scalars are rejected with an invalid envelope rather than treated as schema failures or valid structured values.

## 2026-05-23 — test-shaper follow-up

Completed the newly added structured-output boundary test: syntactically valid non-object JSON arrays and scalars are rejected at the single-JSON-object envelope boundary with `:status :invalid`, parsed value retained for debugging, parse-error recorded, and no exposed `:value`. Focused verification green: `clojure -M:test --focus psi.workflow-runtime.structured-output-test`.

## 2026-05-23 — code-shaper review

Found one new actionable code-shaping issue: structured-output source/result predicates now exist in both workflow-runtime and workflow-step-materialization namespaces, duplicating the canonical source set and validity check across component boundaries. This drift-prone duplication should be consolidated into one shared lower helper or an explicit adapter so structured-output semantics have a single owner.
