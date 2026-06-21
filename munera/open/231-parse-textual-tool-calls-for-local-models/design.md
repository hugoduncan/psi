# 231 — Parse textual tool calls for local models

## Intent

Make Psi optionally recover tool calls that a local model runner emits as plain assistant text, so local models can still use tools when their runner fails to convert a tool-call markup block into provider tool-call events.

The motivating observed shape is:

```xml
<tool_call>
<function=bash>
<parameter=command>
cd /Users/duncan/projects/hugoduncan/psi/compaction && git diff --stat
</parameter>
</function>
</tool_call>
```

When the active model explicitly declares this compatibility capability, Psi should interpret that assistant text as a normal tool call instead of leaving it in the conversation transcript as user-visible prose.

## Problem

Some local model runners, e.g. llama.cpp-compatible paths, may output tool-call markup in the assistant text stream rather than emitting Psi's canonical provider tool-call events. Today the turn runtime only executes tools when provider adapters produce canonical tool-call content. A malformed/local runner response therefore strands an otherwise valid tool request as text, and the session does not execute the tool.

Frontier/provider-native models already emit structured tool events correctly. Running a text parser on every model turn would add unnecessary cost and risk false positives on models that do not need this compatibility path. The behavior needs to be gated by explicit model capability metadata, not inferred from provider names or ad hoc text heuristics.

## Scope

In scope:

- Add an explicit model capability flag for textual tool-call recovery using this capability shape:

  ```edn
  {:capabilities
   {:textual-tool-calls #{:xml}}}
  ```

  `:xml` means the observed XML-like `<tool_call>` / `<function=...>` / `<parameter=...>` tag format. Frontier/default models omit the capability or provide an empty set.
- Resolve the capability from the **runtime active model** used for the turn, after any model-registry/provider-auth runtime resolution, so custom local model definitions can opt in and frontier models remain opt out.
- Add a parser for the XML-like textual tool-call format:
  - `<tool_call> ... </tool_call>` encloses one call.
  - `<function=TOOL_NAME> ... </function>` declares the tool name.
  - One or more `<parameter=PARAM_NAME> ... </parameter>` blocks declare string parameters.
  - Tag names and tool/parameter names are case-sensitive. Only lowercase `tool_call`, `function`, and `parameter` tags are recognized; variants such as `<TOOL_CALL>`, `<function=TOOL_NAME>` with a different tool-name case, or `<parameter=PARAM_NAME>` with a different parameter-name case are distinct text and are not normalized.
  - `TOOL_NAME` and `PARAM_NAME` must match the narrow identifier grammar `[A-Za-z0-9_-]+`. No whitespace, quotes, namespaces, attributes, dots, slashes, or entity decoding are accepted inside tag names.
  - Parameter text is trimmed at tag boundaries but otherwise preserved, including internal newlines and shell metacharacters.
  - Strict simplification: parameter text must not contain tag-looking textual-tool-call markup. Any `<tool_call>`, `</tool_call>`, `<function=...>`, `</function>`, `<parameter=...>`, or `</parameter>` substring inside parameter text makes that enclosing `<tool_call>` block malformed. This compatibility format is intentionally narrow; commands that need literal tag-looking text should use another representation rather than relying on this recovery parser.
  - Parsed arguments are represented as the existing canonical tool-call `:arguments` JSON object string before they reach tool execution. Parameter names become JSON object keys and parameter values remain strings.
  - Duplicate `<parameter=PARAM_NAME>` blocks within the same function make that `<tool_call>` block malformed. Do not choose first-wins or last-wins, because canonical JSON object arguments cannot represent duplicate keys unambiguously.
  - A well-formed `<tool_call>` block must contain exactly one `<function=TOOL_NAME>...</function>` block. All `<parameter=PARAM_NAME>...</parameter>` blocks for that call must be nested inside that function block, and the function must contain one or more parameters. Multiple function blocks, missing function blocks, empty-parameter calls, or parameter blocks outside the function make that `<tool_call>` block malformed.
- Convert a parsed textual tool call into the same canonical assistant tool-call content shape and downstream execution path used by provider-emitted tool calls.
- Remove the exact parsed `<tool_call>...</tool_call>` blocks from assistant text for the turn that generated them, so the transcript does not contain both prose markup and executable tool calls. Any non-tool prose before/after parsed blocks remains as assistant text.
- Support multiple textual tool calls in one assistant response. Every well-formed `<tool_call>` block is converted into a canonical tool call in response order.
- Mixed responses are handled block-by-block: well-formed `<tool_call>...</tool_call>` blocks are converted and removed, while malformed or partial markup outside those exact parsed blocks remains ordinary assistant text and does not prevent conversion of other later, independent well-formed blocks.
- Reject malformed, partial, unknown-format, or unsupported textual tool-call markup safely: leave malformed text unchanged and do not execute a tool for that malformed text unless the parser can produce an unambiguous canonical call. Malformed markup is a no-op, not an error surface. Nested recovery is explicitly not supported: a well-formed-looking `<tool_call>` that appears inside the span of another `<tool_call>` candidate, whether that outer candidate is valid or malformed, remains ordinary text and must not be recovered/executed as an independent call.
- Add focused tests for capability gating, parser behavior, turn accumulation/conversion, and no-op behavior for frontier/default models.
- Document the model capability in user-facing custom-provider/model documentation and add a changelog entry if the capability is user-visible.

Out of scope:

- Teaching every local runner to emit canonical tool events at the adapter level.
- Inferring this behavior from provider ids, model ids, runner names, or endpoints.
- Parsing arbitrary JSON/function-call syntaxes beyond the observed XML-like tags.
- Executing text that merely resembles XML unless it is inside a well-formed `<tool_call>` block and the active model has opted in.
- Supporting literal textual-tool-call tag markup inside parameter values. Under the strict `:xml` recovery contract, tag-looking substrings inside parameter values make the enclosing candidate malformed/no-op.
- Changing tool permission/capability policy. Parsed textual tool calls must pass through the same existing tool availability, authorization, execution, journaling, and result-recording paths as normal tool calls.

## Acceptance criteria

1. A model definition can opt in to textual tool-call recovery with `{:capabilities {:textual-tool-calls #{:xml}}}`.
2. Models without the capability — including default/frontier models — pay no parsing/execution behavior cost and preserve textual `<tool_call>` content as ordinary assistant text.
3. With the capability enabled, the example `bash` block above produces one canonical tool call named `bash` with canonical `:arguments` JSON equivalent to `{"command":"cd /Users/duncan/projects/hugoduncan/psi/compaction && git diff --stat"}` before existing tool execution parses it.
4. Multiple well-formed textual tool-call blocks in a single assistant response produce multiple canonical tool calls in response order.
5. Parsed calls are executed by the existing tool execution machinery and produce ordinary tool-result journal entries; no separate compatibility execution path is introduced.
6. The exact parsed `<tool_call>...</tool_call>` blocks are not retained as assistant prose in the conversation for the same turn. Surrounding non-tool text, if any, is preserved.
7. Malformed examples are no-ops for the malformed text: they do not execute any tool, do not corrupt the turn, and remain visible as ordinary assistant text; other later, independent well-formed blocks in the same response are still converted.
8. Parameter values containing tag-looking textual-tool-call markup are malformed/no-op for the enclosing block. Tests prove at least literal `<tool_call>`, `<function=...>`, and `<parameter=...>` inside parameter text are not preserved as valid parameter text and do not trigger nested execution.
9. Unknown tool names or unavailable tools follow the same errors/policy as canonical provider-emitted tool calls.
10. Tests cover enabled vs disabled capability, nominal `bash` parsing, multiple calls, multi-parameter parsing, malformed markup no-op, and preservation of surrounding text while removing only exact parsed blocks.
11. User docs explain how a local/custom model opts into the compatibility parser, warn that frontier models should not enable it, and document that literal tag-looking markup inside parameter values is unsupported.

## Design constraints

- Prefer model-map capability data over provider/model heuristics. `runtime-active-model` or the turn's already-resolved model is the authority.
- Keep parsing local to the turn/provider-boundary normalization layer: after assistant text is known, before the runtime decides whether tool calls are pending/executable.
- The normalization should be a single pure boundary used by both streaming final assembly and non-streaming assistant responses. Do not implement separate streaming-only and non-streaming parsers, and do not duplicate conversion logic across execution paths.
- Resolve textual tool-call capability from the turn's already-resolved runtime model/prepared-request model. Do not make `turn-runtime` depend on the `agent-session` component to call session capability helpers; if shared helpers are needed, place pure model-capability/parser helpers in a lower-level component already allowed by the dependency graph.
- Reuse existing canonical tool-call ids and content block semantics. If the parsed markup has no id, generate one with the same per-turn/canonical id approach used for provider tool calls.
- When residual assistant text, provider-emitted tool calls, and recovered textual tool calls coexist, preserve canonical response order by content position:
  - provider-emitted tool calls keep their existing content indexes and ids;
  - recovered textual tool calls are inserted at the position of their parsed `<tool_call>...</tool_call>` block relative to residual text;
  - generated ids for recovered calls use the existing `turn-id/toolcall/content-index` convention with non-colliding content indexes assigned in final-content order after considering provider indexes;
  - final assistant content blocks should not expose implementation-only content-index metadata unless that is already part of the existing canonical content shape.
- Do not bypass security: parsed textual calls must be indistinguishable from provider calls by the time authorization/tool availability checks run.
- Make invalid states unreachable where practical: either the assistant message has text or canonical tool-call blocks, not an unexecuted textual tool-call block that also triggers execution.
- Keep the parser intentionally narrow. It should handle the known runner output reliably, not become a general XML parser. Prefer rejecting ambiguous tag-looking parameter text over adding recovery heuristics for nested or literal markup.

## Implementation notes / likely touch points

These are orientation hints, not binding implementation instructions:

- Capability helpers likely belong near `psi.agent-session.model-capabilities`, which already resolves session runtime model capabilities.
- Turn accumulation and final assistant content assembly live in `components/turn-runtime/src/psi/turn_runtime/accumulator.clj`; this is a likely normalization point for converting final text into canonical tool-call content before `:on-done` delivers the assistant message.
- Model definitions live under `components/ai/src/psi/ai/models.clj`; custom/user model loading may need schema/doc updates so the new capability can be declared by local model definitions.
- Existing tool-call assembly helpers in the turn runtime should be reused rather than introducing a second execution path.
