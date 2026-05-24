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


## 2026-05-23 — executed ambiguity follow-ups repeat

Completed the newly added ambiguity follow-ups without executing implementation `steps.md` work. Updated `design.md` to define the provider-extracted structured payload surface as `[:structured-output :payload]` for non-streaming results and `:structured-output-result` streaming events or equivalent captures, with Anthropic synthetic `tool_use` input hidden from ordinary assistant tool calls. Clarified the AI/workflow validation boundary: AI adapters select native strategy, build requests, extract raw/provider payloads, and report metadata; workflow/runtime validation remains the final authority before downstream structured values are exposed. Updated `plan.md` and `steps.md` to reflect the payload surface and validation boundary. Marked the ambiguity `design-steps.md` items done.

## 2026-05-23 — inconsistency review repeat

Reviewed `design.md`, `plan.md`, `steps.md`, `design-steps.md`, referenced AI provider/model files, and provider docs for cross-artifact inconsistency after the payload-surface and validation-boundary clarifications. Found no new actionable inconsistency feedback. The artifacts consistently keep OpenAI provider-native support limited to explicit `:openai-completions` Chat Completions `response_format`, keep Codex fallback-only, model Anthropic forced synthetic tool extraction separately from ordinary tool calls, expose explicit strategy/payload metadata, and leave final Malli validation authority to workflow/runtime callers.

## 2026-05-23 — executed inconsistency follow-ups repeat

No newly added unchecked `design-steps.md` items existed after the repeated inconsistency review, so there were no actionable design follow-ups to execute. Did not execute implementation `steps.md` work.

## 2026-05-23 — ambiguity review repeat 2

Found one new actionable ambiguity: the artifacts say AI adapters do not final-validate/coerce provider payloads, but the testing requirements still say "local validation is still invoked after provider response extraction." For this provider/model slice, the task should specify whether implementation only proves the AI result preserves extracted/raw payload metadata for task-170 workflow validation, or whether task 169 must introduce an AI-level caller/test seam that invokes Malli validation after extraction.

## 2026-05-23 — executed ambiguity follow-up repeat 2

Completed the newly added ambiguity follow-up without executing implementation `steps.md` work. Updated `design.md`, `plan.md`, and `steps.md` to make task 169's validation proof a handoff-contract proof: AI adapters preserve extracted/raw payloads plus strategy metadata for the existing workflow/runtime validation layer, and this task does not introduce an AI-level Malli validation invocation seam. Marked the ambiguity `design-steps.md` item done.

## 2026-05-23 — inconsistency review repeat 2

Reviewed `design.md`, `plan.md`, `steps.md`, `design-steps.md`, prior implementation notes, current AI model/provider/schema files, and provider docs for cross-artifact inconsistency. Found no new actionable inconsistency feedback. The task artifacts consistently specify OpenAI native support only for explicitly capable `:openai-completions` Chat Completions `response_format`, keep `:openai-codex-responses` fallback-only, model Anthropic forced synthetic-tool extraction separately from ordinary tool calls, require explicit strategy/payload metadata, and preserve workflow/runtime as the final validation authority.

## 2026-05-23 — executed inconsistency follow-ups repeat 2

No newly added unchecked `design-steps.md` items existed after the preloaded inconsistency review, so there were no actionable design follow-ups to execute. Did not execute implementation `steps.md` work.

## 2026-05-23 — ambiguity review repeat 3

Found one new actionable ambiguity: the capability shape uses `:supported? true` for fallback-only structured output while the acceptance criteria also list `:unsupported`, but the artifacts do not define whether `:supported?` means any structured-output path (including prompted JSON), provider-native support only, or request-time support under the requested fallback policy. This should be explicit so model/user-model validation, strategy selection, and documentation do not interpret fallback-only models differently.


## 2026-05-23 — executed ambiguity follow-up repeat 3

Completed the newly added ambiguity follow-up without executing implementation `steps.md` work. Clarified that `:supported?` means at least one declared structured-output request path exists, not provider-native support; provider-native requires `:strategies` to include `:provider-native` plus a concrete `:native-mechanism`. Fallback-only models use `:supported? true` with `[:prompted-json]` and become request-time `:unsupported` when fallback is disallowed. Globally unsupported models use `:supported? false`, empty strategies, and always select `:unsupported`. Updated `plan.md` and `steps.md` to carry the same strategy-selection semantics. Marked the ambiguity design-step done.

## 2026-05-23 — inconsistency review repeat 3

Reviewed `design.md`, `plan.md`, `steps.md`, `design-steps.md`, prior implementation notes, current AI model/provider/schema files, and referenced workflow/provider docs for cross-artifact inconsistency. Found no new actionable inconsistency feedback. The task artifacts remain consistent: `:supported?` denotes any declared structured-output path, provider-native support requires `:provider-native` plus a concrete mechanism, fallback-only models become request-time `:unsupported` only when fallback is disallowed, OpenAI native support is limited to explicit `:openai-completions` Chat Completions `response_format`, Codex remains fallback-only, Anthropic uses a hidden synthetic forced tool payload surface, and workflow/runtime remains final validation authority.

## 2026-05-23 — executed inconsistency follow-ups repeat 3

No newly added unchecked `design-steps.md` items existed after the preloaded inconsistency review, so there were no actionable design follow-ups to execute. Did not execute implementation `steps.md` work.

## 2026-05-23 — ambiguity review repeat 4

Found one new actionable ambiguity: fallback strategy is required to be tested and reported as `:prompted-json`, but the task artifacts do not specify the concrete AI request behavior for fallback selection. They should state whether the AI adapter injects schema-guided JSON instructions into the prompt/request, relies on an already-prompted caller/runtime text, or only reports fallback metadata without mutating the outbound request, so fallback request-shape tests have a deterministic target.

## 2026-05-23 — executed ambiguity follow-up repeat 4

Completed the newly added ambiguity follow-up without executing implementation `steps.md` work. Specified that prompted-JSON fallback is an adapter-owned deterministic request mutation: when `:prompted-json` is selected, the adapter injects schema-guided JSON-only instructions while preserving caller text, avoids provider-native schema fields, and reports `:fallback-used? true`; no-fallback unsupported requests fail/report `:unsupported` without injecting fallback instructions. Updated `plan.md` and `steps.md` with the fallback request-shape target and marked the ambiguity design-step done.

## 2026-05-23 — inconsistency review repeat 4

Found one new actionable inconsistency: task 169 now specifies prompted-JSON fallback as an adapter-owned deterministic request mutation, but referenced workflow structured-output docs still describe `:prompted-json` as the runtime prompting for JSON. Align the docs/task documentation target so the AI adapter owns fallback request shaping while workflow/runtime owns parse/coerce/validate and downstream exposure.

## 2026-05-23 — executed inconsistency follow-up repeat 4

Completed the newly added inconsistency follow-up without executing implementation `steps.md` work. Aligned `doc/workflow-ir.md` and `doc/workflow-grammar.md` with the finalized prompted-JSON fallback boundary: AI adapters own deterministic JSON-only/schema prompt injection and strategy reporting, while workflow runtime owns parsing, schema-guided coercion, validation, and downstream exposure of trusted structured values. Marked the design-step done.

## 2026-05-23 — ambiguity review repeat 5

Found one new actionable ambiguity after checking current AI stream schemas/provider code: the design allows streaming structured-output strategy/result metadata either as new stream events or as an equivalent callback/capture surface, while `psi.ai.schemas/StreamEventType` currently has no structured-output event types. The task should choose the concrete streaming metadata/result surface before implementation so provider code, event schemas, docs, and tests have one target instead of leaving adapters to pick events vs captures ad hoc.


## 2026-05-23 — executed ambiguity follow-up repeat 5

Completed the newly added ambiguity follow-up without executing implementation `steps.md` work. Chose first-class streaming events as the concrete metadata/result surface: task 169 must add `:structured-output-strategy` and `:structured-output-result` to `psi.ai.schemas/StreamEventType`, emit them on provider streams, and test them as the authoritative streaming caller contract. Provider request/response captures may duplicate metadata for diagnostics only. Updated `design.md`, `plan.md`, and `steps.md`, and marked the ambiguity design-step done.

## 2026-05-23 — inconsistency review repeat 5

Reviewed `design.md`, `plan.md`, `steps.md`, `design-steps.md`, prior implementation notes, referenced workflow docs, and current AI model/provider/schema files for cross-artifact inconsistency after choosing first-class streaming structured-output events. Found no new actionable inconsistency feedback. The artifacts consistently require `:structured-output-strategy` and `:structured-output-result` as the authoritative streaming caller surface, allow provider captures only as diagnostics, keep OpenAI native support scoped to explicit `:openai-completions` Chat Completions `response_format`, keep Codex fallback-only, use Anthropic hidden synthetic forced-tool extraction, and preserve workflow/runtime as the final validation authority.

## 2026-05-23 — executed inconsistency follow-ups repeat 5

No newly added unchecked `design-steps.md` items existed after the preloaded inconsistency review, so there were no actionable design follow-ups to execute. Did not execute implementation `steps.md` work.

## 2026-05-23 — ambiguity review repeat 6

Found one new actionable ambiguity: the artifacts define explicit structured-output capability maps for native-capable, fallback-only, and unsupported models, but do not say how to interpret a model description that omits `:capabilities :structured-output` entirely. Existing built-in and custom model definitions currently have no such field, and `user_models.clj` uses a closed model schema, so implementation needs one clear rule for absent capability data: invalid config, default unsupported, default fallback-only, or migration/defaulting behavior.


## 2026-05-23 — executed ambiguity follow-up repeat 6

Completed the newly added ambiguity follow-up without executing implementation `steps.md` work. Specified that omitted `[:capabilities :structured-output]` remains valid for existing built-in and user/custom model descriptions, but normalizes to effective unsupported for strategy selection. Prompted-JSON fallback is explicit opt-in via `:strategies [:prompted-json]`; omitted capability data returns `:unsupported` even when fallback is allowed, avoiding surprise prompt injection for legacy/custom models. Updated `design.md`, `plan.md`, and `steps.md`, and marked the ambiguity design-step done.

## 2026-05-23 — inconsistency review repeat 6

Reviewed `design.md`, `plan.md`, `steps.md`, `design-steps.md`, prior implementation notes, referenced workflow docs, and current AI model/provider/schema/user-model files for cross-artifact inconsistency after absent-capability semantics were clarified. Found no new actionable inconsistency feedback. The task artifacts consistently treat omitted structured-output capability data as load-valid but normalized to effective unsupported, require prompted JSON fallback to be explicit opt-in, keep OpenAI native support scoped to explicit `:openai-completions` Chat Completions `response_format`, keep Codex fallback-only, use Anthropic hidden synthetic forced-tool extraction, expose first-class streaming structured-output events, and preserve workflow/runtime as the final validation authority.

## 2026-05-23 — executed inconsistency follow-ups repeat 6

No newly added unchecked `design-steps.md` items existed after the preloaded inconsistency review, so there were no actionable design follow-ups to execute. Did not execute implementation `steps.md` work.

## 2026-05-23 — ambiguity review repeat 7

Found one new actionable ambiguity: the artifacts require capability selection to be auth-path/transport-aware and give `openai/gpt-5.5` under ChatGPT OAuth/Codex as fallback-only, but the current runtime model override changes only `:api`/`:base-url` for OAuth-backed `gpt-5.5`. If built-in platform `gpt-5.5` later declares OpenAI Chat Completions native capability, it is ambiguous whether the OAuth override also clears/replaces that capability or whether strategy selection derives effective capability from the post-auth runtime API. The design should specify the authoritative capability source after runtime auth/transport resolution so Codex OAuth cannot inherit platform-native structured-output support accidentally.

## 2026-05-23 — executed ambiguity follow-up repeat 7

Completed the newly added ambiguity follow-up without executing implementation `steps.md` work. Updated `design.md` to make the resolved runtime model the authoritative source for structured-output capability after auth/transport overrides. If `resolve-runtime-model` maps OAuth-backed `openai/gpt-5.5` to `:openai-codex-responses`, it must clear or replace any platform Chat Completions native capability with Codex fallback-only or unsupported capability; strategy selection must consume the resolved capability and cannot inherit pre-override native support. Updated `plan.md` and `steps.md` with the runtime-resolution requirement and marked the ambiguity design-step done.
