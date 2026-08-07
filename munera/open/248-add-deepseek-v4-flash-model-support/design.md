# 248 — Add DeepSeek `deepseek-v4-flash` support via the Anthropic-compatible API

## Intent

Let a psi user select and run DeepSeek's `deepseek-v4-flash` model by
configuring DeepSeek as a custom provider (`models.edn`) against its
Anthropic-compatible endpoint, documented the same way as psi's other
Anthropic-compatible custom providers.

## Revision note

This design originally targeted DeepSeek's OpenAI-shaped **Responses API**
(`https://api.deepseek.com`, confirmed at
[api-docs.deepseek.com/guides/responses_api](https://api-docs.deepseek.com/guides/responses_api/)).
That would have required a brand-new generic Responses API transport, since
psi's only existing "Responses API" protocol (`:openai-codex-responses`) is
hard-coupled to the ChatGPT/Codex backend (OAuth `chatgpt_account_id`
extraction, forced `/codex/responses` URL suffix, ChatGPT-only headers) and
would not work against a plain DeepSeek API key.

DeepSeek also publishes an **Anthropic-compatible** endpoint
([api-docs.deepseek.com/guides/anthropic_api](https://api-docs.deepseek.com/guides/anthropic_api/)),
confirmed live (2026-07), which maps directly onto psi's existing, already
vendor-agnostic `:anthropic-messages` custom-provider transport — the same
protocol documented for MiniMax/arbitrary Anthropic-compatible proxies in
`doc/custom-providers.md`. This is a strictly simpler, lower-risk path to the
same outcome (DeepSeek's `deepseek-v4-flash` selectable in psi) and needs
**no new transport protocol** (the original Responses API transport is
dropped, not deferred — it is unnecessary for this goal). This design
supersedes the Responses API approach.

## Revision note (implementation reviews)

The implementation reviews added small, deliberate changes to the provider
transports beyond the original "no provider code changes" scope. They are the
*only* provider-transport changes in this task; the request-shaping logic
itself (thinking/adaptive/temperature/tools/headers) is otherwise unchanged.

- **Provider-scoped API-key resolution (review 3):** `anthropic/resolve-api-key`
  falls back to the `ANTHROPIC_API_KEY` env var only for built-in Anthropic
  models (`:provider` nil or `:anthropic`); custom `:anthropic-messages`
  providers fail fast with a provider-scoped "Missing API key for provider
  <name>" error when their configured key is nil/blank. This is an intended
  behavior change to custom-provider key resolution: it prevents a user's
  Anthropic key from being silently sent to a third-party endpoint. A
  `getenv` indirection makes the env fallback testable.
- **No-auth-header key tolerance (review 4):** custom `:anthropic-messages`
  providers with `:no-auth-header` set (`:auth-header? false`, the documented
  local-server/custom-headers pattern) no longer require an API key —
  `resolve-api-key` returns nil and `build-request` strips the auth headers,
  restoring the pre-review-3 behavior for keyless local-proxy configs.
  Custom-provider missing-key errors no longer hint at `/login` (OAuth login
  exists only for built-in providers).
- **Provider-scoped API-key resolution for OpenAI chat completions (review
  10):** the `:openai-completions` transport received the same
  provider-scoped key resolution the anthropic transport got in review 3 —
  `openai/chat_completions` `resolve-api-key` falls back to
  `OPENAI_API_KEY` only for built-in OpenAI models (`:provider` nil or
  `:openai`); custom `:openai-completions` providers fail fast with a
  provider-scoped "Missing API key" error (no cross-provider credential
  disclosure, no `/login` hint), and the same keyless exemptions apply
  (`:no-auth-header`, or a recognized `x-api-key`/`Authorization` header
  among custom `:headers` with no configured key). This closes the
  review-10-flagged asymmetry where a custom OpenAI-compatible provider with
  an unset key silently received the global `OPENAI_API_KEY`.

## Verified facts (DeepSeek docs, 2026-07)

From `/guides/anthropic_api`:

- `base_url: https://api.deepseek.com/anthropic`.
- Standard `anthropic` SDK, `x-api-key` auth — **"Fully Supported"**.
  `anthropic-beta` / `anthropic-version` headers are **"Ignored"** (not
  rejected — harmless either way).
- Model id passed straight through: DeepSeek's own example calls
  `client.messages.create(model="deepseek-v4-pro", ...)` directly (not via
  Claude-name mapping); `deepseek-v4-flash` is the sibling model id (the
  Responses-API guide independently names it explicitly, and the Anthropic
  guide's model-mapping table confirms `deepseek-v4-flash` as a first-class
  target: "unsupported model name" falls back to it).
- Compatibility table (full detail in the doc):
  - `system`, `stream`, `temperature`, `top_p`, `stop_sequences`,
    `max_tokens` — fully supported.
  - `thinking` — supported (`budget_tokens` sub-field ignored, but
    `type: "enabled"`/`"disabled"` is honoured, so on/off control works via
    psi's existing extended-thinking request shape).
  - `output_config.effort` — supported, but only reachable via psi's
    **adaptive**-thinking request shape (`:adaptive-thinking true` on the
    model map) — see "Known gap" below.
  - `tools` (name/input_schema/description) and `tool_choice` — fully/mostly
    supported; `cache_control` and `disable_parallel_tool_use` are ignored
    everywhere they appear (harmless no-ops, not errors).
  - Message content: text and `tool_use`/`tool_result` blocks fully
    supported; `thinking` content blocks supported; images, documents,
    search-result, and redacted-thinking blocks are **not** supported.
  - No `output_format`/JSON-Schema-native structured-output field is
    documented on this endpoint.

From `/quick_start/pricing`:

- `deepseek-v4-flash`: context length **1M** tokens, max output **384K**
  tokens.
- Pricing per 1M tokens: input (cache miss) **$0.14**, input (cache hit)
  **$0.0028**, output **$0.28**. No separate cache-write price is published
  (DeepSeek manages context caching automatically); the input/cache-miss rate
  is the effective write-path cost.
- Thinking mode: supports both non-thinking and thinking, thinking is the
  default. Tool calls and JSON output are marked supported at the model
  level (JSON output support is not confirmed as reachable through the
  Anthropic-compatible field set specifically — see "Resolved decision:
  structured output" below).

## Scope

In scope:

- A `doc/custom-providers.md` example for DeepSeek (new subsection alongside
  the existing MiniMax and Anthropic-compatible-proxy examples), giving a
  concrete `models.edn` provider definition:
  - `:base-url "https://api.deepseek.com/anthropic"`
  - `:api :anthropic-messages`
  - `:auth {:api-key "env:DEEPSEEK_API_KEY"}`
  - one model: `:id "deepseek-v4-flash"`, `:supports-reasoning true`,
    `:adaptive-thinking true` (see below), `:supports-images false`,
    `:supports-text true`, `:context-window 1000000`, `:max-tokens 384000`,
    `:input-cost 0.14`, `:output-cost 0.28`, `:cache-read-cost 0.0028`,
    `:cache-write-cost 0.14` (mirroring the cache-miss/input rate, since no
    distinct write price is published).
  - `:capabilities` omitted (defaults to unsupported structured output — see
    "Resolved decision" below).
- **Closing the adaptive-thinking custom-provider gap** (pulled into scope,
  see "Adaptive-thinking custom-provider support" below):
  - Add `:adaptive-thinking {:optional true} [:maybe boolean?]` to the
    `ModelDef` schema in `components/ai/src/psi/ai/user_models.clj`.
  - No `expand-model` change is needed: it already merges all of `model-def`
    (`dissoc model-def :name`) into the expanded model map, so the new field
    flows through purely from the schema change.
  - Document the new field in `doc/custom-providers.md` next to the
    Anthropic-compatible example.
- A focused unit test proving psi's existing Anthropic transport shapes a
  request correctly for this exact custom-provider model map: `x-api-key`
  header from the configured key (not OAuth path), `anthropic-version`
  present but no forced `anthropic-beta`, correct `base-url`-derived request
  URL, adaptive `output_config.effort` shape (not `budget_tokens`) when
  `:adaptive-thinking true`, and no code path assumes the model is a
  built-in Anthropic catalog entry. This is a request-shaping proof, not a
  live network call.
- Unit tests in `user_models_test.clj` proving: `:adaptive-thinking true` is
  accepted by `ModelDef` and flows into the expanded model map; the field
  remains optional/absent-safe (unset → falsy, matching current
  extended-thinking-by-default behaviour, no `model-defaults` entry needed).
- CHANGELOG `[Unreleased]` → `Added` entry noting the new documented
  DeepSeek custom-provider example and the new `:adaptive-thinking`
  custom-provider field.

Out of scope (adjacent, separate tasks if wanted):

- Any new `:api` transport protocol (the previously-scoped Responses API
  work — dropped, see "Revision note").
- `deepseek-v4-pro` (not requested; same pattern would apply if wanted
  later).
- Adding DeepSeek as a *built-in* `models.clj` catalog family (this task
  treats it as a custom `models.edn` provider, consistent with every other
  non-Anthropic/non-OpenAI vendor).

## Adaptive-thinking custom-provider support (resolved: in scope)

`components/ai/src/psi/ai/schemas.clj`'s canonical `Model` schema already
declares `[:adaptive-thinking {:optional true} boolean?]` — built-in catalog
models (Opus 4.7/4.8/5) already use it. The gap is narrower than a full
feature: `components/ai/src/psi/ai/user_models.clj`'s separate closed
`ModelDef` schema (used only to validate `models.edn` custom-provider model
definitions) does not list `:adaptive-thinking`, so a custom-provider model
definition that includes it currently fails schema validation.
`providers/anthropic.clj`'s `thinking-param`/`adaptive-thinking?` already
read `:adaptive-thinking` generically off any resolved model map — no
provider-transport code change is needed, only the schema gate.

Per DeepSeek's compat table, `output_config.effort` is supported and
`thinking.budget_tokens` is ignored (not rejected), so `deepseek-v4-flash`
is a correct, low-risk first user of this field: with
`:adaptive-thinking true`, psi sends the adaptive shape
(`{:type "adaptive" ...}` + `output_config.effort`) DeepSeek actually
honours, instead of the classic extended-thinking shape whose
`budget_tokens` DeepSeek silently ignores.

## Resolved decision: structured output

DeepSeek's pricing page marks "JSON Output" as a supported model feature, but
the Anthropic-compatible endpoint's own compatibility table does not document
a `output_format`/JSON-Schema-native field. Per `doc/custom-providers.md`,
omitting `:capabilities :structured-output` is valid and normalizes to
unsupported (no prompted-JSON fallback injected). Proposing: omit it in the
example, i.e. treat structured output as unsupported through this transport
for now, rather than guessing at an unverified native mechanism. A user who
independently confirms native JSON-Schema support can add
`:strategies [:prompted-json]` or a native mechanism themselves.

## Acceptance criteria

- `doc/custom-providers.md` documents a DeepSeek `models.edn` example
  matching the resolved fields above, including `:adaptive-thinking true`.
- `doc/custom-providers.md` documents the new `:adaptive-thinking` custom
  model field (what it does, when to set it, and that it is only meaningful
  for `:api :anthropic-messages` custom providers).
- `ModelDef` in `user_models.clj` accepts `:adaptive-thinking true/false` and
  the parsed model map carries it through unchanged; omitting it remains
  valid and behaves exactly as today (falsy → classic extended thinking).
- A unit test proves the `:anthropic-messages` transport builds a correct
  request (headers, URL, body) for a DeepSeek-shaped custom-provider model
  map, including the adaptive `output_config.effort` shape when
  `:adaptive-thinking true`. No change to
  `providers/anthropic.clj`'s request-shaping logic itself (thinking/
  adaptive/temperature/tools/headers) — only the schema gate in
  `user_models.clj` plus the review-driven API-key resolution changes
  documented in the revision note.
- No existing built-in Anthropic model request shaping changes, and no
  custom-provider behaviour changes except the review-driven provider-scoped
  API-key resolution and `:no-auth-header` key tolerance documented in the
  revision note; `gpt-5.5`/`gpt-5.6-*`/Opus 4.7/4.8/5 request shaping is
  unaffected.
- `bb test` green; `clj-kondo` clean.
- CHANGELOG `[Unreleased]` → `Added` entry.

Design is complete and unambiguous; ready for planning.
