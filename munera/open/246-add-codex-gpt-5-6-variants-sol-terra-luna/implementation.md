# Implementation log — 246

## Streaming probe evidence (pre-planning)

Probe: streaming POST to `https://chatgpt.com/backend-api/codex/responses`
(`stream:true`, `store:false`, `include:["reasoning.encrypted_content"]`,
`accept: text/event-stream`), using the live OpenAI ChatGPT-account OAuth token
(`chatgpt-account-id` extracted from the token). Structured status/body only —
no assertions. Probe script: `/tmp/psi-gpt56-codex-stream-probe.clj`. Run via
project REPL on 2026-07-28.

"Reached execution" = backend emitted `response.created` →
`response.in_progress` → `response.output_item.added` SSE events (request
accepted and began generating), rather than a terminal model-support rejection.

| model id        | status | outcome                                                                 |
| --------------- | ------ | ----------------------------------------------------------------------- |
| `gpt-5.5`       | 200    | reached execution (control — existing OAuth/Codex path)                 |
| `gpt-5.6`       | 400    | `The 'gpt-5.6' model is not supported when using Codex with a ChatGPT account.` (confirms task 245) |
| `gpt-5.6-sol`   | 200    | reached execution — events response.created/in_progress/output_item.added |
| `gpt-5.6-terra` | 200    | reached execution — events response.created/in_progress/output_item.added |
| `gpt-5.6-luna`  | 200    | reached execution — events response.created/in_progress/output_item.added |

### Conclusion

All three GPT-5.6 variants (`gpt-5.6-sol`, `gpt-5.6-terra`, `gpt-5.6-luna`) are
accepted and reach execution on the ChatGPT/Codex backend for a ChatGPT
account. The task-245 rule ("no id joins the Codex path without live backend
evidence") is satisfied for all three. Bare `gpt-5.6` remains rejected and must
stay unsupported under OpenAI OAuth.

Observed backend echo per variant: `store:false`, `service_tier:"auto"`,
`reasoning.effort:"medium"`, `reasoning.context:"all_turns"` (vs `gpt-5.5`'s
`current_turn`), `text.verbosity:"low"` — useful for catalog metadata during
implementation.

## Review notes

- architectural review: no architectural review feedback (design reuses the shared `model_registry.clj` codex join point + `with-openai-codex-transport`; additive catalog/policy-set slice mirrors existing `gpt-5.5` pattern; single-source-of-truth honored)
- ambiguity review: added 1 new design step (under-determined per-variant catalog metadata: same-vs-differ across sol/terra/luna, and 272K vs larger-context/long-context-pricing conflict for Codex-routed entries)
- inconsistency review: no inconsistency review feedback (design internally consistent and consistent with model_registry.clj codex/unsupported sets + implementation.md probe evidence; metadata 272K-vs-1M/larger-context tension already captured by ambiguity design step, not duplicated)
