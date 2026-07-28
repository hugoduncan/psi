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

## Notes for the design-step task

Relevant project files (non-task):
- `components/ai/src/psi/ai/models.clj` — built-in catalog. Existing `gpt-5.6`
  entry at ~L594 (context-window 1000000, input 6.0 / output 35.0); `gpt-5.5`
  at ~L578. New variant keys must also join
  `openai-chat-completions-native-model-keys` (~L636) for API classification.
- `components/ai/src/psi/ai/model_registry.clj` — `openai-oauth-codex-model-ids`
  (L180) and `openai-oauth-unsupported-model-ids` (L186); `openai-oauth-runtime-model`
  / `resolve-runtime-model` are the single join point. Reuse
  `structured-output/with-openai-codex-transport`; do not restate codex literals.

Principles to maintain when resolving the metadata design-step:
- Single source of truth: encode per-variant metadata once in the catalog; all
  surfaces read through it. No per-surface literals.
- Resolve the 272K-vs-larger-context/pricing conflict with an explicit chosen
  value per id; the existing `gpt-5.6` uses 1M/6.0/35.0 as one reference point —
  decide deliberately whether Codex-routed variants match or differ, don't inherit
  by accident.
- Keep the frozen scope: three variants only; bare `gpt-5.6` stays unsupported.

## Design-follow-up: catalog metadata resolution (2026-07-28)

Resolved the ambiguity-review design step. Authoritative source for per-variant
values: pi-mono `~/src/pi-mono/packages/ai/scripts/generate-models.ts`,
`missingOpenAiModels` block (~L2119) and the OpenAI short-context/long-context
sets (~L305–321). Facts discovered for downstream implementation:

- Variants **differ in pricing** (base flat rates): sol 5/30/0.5/6.25,
  terra 2.5/15/0.25/3.125, luna 1/6/0.1/1.25 (input/output/cacheRead/cacheWrite).
  These base rates differ from our existing bare `gpt-5.6` entry (6.0/35.0) — do
  **not** inherit gpt-5.6's numbers; use the per-variant table in design.md.
- pi-mono default context window for these ids is 272000 with an opt-in override
  to 1050000 that triggers long-context pricing (input×2, output×1.5) above the
  272K threshold. Our catalog schema is flat (no `:tiers`), so design fixes each
  variant at `:context-window 272000` with flat short-context rates — no tier
  modelling. If a future task wants the 1.05M window, it must add tier support
  first.
- pi-mono classes these as `openai-responses`; our catalog mirrors the working
  `gpt-5.5` control instead (`:api :openai-completions` + join
  `openai-chat-completions-native-model-keys`), consistent with the design's
  "mirror gpt-5.5" constraint and the Codex route.
- No thinking-level-map catalog field exists; reasoning effort comes from the
  shared Codex transport (probe echoed `reasoning.effort:medium`), same as
  `gpt-5.5`. No per-variant reasoning field needed beyond `:supports-reasoning true`.
