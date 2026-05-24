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

## 2026-05-23 — inconsistency review repeat 7

Found one new actionable inconsistency: `design.md` makes auth path part of the effective resolved structured-output capability and shows a fallback-only `openai/gpt-5.5` runtime model with `:auth :chatgpt-oauth`, but current/target model schemas are closed and `plan.md`/`steps.md` do not say to allow or populate an auth marker on resolved runtime models. As written, implementation could either make the design example/schema invalid or leave auth-path capability selection unobservable despite the design requiring final `:auth` to participate in resolution.

## 2026-05-23 — executed inconsistency follow-up repeat 7

Completed the newly added inconsistency follow-up without executing implementation `steps.md` work. Resolved the auth-path representation inconsistency by keeping model maps/schema closed and not adding a runtime-only `:auth` marker. Updated `design.md` so ChatGPT OAuth capability selection is resolver-context-derived and materialized as the resolved model's final `:api`, `:base-url`, and structured-output capability map; strategy selection consumes that resolved capability and does not depend on an `:auth` field. Updated `plan.md` and `steps.md` with the same constraint and marked the design-step done.

## 2026-05-23 — ambiguity review repeat 8

Found one new actionable ambiguity: the non-streaming structured-output metadata/payload surface is still not anchored to the current `execute-response` return shape. The design says provider execution "returns or associates" `:structured-output` and examples show `[:structured-output :payload]`, but current OpenAI execution returns a top-level map like `{:assistant-message ... :logprobs ...}`. The task should specify whether non-streaming `:structured-output` is a top-level provider result key, nested on `:assistant-message`, only present in captures, or exposed through another exact field so implementation and tests do not choose different roots.


## 2026-05-23 — executed ambiguity follow-up repeat 8

Completed the newly added ambiguity follow-up without executing implementation `steps.md` work. Anchored non-streaming structured-output metadata and extracted payloads to a top-level `:structured-output` key on provider result maps, sibling to existing `:assistant-message` and `:logprobs` entries, not nested under `:assistant-message` and not capture-only. Updated `design.md`, `plan.md`, and `steps.md` so tests target that exact root, and marked the ambiguity design-step done.

## 2026-05-23 — inconsistency review repeat 8

Reviewed `design.md`, `plan.md`, `steps.md`, `design-steps.md`, prior implementation notes, referenced workflow docs, and current AI model/provider/schema/user-model files for cross-artifact inconsistency after anchoring the non-streaming structured-output result root. Found no new actionable inconsistency feedback. The task artifacts consistently require top-level non-streaming `:structured-output` provider-result metadata/payload, first-class streaming structured-output events, resolver-derived auth/transport capability normalization without a runtime `:auth` field, omitted capabilities normalized to unsupported, explicit prompted-JSON opt-in/fallback shaping, OpenAI native support limited to explicit `:openai-completions` Chat Completions `response_format`, Codex fallback-only/unsupported behavior, Anthropic hidden synthetic forced-tool extraction, and workflow/runtime final validation authority.

## 2026-05-23 — executed inconsistency follow-ups repeat 8

No newly added unchecked `design-steps.md` items existed after the preloaded inconsistency review, so there were no actionable design follow-ups to execute. Did not execute implementation `steps.md` work.

## 2026-05-23 — ambiguity review repeat 9

Found one new actionable ambiguity: the request contract still leaves JSON Schema availability undecided. It says adapters may derive `:json-schema` from `:schema`, or support the judge schema subset if conversion is too broad, while provider-native OpenAI/Anthropic request construction requires a JSON-Schema-compatible payload. The task should choose whether task 169 requires callers to supply `:json-schema`, implements a minimal Malli-to-JSON-Schema conversion subset, or fails `:unsupported` when only `:schema` is present so implementation and tests share one contract.

## 2026-05-23 — executed ambiguity follow-up repeat 9

Completed the newly added ambiguity follow-up without executing implementation `steps.md` work. Chose explicit request `:json-schema` as the provider-bound schema source for task 169; AI adapters do not convert Malli/domain `:schema` in this slice. Schema-only structured-output requests must report `:unsupported` with a clear missing-JSON-Schema reason and must not inject fallback prompts or provider-native fields. Updated `design.md`, `plan.md`, and `steps.md`, and marked the ambiguity design-step done.

## 2026-05-23 — ambiguity review repeat 10

Found one new actionable ambiguity: the task specifies capability shapes and provider-native request behavior, but does not identify which current built-in model descriptions should actually declare structured-output capability in this slice. The OpenAI example uses `openai/gpt-4.1`, which is not a current built-in model, and the Anthropic example names a conceptual Sonnet 4 id while current built-ins include several Anthropic Messages models. Implementation and tests need one rule: mark all current `:openai-completions` / `:anthropic-messages` built-ins that support the chosen mechanisms, mark only a named subset, or use test-only synthetic models while documenting built-ins as omitted/unsupported.

## 2026-05-23 — executed ambiguity follow-up repeat 10

Completed the newly added ambiguity follow-up without executing implementation `steps.md` work. Added a concrete built-in capability assignment to `design.md`: all current Anthropic Messages built-ins are forced-tool native-capable; named modern OpenAI Chat Completions built-ins are JSON Schema response-format native-capable; OpenAI Codex Responses built-ins are prompted-JSON fallback-only; unverified OpenAI Chat Completions entries such as `:o1-preview` and `:codex-mini-latest` remain omitted/unsupported unless verified during implementation. Updated `plan.md` and `steps.md` to carry the assignment target, and marked the ambiguity design-step done.

## 2026-05-23 — inconsistency review repeat 9

Found one new actionable inconsistency: the finalized request contract and plan require explicit request `:json-schema` and say AI adapters do not convert Malli/domain `:schema` in task 169, but `design.md` still says request options may carry a JSON-Schema payload "or converted schema" and the design constraints still say to convert Malli/domain schemas to provider-compatible JSON Schema at the API boundary. Align those older acceptance/constraint statements with the explicit-`:json-schema` source contract.

## 2026-05-23 — executed inconsistency follow-up repeat 9

Completed the newly added inconsistency follow-up without executing implementation `steps.md` work. Aligned the remaining `design.md` acceptance and design-constraint wording with the finalized explicit-`:json-schema` request contract: callers must supply provider-bound `:json-schema`, `:schema` is metadata only, and AI adapters do not convert Malli/domain schemas in task 169. Marked the design-step done.

## 2026-05-23 — ambiguity review repeat 11

Reviewed `design.md`, `plan.md`, `steps.md`, `design-steps.md`, prior implementation notes, referenced workflow docs, and current AI model/provider/schema/user-model files for ambiguity. Found no new actionable ambiguity feedback. The task artifacts now give one concrete contract for built-in capability assignment, explicit caller-supplied `:json-schema`, omitted capability normalization, auth/transport override handling, fallback prompt injection, OpenAI/Anthropic native request shapes, top-level non-streaming `:structured-output`, first-class streaming structured-output events, and workflow/runtime final validation authority.

## 2026-05-23 — executed ambiguity follow-up repeat 11

No newly added unchecked `design-steps.md` items existed after the preloaded ambiguity review, so there were no actionable ambiguity design follow-ups to execute. Did not execute implementation `steps.md` work.

## 2026-05-23 — inconsistency review repeat 10

Reviewed `design.md`, `plan.md`, `steps.md`, `design-steps.md`, prior implementation notes, referenced workflow docs, and current AI model/provider/schema/user-model files for cross-artifact inconsistency after the JSON Schema source and built-in capability assignment clarifications. Found no new actionable inconsistency feedback. The task artifacts consistently require explicit caller-supplied `:json-schema`, do not add AI-side Malli/domain schema conversion, treat omitted capability data as load-valid but effectively unsupported, derive auth/transport-specific capability from the resolved runtime model without an `:auth` field, use top-level non-streaming `:structured-output` plus first-class streaming structured-output events, keep OpenAI native support scoped to explicit `:openai-completions` Chat Completions `response_format`, keep Codex fallback-only/unsupported behavior, use Anthropic hidden synthetic forced-tool extraction, and preserve workflow/runtime as final validation authority.

## 2026-05-23 — executed inconsistency follow-ups repeat 10

No newly added unchecked `design-steps.md` items existed after the preloaded inconsistency review, so there were no actionable design follow-ups to execute. Did not execute implementation `steps.md` work.

## 2026-05-23 — ambiguity review repeat 12

Reviewed `design.md`, `plan.md`, `steps.md`, `design-steps.md`, prior implementation notes, referenced workflow docs, and current AI model/provider/schema/user-model files for ambiguity. Found no new actionable ambiguity feedback. The task artifacts remain unambiguous on caller-supplied `:json-schema`, capability defaulting and built-in assignment, auth/transport-aware resolved capabilities without `:auth`, OpenAI/Anthropic native request shapes, prompted-JSON fallback injection, top-level non-streaming `:structured-output`, first-class streaming events, extracted payload handoff, and workflow/runtime final validation authority.

## 2026-05-23 — executed ambiguity follow-up repeat 12

No newly added unchecked `design-steps.md` items existed after the preloaded ambiguity review, so there were no actionable ambiguity design follow-ups to execute. Did not execute implementation `steps.md` work.

## 2026-05-23 — inconsistency review repeat 11

Reviewed `design.md`, `plan.md`, `steps.md`, `design-steps.md`, prior implementation notes, referenced workflow docs, and current AI model/provider/schema/user-model files for cross-artifact inconsistency. Found no new actionable inconsistency feedback. The artifacts remain aligned on explicit caller-supplied `:json-schema`, no AI-side Malli/domain schema conversion, omitted capability normalization to unsupported, resolved-runtime-model capability authority without `:auth`, top-level non-streaming `:structured-output`, first-class streaming structured-output events, OpenAI Chat Completions native-only support, Codex fallback-only/unsupported behavior, Anthropic hidden synthetic forced-tool extraction, adapter-owned prompted-JSON fallback shaping, and workflow/runtime final validation authority.

## 2026-05-23 — executed inconsistency follow-ups repeat 11

No newly added unchecked `design-steps.md` items existed after the preloaded inconsistency review, so there were no actionable design follow-ups to execute. Did not execute implementation `steps.md` work.

## 2026-05-23 — ambiguity review repeat 13

Reviewed `design.md`, `plan.md`, `steps.md`, `design-steps.md`, prior implementation notes, referenced workflow docs, and current AI model/provider/schema/user-model files for ambiguity. Found no new actionable ambiguity feedback. The task artifacts remain unambiguous on explicit caller-supplied `:json-schema`, capability semantics/defaulting and built-in assignments, resolved-runtime-model capability authority after auth/transport overrides without an `:auth` field, OpenAI/Anthropic native request shapes, adapter-owned prompted-JSON fallback injection, top-level non-streaming `:structured-output`, first-class streaming structured-output events, provider-extracted payload handoff, and workflow/runtime final validation authority.

## 2026-05-23 — executed ambiguity follow-up repeat 13

No newly added unchecked `design-steps.md` items existed after the preloaded ambiguity review, so there were no actionable ambiguity design follow-ups to execute. Did not execute implementation `steps.md` work.

## 2026-05-23 — inconsistency review repeat 12

Reviewed `design.md`, `plan.md`, `steps.md`, `design-steps.md`, prior implementation notes, referenced workflow docs, and current AI model/provider/schema/user-model files for cross-artifact inconsistency. Found no new actionable inconsistency feedback. The task artifacts remain aligned on explicit caller-supplied `:json-schema`, no AI-side Malli/domain schema conversion, omitted capability normalization to unsupported, resolved-runtime-model capability authority without an `:auth` field, top-level non-streaming `:structured-output`, first-class streaming structured-output events, OpenAI Chat Completions native-only support, Codex fallback-only/unsupported behavior, Anthropic hidden synthetic forced-tool extraction, adapter-owned prompted-JSON fallback shaping, and workflow/runtime final validation authority.

## 2026-05-23 — executed inconsistency follow-ups repeat 12

No newly added unchecked `design-steps.md` items existed after the preloaded inconsistency review, so there were no actionable design follow-ups to execute. Did not execute implementation `steps.md` work.


## 2026-05-23 — ambiguity review repeat 14

Reviewed `design.md`, `plan.md`, `steps.md`, `design-steps.md`, prior implementation notes, referenced workflow docs, and current AI model/provider/schema/user-model files for ambiguity. Found no new actionable ambiguity feedback. The task artifacts remain unambiguous on explicit caller-supplied `:json-schema`, capability semantics/defaulting and built-in assignments, resolved-runtime-model capability authority after auth/transport overrides without an `:auth` field, OpenAI/Anthropic native request shapes, adapter-owned prompted-JSON fallback injection, top-level non-streaming `:structured-output`, first-class streaming structured-output events, provider-extracted payload handoff, and workflow/runtime final validation authority.

## 2026-05-23 — executed ambiguity follow-up repeat 14

No newly added unchecked `design-steps.md` items existed after the preloaded ambiguity review, so there were no actionable ambiguity design follow-ups to execute. Did not execute implementation `steps.md` work.

## 2026-05-23 — inconsistency review repeat 13

Reviewed `design.md`, `plan.md`, `steps.md`, `design-steps.md`, prior implementation notes, referenced workflow/provider docs, and current AI model/schema/structured-output files for cross-artifact inconsistency. Found no new actionable inconsistency feedback. The task artifacts remain aligned on explicit caller-supplied `:json-schema`, no AI-side Malli/domain schema conversion, omitted capability normalization to unsupported, resolved-runtime-model capability authority without an `:auth` field, top-level non-streaming `:structured-output`, first-class streaming structured-output events, OpenAI Chat Completions native-only support, Codex fallback-only/unsupported behavior, Anthropic hidden synthetic forced-tool extraction, adapter-owned prompted-JSON fallback shaping, and workflow/runtime final validation authority.

## 2026-05-23 — executed inconsistency follow-ups repeat 13

No newly added unchecked `design-steps.md` items existed after the preloaded inconsistency review, so there were no actionable design follow-ups to execute. Did not execute implementation `steps.md` work.

## 2026-05-23 — implementation slice 1

Implemented the model/capability foundation slice. Added structured-output capability schemas and stream event enum values, introduced `psi.ai.structured-output` normalization helpers, declared built-in capabilities for Anthropic Messages/native forced-tool, named modern OpenAI Chat Completions/native JSON Schema response format, OpenAI Codex Responses/fallback-only, and left unverified OpenAI Chat Completions entries omitted so they normalize to unsupported. User/custom models now accept optional structured-output capability data while omitted data remains load-valid and effective unsupported. Runtime OAuth resolution for `openai/gpt-5.5` now materializes Codex fallback-only capability after transport override so platform-native capability cannot leak into ChatGPT OAuth execution. Added focused model registry and user model tests. Verification: `clojure -M:test --focus psi.ai.model-registry-test --focus psi.ai.user-models-test` => 24 tests, 160 assertions, 0 failures.

## 2026-05-23 — implementation slice 2

Implemented structured-output request strategy/request-shaping slice. Added request normalization, explicit `:json-schema` strategy selection, provider-neutral prompted-JSON fallback instruction generation, OpenAI Chat Completions native `response_format` construction, OpenAI Codex fallback-only prompt shaping with no native schema fields, Anthropic synthetic forced-tool request composition with deterministic collision suffixing, and first-class streaming strategy events. OpenAI non-streaming execution now returns top-level `:structured-output` metadata with parsed payload handoff for provider-native/prompted JSON content; local Malli/domain validation remains outside AI adapters. Updated AI/custom-provider docs. Focused verification: `clojure -M:test --focus psi.ai.model-registry-test --focus psi.ai.user-models-test` => 24 tests, 160 assertions, 0 failures; `clojure -M:test --focus psi.ai.providers.openai-test --focus psi.ai.providers.anthropic-test` => 49 tests, 268 assertions, 0 failures.

## 2026-05-23 — implementation slice 3

Completed the remaining streaming result/payload surface slice. OpenAI Chat Completions streaming now accumulates assistant text for structured-output requests and emits `:structured-output-result` with parsed payload/raw text before completion. Anthropic streaming now recognizes the synthetic forced structured-output tool, suppresses its ordinary `:toolcall-*` events, accumulates its `partial_json`, and emits `:structured-output-result` with `:source :anthropic/tool-use`; ordinary tools remain unaffected. Updated AI docs to name both first-class streaming structured-output events. Focused verification: `clojure -M:test --focus psi.ai.model-registry-test --focus psi.ai.user-models-test` => 24 tests, 160 assertions, 0 failures; `clojure -M:test --focus psi.ai.providers.openai-test --focus psi.ai.providers.anthropic-test` => 45 tests, 252 assertions, 0 failures; targeted combined provider structured-output/Anthropic focused run => 26 tests, 167 assertions, 0 failures.

## 2026-05-23 — broader verification

Ran `bb clojure:test:unit`; all tests passed.

## 2026-05-23 — implementation review

Found one new actionable implementation issue: Anthropic request construction only handles `:provider-native` structured-output strategy. If a resolved Anthropic/custom `:anthropic-messages` model declares fallback-only `:strategies [:prompted-json]`, `select-strategy` reports `:prompted-json` but `build-request` does not append adapter-owned JSON-only/schema fallback instructions, so the checked prompted-JSON fallback step is only implemented for OpenAI/Codex paths.

## 2026-05-23 — implementation-review follow-up

Implemented the Anthropic Messages prompted-JSON fallback path for fallback-only structured-output capabilities. `build-request` now passes selected fallback requests into Anthropic message transformation, appending the adapter-owned JSON-only/schema instruction to the final user text block while preserving caller text, ordinary tools, and absence of native `tool_choice`/synthetic forced-tool fields. Added focused Anthropic structured-output fallback request-shape coverage proving `:prompted-json`/`:fallback-used? true`, prompt injection, no forced native field, and ordinary-tool preservation. Verification: `clojure -M:test --focus psi.ai.providers.anthropic-structured-output-test` => 4 tests, 20 assertions, 0 failures; `clojure -M:test --focus psi.ai.model-registry-test --focus psi.ai.user-models-test` => 24 tests, 160 assertions, 0 failures; combined provider focused run `clojure -M:test --focus psi.ai.providers.openai-test --focus psi.ai.providers.anthropic-test --focus psi.ai.providers.openai-structured-output-test --focus psi.ai.providers.anthropic-structured-output-test` => 50 tests, 280 assertions, 0 failures.

## 2026-05-23 — test review

Found one new actionable test/verification issue: current focused structured-output verification does not load because `components/ai/src/psi/ai/providers/anthropic.clj` fails compilation after the Anthropic error extraction (`Unable to resolve symbol: oauth-auth-request?` / `psi.ai.providers.anthropic` not found). The recorded green test results are therefore stale until the compile/load failure is fixed and the focused provider/model tests are rerun.

## 2026-05-23 — test-review follow-up

Rechecked the preloaded Anthropic provider compile/load failure. The current Anthropic namespace loads successfully; `anthropic-error/oauth-auth-request?` is present and the focused Anthropic structured-output test now runs. Marked the follow-up complete after refreshing focused structured-output evidence. Verification: `clojure -M:test --focus psi.ai.providers.anthropic-structured-output-test` => 4 tests, 20 assertions, 0 failures; `clojure -M:test --focus psi.ai.providers.openai-structured-output-test --focus psi.ai.providers.anthropic-structured-output-test --focus psi.ai.model-registry-test --focus psi.ai.user-models-test` => 32 tests, 199 assertions, 0 failures.

## 2026-05-23 — test-shaper review

Found one new actionable test-shaping issue: streaming structured-output tests exercise provider-native OpenAI/Anthropic result events, but no focused test covers prompted-JSON fallback streaming (for Codex or a fallback-only Anthropic model). The design requires fallback structured-output streaming requests to expose first-class strategy/result surfaces, so the suite can pass while fallback streaming only emits strategy metadata or omits parsed result handoff.
