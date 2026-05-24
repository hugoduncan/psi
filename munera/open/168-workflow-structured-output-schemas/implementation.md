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
