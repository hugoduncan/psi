# Plan

## Approach

Implement task 170 as a narrow workflow-runtime wiring slice. Keep provider-specific request construction in the AI provider adapters from tasks 169 and 171. Workflow code should only pass a provider-neutral `:structured-output` request contract and record the returned strategy metadata in the workflow structured-output envelope.

## Order

1. Extend the normalized workflow structured-output spec/schema surface to accept the canonical policy keys: `:json-schema`, `:strategy-preference`, `:fallback`, and `:require-provider-native?`.
2. Add a small workflow-runtime helper that turns a structured output spec into the task-169 AI request map under `:structured-output`, failing clearly when `:json-schema` is missing and encoding required-native requests as fallback-forbidden without trying to inspect provider capability.
3. Wire session-step execution so structured `:outputs` pass that request map into actor-turn generation.
4. Wire LLM-judge execution so judge structured `:outputs` pass the same request map into judge-turn generation.
5. Extend structured-output envelope construction to accept AI structured-output metadata and native/raw payloads while preserving task-168 downstream `:value` semantics.
6. Add focused tests for provider-native, prompted fallback, required-native unsupported failure, missing JSON Schema failure, invalid local validation, downstream value resolution, and persisted/replay metadata behavior.
7. Update `doc/workflow-grammar.md`, `doc/workflow-ir.md`, and `doc/workflows.md` with the final author-facing policy keys, JSON Schema boundary, and envelope metadata.

## Design decisions

- Workflow specs must provide explicit `:json-schema`; task 170 does not perform Malli-to-JSON-Schema conversion.
- Omitted `:strategy-preference` defaults to `:provider-native`.
- Omitted `:fallback` defaults to `:prompted-json`.
- `:require-provider-native? true` forbids fallback even if `:fallback :prompted-json` is present; unsupported-native detection happens in turn execution / AI strategy selection after resolved model/provider capability is known, and the workflow propagates that as a clear step or judge failure.
- The workflow envelope stores metadata for debugging, but downstream source resolution remains limited to validated `:value`.
- Workflow envelope naming follows AI structured-output metadata: `:payload` is parsed/native structured data before local coercion and is the validation input; `:raw-payload` is copied only for raw provider text/payload diagnostics.

## Risks

- Existing turn execution seams may not return structured-output metadata uniformly for streaming and non-streaming paths. Prefer adding one explicit result field over model-id inference.
- Native providers may return structured payload without assistant text. Envelope parsing must validate from payload when text is absent.
- Workflow fixtures that only declare Malli schemas must be updated with paired JSON Schema before native/fallback request tests can pass.
