# Plan

## Approach

Implement task 170 as a narrow workflow-runtime wiring slice. Keep provider-specific request construction in the AI provider adapters from tasks 169 and 171. Workflow code should only pass a provider-neutral `:structured-output` request contract and record the returned strategy metadata in the workflow structured-output envelope.

## Order

1. Extend the normalized workflow structured-output spec/schema surface to accept the canonical policy keys: `:json-schema`, `:strategy-preference`, `:fallback`, and `:require-provider-native?`.
2. Add a small workflow-runtime helper that turns a structured output spec into the task-169 AI request map under `:structured-output`, failing clearly when `:json-schema` is missing and encoding required-native requests as fallback-forbidden without trying to inspect provider capability.
3. Extend the bounded turn-execution contract so canonical execution results may carry `:execution-result/structured-output`, and `execute-actor-turn!` / `execute-judge-turn!` expose that value as top-level `:structured-output` in their bounded result.
4. Populate `:execution-result/structured-output` from non-streaming provider result `:structured-output` and from accumulated streaming `:structured-output-strategy` / `:structured-output-result` events.
5. Wire session-step execution so structured `:outputs` pass that request map into actor-turn generation and read strategy/payload metadata only from the top-level bounded turn result `:structured-output` seam.
6. Wire LLM-judge execution so judge structured `:outputs` pass the same request map into judge-turn generation and read the same seam.
7. Extend structured-output envelope construction to accept AI structured-output metadata and native/raw payloads while preserving task-168 downstream `:value` semantics.
8. Add focused tests for provider-native, prompted fallback, required-native unsupported failure, missing JSON Schema failure, invalid local validation, downstream value resolution, turn-result seam usage, and persisted/replay metadata behavior.
9. Update `doc/workflow-grammar.md`, `doc/workflow-ir.md`, and `doc/workflows.md` with the final author-facing policy keys, JSON Schema boundary, and envelope metadata.

## Design decisions

- Workflow specs must provide explicit `:json-schema`; task 170 does not perform Malli-to-JSON-Schema conversion.
- Omitted `:strategy-preference` defaults to `:provider-native`.
- Omitted `:fallback` defaults to `:prompted-json`.
- `:require-provider-native? true` forbids fallback even if `:fallback :prompted-json` is present; `:fallback :none` also forbids fallback when native support is unavailable. Unsupported-native detection happens in turn execution / AI strategy selection after resolved model/provider capability is known, and the workflow propagates either fallback-forbidden case with the same clear `:unsupported-structured-output` step or judge failure surface.
- The workflow envelope stores metadata for debugging, but downstream source resolution remains limited to validated `:value`.
- The authoritative workflow-visible AI metadata seam is top-level `:structured-output` on the bounded result returned by `execute-actor-turn!` / `execute-judge-turn!`, copied from `:execution-result/structured-output`; workflow code must not recover this metadata from journals, captures, model ids, or assistant messages.
- Workflow envelope naming follows AI structured-output metadata: `:payload` is parsed/native structured data before local coercion and is the validation input; `:raw-payload` is copied only for raw provider text/payload diagnostics.
- Session-step structured-output contract failures (`:missing-json-schema`, unsupported required-native, invalid local validation) use blocked actor pending results with `:outcome :blocked`; LLM-judge structured-output contract failures use `:routing-result {:action :fail}` with the same stable reasons and do not enter prose no-match retry loops.

## Risks

- Existing turn execution seams may not return structured-output metadata uniformly for streaming and non-streaming paths. Task 170 resolves this by adding one explicit execution-result field and bounded turn-result field, not model-id inference.
- Native providers may return structured payload without assistant text. Envelope parsing must validate from payload when text is absent.
- Workflow fixtures that only declare Malli schemas must be updated with paired JSON Schema before native/fallback request tests can pass.
