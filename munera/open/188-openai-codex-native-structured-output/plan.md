Approach

1. Reconfirm the current Codex structured-output boundary.
   - Prove from the current model registry, structured-output capability code, and provider tests that transport-resolved ChatGPT/Codex execution is currently fallback-only.
   - Treat the authoritative seam as resolved runtime transport, especially OAuth-routed `gpt-5.5` resolving onto `:openai-codex-responses`.

2. Record the verified ChatGPT/Codex endpoint contract.
   - Inspect the current Codex request/response shaping in `components/ai/src/psi/ai/providers/openai/codex_responses.clj` and adjacent OpenAI provider seams.
   - Record the live discovery evidence showing that `https://chatgpt.com/backend-api/codex/responses` supports native schema-constrained structured output for streaming requests.
   - Record the exact accepted request surface: Responses-style `text.format` with `{"type":"json_schema", ...}`.
   - Record the exact rejected request surface: Chat Completions-style `response_format`, which returned `400` unsupported-parameter.
   - Record that `stream: false` returned `400`, so native support is verified for streaming while non-streaming remains a separate open question.

3. Implement the smallest coherent capability slice for the evidence-backed outcome.
   - Introduce a distinct Codex native mechanism unless the protocol is proven identical to an existing OpenAI mechanism.
   - Update capability declaration, strategy selection, request shaping, and streaming response extraction to use the verified native Codex contract.
   - Leave non-streaming support unimplemented unless separate verification establishes a supported contract.
   - Preserve the distinction between ChatGPT/Codex transport capability and the existing OpenAI Chat Completions native structured-output path.

4. Add focused proofs at the authoritative seams.
   - Cover transport-resolved runtime model cases, including OAuth-routed `gpt-5.5`, not only static Codex catalog entries.
   - Add provider tests for Codex request shaping and structured-output result/event behavior under the finalized capability.
   - Add a focused workflow or turn-runtime regression proving loop-control schemas remain intact under the finalized Codex capability.

5. Record the finalized boundary.
   - Update task implementation notes with the evidence, decisions, and verification results.
   - Update user-facing or internal AI docs only if the resulting capability boundary is discoverable/document-worthy.

Risks

- The ChatGPT/Codex backend may use undocumented schema fields or a subtly different response shape than public OpenAI APIs.
- Native schema support may exist only for streaming, which could leave non-streaming `:execute` support out of scope or partially supported.
- Live verification may be constrained by available OAuth/account context, in which case the implementation may need to stop at explicit fallback-only evidence.
- Runtime capability assignment spans both static model catalog data and transport-resolved model selection, so drift between those seams is a risk.

Slice order

1. Baseline evidence slice: confirm current fallback-only capability and inventory provider seams.
2. Capability discovery slice: record the verified ChatGPT/Codex native structured-output contract.
3. Capability implementation slice: implement the minimal coherent native streaming outcome, leaving non-streaming explicit until separately verified.
4. Proof slice: add focused model-registry, provider, and workflow/turn-runtime regression tests.
5. Documentation slice: record implementation notes and any necessary docs updates.
