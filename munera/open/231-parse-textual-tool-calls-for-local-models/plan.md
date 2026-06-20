# 231 — Plan

## Approach

Implement textual tool-call recovery as a narrow, opt-in normalization step at the turn-runtime/provider-boundary layer, using the already-resolved runtime `ai-model` for the turn as the authority for capability lookup.

Key decisions:

- Add pure lower-level helpers, not an `agent-session` dependency from `turn-runtime`:
  - a model-capability predicate for `{:capabilities {:textual-tool-calls #{:xml}}}` near existing AI/model capability code that `turn-runtime` can legally require;
  - a pure XML-like textual tool-call parser/normalizer that is intentionally not a general XML parser.
- Normalize assistant content once before downstream tool execution sees the assistant message:
  - streaming: apply during final assistant-content assembly from accumulated text/tool calls;
  - non-streaming: route the returned assistant message through the same pure normalization helper before returning the turn result.
- Convert only well-formed `<tool_call>...</tool_call>` blocks into canonical `{:type :tool-call ...}` content blocks with JSON-object-string `:arguments`.
- Remove exactly the parsed blocks from assistant text while preserving surrounding prose and malformed/partial markup as ordinary text.
- Reuse existing canonical tool-call content semantics and execution path; parsed calls must be indistinguishable from provider-emitted tool calls before tool availability, authorization, journaling, and result handling.
- Keep default/frontier models opt-out by default: no capability means no parsing and no behavioral/cost change.
- Update schemas/docs/tests so local/custom model definitions can declare the capability and users know when not to enable it.

## Risks

- Parser looseness could create false-positive tool execution from prose; mitigate with explicit capability gating and a strict tag/name grammar.
- Parser strictness may reject near-miss runner output; this is acceptable for the first slice because the design intentionally targets one known XML-like format.
- Streaming and non-streaming paths may drift if normalization is wired twice; mitigate by introducing one pure normalization function and testing both paths where practical.
- Content ordering/id generation can regress if parsed calls are appended without respecting original block order; tests must cover surrounding text, provider/recovered mixed content, multiple calls in response order, and generated id/index non-collision.
- Capability placement can accidentally introduce a component cycle; keep helpers in `components/ai` or another lower-level namespace already available to `turn-runtime`.

## Slice order

1. **Capability model surface** — add schema/helper support for `:capabilities :textual-tool-calls #{:xml}` and tests proving default models remain disabled.
2. **Pure parser** — implement and test the strict XML-like parser for nominal, multi-call, multi-parameter, duplicate/malformed, nesting/cardinality, and preservation cases.
3. **Canonical normalization** — convert parsed calls plus residual text into canonical assistant content blocks with JSON-object-string arguments and stable per-turn call ids/order.
4. **Turn-runtime integration** — apply the shared normalizer to streaming final assembly and non-streaming assistant responses using the already-resolved runtime `ai-model`.
5. **Execution-path coverage** — add tests proving parsed calls flow through existing tool execution/journaling/error policy and disabled models leave markup as text.
6. **Docs and changelog** — document local/custom model opt-in and warning for frontier models; add a user-visible changelog entry.
