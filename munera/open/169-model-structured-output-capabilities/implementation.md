# Implementation notes

## 2026-05-23 — ambiguity review

Found actionable ambiguity feedback: `plan.md` and `steps.md` are absent, so the implementation approach, sequencing, target files, and verification are not reviewable. The design also leaves the first OpenAI native mechanism/transport unresolved against the current AI API surface (`:openai-completions` and `:openai-codex-responses`, with no explicit public Responses transport), leaves strategy metadata propagation ambiguous for streaming vs non-streaming callers, and does not specify how Anthropic forced structured-output tool use composes with ordinary user tools/tool choice.

## 2026-05-23 — executed ambiguity follow-ups

Completed all newly added ambiguity follow-up items without executing implementation `steps.md` work. Created `plan.md` and `steps.md`; refined `design.md` to choose OpenAI Chat Completions JSON Schema `response_format` for explicit `:openai-completions` capabilities, defer public `/v1/responses`, keep `:openai-codex-responses` fallback-only, specify strategy metadata for non-streaming and streaming calls, and define Anthropic synthetic forced-tool composition/collision/extraction semantics. Marked all ambiguity `design-steps.md` items done.

## 2026-05-23 — inconsistency review

Reviewed `design.md`, `plan.md`, `steps.md`, `design-steps.md`, current AI provider/model files, and referenced docs for cross-artifact inconsistency. Found no new actionable inconsistency feedback; existing design, plan, and checklist consistently defer public OpenAI Responses/Codex native support, use OpenAI Chat Completions `response_format` first, model Anthropic forced-tool output, preserve fallback/local validation, and require explicit strategy metadata.

## 2026-05-23 — executed inconsistency follow-ups

No newly added unchecked `design-steps.md` items existed after the inconsistency review, so there were no actionable design follow-ups to execute. Did not execute implementation `steps.md` work.

## 2026-05-23 — ambiguity review repeat

Found actionable ambiguity feedback: the design does not specify the concrete AI result/event surface for provider-extracted structured payloads. In particular, Anthropic synthetic tool input must be hidden from ordinary tool-call output, but the task does not say where that payload appears for non-streaming results or streaming callers. The local-validation requirement is also ambiguous for this AI-only slice: the artifacts should state whether the AI component validates/coerces against the request schema itself or only returns extracted/raw structured payload plus strategy metadata for the workflow/runtime layer to validate in task 170.
