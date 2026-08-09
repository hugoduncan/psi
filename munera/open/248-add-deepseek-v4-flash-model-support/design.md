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
- **Provider-scoped API-key resolution for OpenAI Codex responses (review
  13):** the `:openai-codex-responses` transport — the third custom
  `ModelDef` `ApiProtocol` — received the same provider-scoped key
  resolution (reviews 3/10 left it falling back to the global
  `OPENAI_API_KEY` unconditionally, so a custom codex provider with no
  configured key silently sent the user's OpenAI credential to the
  third-party `:base-url`). `codex_responses/build-codex-request` now
  resolves the key provider-scoped (built-in `:provider` nil/`:openai` keep
  the env fallback; custom codex providers fail fast with the provider-scoped
  "Missing API key" error naming the models.edn `:auth` remedy, no `/login`
  hint), and the keyless exemptions apply: `:no-auth-header` or a recognized
  auth header among custom `:headers` with no configured key builds a request
  without `Authorization` or `chatgpt-account-id` (the account-id requirement
  is waived for keyless requests). This closes the last remaining
  cross-provider credential disclosure class on the custom-provider schema.
- **OAuth content-sniff gating (review 11):** `oauth?` in
  `providers/anthropic.clj` is now `(and (builtin-anthropic? model)
  (oauth-api-key? api-key))` — a custom `:anthropic-messages` provider whose
  configured key merely contains `sk-ant-oat` always uses `x-api-key` auth
  and never receives the Claude Code OAuth headers (`Authorization: Bearer`,
  `user-agent: claude-cli/…`, `x-app`) or the `claude-code-system-prompt`
  prepended as the first system block. This is a custom-provider header
  behavior change: pre-review-11, an OAuth-shaped key on a custom provider
  leaked the Claude Code identity headers/system prompt to the third-party
  endpoint.
- **Case-insensitive capture redaction (reviews 7/11/13):** provider request
  captures now redact auth headers case-insensitively via a shared
  `find-header` helper on both transports — mixed-case `X-API-Key`,
  lowercase `authorization`, and `chatgpt-account-id` are redacted instead of
  leaking verbatim into the `:on-provider-request` capture payload — and the
  OpenAI transport's `redact-authorization` strips a leading `Bearer `
  before counting (length metadata consistent with the anthropic transport).
  This changes the capture payload (redaction is a security improvement, not
  a request-shaping change).
- **Custom-provider origin tagging (review 14):** `expand-model` tags every
  custom models.edn model `:custom? true`, and built-in detection
  (`builtin?`/`builtin-anthropic?`) requires the tag in addition to the
  provider name — a custom provider literally named `"anthropic"`/`"openai"`
  can no longer be classified built-in, so it never falls back to the
  user's `ANTHROPIC_API_KEY`/`OPENAI_API_KEY` and never receives
  built-in-only treatment (e.g. Claude Code OAuth headers).
- **Shared provider request-support namespace (review 14):** the
  provider-scoped key resolution, keyless-auth detection, auth-header
  recognition and capture-redaction primitives (previously triplicated
  across the three transports) now live in
  `providers/request_support.clj`, parameterized by the built-in provider
  keyword + env var name. Pure refactor — no behavior change.
- **Request-time `env:` key resolution (review 26):** custom-provider
  `:api-key "env:VAR"` specs are stored RAW in the registry (not resolved
  at models.edn parse time) and re-resolved through `getenv` per request by
  the shared `request-support/resolve-api-key` — matching the built-in env
  fallback's live semantics, so exporting the var after psi has loaded
  models.edn works without a reload. The custom-provider missing-key error
  now names the unset variable when the configured spec is an `env:` string.
- **Shared built-in OpenAI chat-completions classification (review 26):**
  agent-session's mid-conversation system-message inference
  (`model_capabilities.clj`) now uses the shared
  `request-support/builtin-openai-chat-completions?` predicate instead of
  an inline `(and (= :openai provider) (= :openai-completions api)
  (not :custom?))` copy, so the origin-tag built-in-classification
  semantics (review 14) live in one place. Pure refactor — no behavior
  change.
- **Session-stored runtime API-key provider scoping (review 35):** the
  session's `:runtime-api-key` (recorded at prompt prepare from the previous
  turn's resolved `:ai-options :api-key`) is now stored together with the
  provider it was resolved for (`:runtime-api-key-provider`) and reused only
  while the session's current model provider still matches — a mid-session
  `/model` or session-profile provider switch resolves the new provider's
  own auth instead of injecting the prior provider's raw key spec/literal
  key/OAuth token into the new provider's endpoint. An unscoped stored key
  (legacy session data without a recorded provider) is never reused. This is
  an agent-session session-data scoping change (not a provider-transport
  change): it closes the last cross-provider credential-disclosure class —
  via session-data — after reviews 3/10/13 closed the env-var fallback and
  review 11 the OAuth content-sniff.
- **Session-stored runtime API-key origin + staleness hardening
  (review 36):** two refinements to the review-35 scoping. (1) Origin
  scoping: prompt prepare now records `:runtime-api-key-custom?` (whether
  the session model was a custom models.edn provider at prepare time,
  resolved via the registry's `:custom?` origin tag), and the reuse check
  requires BOTH the provider AND the built-in/custom origin to match — a
  custom models.edn provider literally named `"anthropic"`/`"openai"`
  (same session provider string as the built-in, tagged `:custom? true`)
  can no longer reuse a key recorded for the built-in same-named origin
  (e.g. a built-in OAuth token sent as plain `x-api-key` to the custom
  provider's third-party endpoint), and vice versa. (2) Staleness: the
  stored key is no longer a self-perpetuating fixed point — it is reused
  only when it is NOT contradicted by the current provider-auth resolution
  (a models.edn `:auth` change + `/reload-models` wins over the stale
  stored spec; an OAuth refresh wins over the old token), while a nil
  current resolution (e.g. an RPC/extension-threaded key that lives only in
  runtime-opts / session-data, not in provider-auth) lets the stored key
  keep the session working across continuation turns. Same-provider
  same-origin OAuth stability is preserved (provider-auth re-resolves the
  same token).
- **Session request-options origin gate for registry auth (review 42):**
  `provider-auth/provider-api-key` and `provider-auth/provider-request-options`
  resolved registry `:auth` purely by provider NAME
  (`model-registry/get-auth`), and `prompt_request/session->request-options` +
  `resolve-api-key` consumed them without the session model's `:custom?`
  origin tag — so the provider-name-collision class (closed at the transport,
  capability-inference and session-data layers by reviews 14/25/26/27/36)
  remained open at the session request-options layer: a custom models.edn
  provider literally named `"anthropic"`/`"openai"` keys the registry
  `:auth` entry by that provider name, and a session running the BUILT-IN
  same-named model inherited the custom provider's auth config (custom
  headers / `:no-auth-header` / api-key spec sent to the built-in's
  endpoint). Both functions now take the resolved model's `:custom?` origin
  tag: registry auth is consulted only for custom models, and OAuth only
  for built-in models — so a built-in same-named model resolves only
  env/OAuth (never the custom provider's registry auth), and a custom
  provider named `"anthropic"`/`"openai"` never receives the built-in
  same-named OAuth credential. `runtime/resolve-api-key-in` threads the
  resolved model's `:custom?` into `provider-api-key` the same way.
- **Mid-stream SSE error-event surfacing + no-further-events-once-done
  guard (reviews 43/44/46):** both the `:anthropic-messages` transport
  (`stream-anthropic`'s `"error"` SSE case branch) and the
  `:openai-completions` transport (`emit-chat-error!`/`process-chat-sse-line!`
  in chat_completions.clj) now surface a mid-stream provider SSE error as an
  `:error` event and terminate the stream — previously such events fell to
  the stream loop's default no-op, so a mid-stream provider failure hung the
  turn until `llm-stream-idle-timeout-ms` with a misleading timeout (the
  codex transport already handled both shapes). Review 44 completed the
  terminal guard: the `message_delta` branch's terminal `:done` emission is
  guarded on `done?` (a trailing `message_delta` carrying `stop_reason`
  after a mid-stream SSE error no longer emits a second terminal event, and
  its usage accumulation + structured-output-result emissions are suppressed
  too), and both stream catch blocks' `:error` emission is guarded on
  `done?` (a post-error stream-read exception cannot emit a second
  `:error`). Review 46 extended the guard from terminal emissions to NO
  further event at all: the whole SSE dispatch is short-circuited on `done?`
  on all three transports (`stream-anthropic`'s event `case`,
  `process-chat-sse-line!`, and `handle-codex-event!` — the latter had no
  `done?` check at its top), so a post-error trailing event — a
  `content_block_stop`/`content_block_delta`/`content_block_start` (which
  previously still emitted `:text-end`/`:text-delta`/`:text-start` and could
  fire `maybe-emit-structured-result!`), a `:choices` chunk (`:text-delta`),
  a codex `response.output_text.delta` (`:text-delta`), a `[DONE]`/finish
  chunk (unguarded `force-start-pending-chat-tools!`/
  `emit-chat-tool-ends!`/`emit-structured-output-result!`) — is a full
  no-op, and `done?` is set on the anthropic `message_stop` terminal too —
  exactly one terminal event (`:error` or `:done`) per stream and nothing
  after it, mirroring the codex transport's `emit-codex-error!`.
- **Thinking-block stop labeling (review 43):**
  `content-block-stop-event` now emits `:thinking-end` for `"thinking"`
  content-block stops instead of the mislabeled `:text-end` (tool_use
  stops still emit `:toolcall-end`, text stops `:text-end`), so the turn
  accumulator's dedicated `:on-thinking-end` handler
  (`note-last-provider-event!` `:thinking-end` + `end-content-block!`) runs
  for anthropic-path thinking-block stops — DeepSeek returned a `thinking`
  content block in the live smoke test (2026-08-09), so the mislabel was
  reachable on this task's newly shipped provider.
- **Streamed usage on the message_stop terminal + SSE error status
  extraction (review 47):** two `stream-anthropic` follow-up fixes to the
  review-43/44/46 SSE handling. (a) The `message_stop` terminal `:done`
  now carries `:usage (usage-with-cost model usage-acc)` like the
  `message_delta`-with-`stop_reason` terminal — a stream terminating via
  `message_stop` WITHOUT a preceding `message_delta` carrying `stop_reason`
  previously emitted a bare `{:type :done :reason :stop}`, so
  `handle-done!` (`(map? usage)` false) recorded ZERO usage/cost even
  though `usage-acc` held the input + cache tokens accumulated from
  `message_start`; reachable on any Anthropic-compatible endpoint that
  omits `message_delta` (or sends it without `stop_reason`/`usage`) —
  including the newly shipped DeepSeek provider whose STREAMING path is
  unverified (the review-1 smoke test exercised only the non-streaming
  path). (b) The mid-stream SSE `"error"` branch's http-status extraction
  now mirrors the sibling transports' `emit-chat-error!` /
  `codex-error-http-status` — `:status` / `[:error :status]` /
  `[:error :http_status]`, numeric `>= 400` only — instead of reading
  `[:error :http_status]`/`:http_status` only, so a status-carrying error
  event (e.g. `{"error":{"status":529,...}}`, a generic message plus a
  `status` key, or a string status) keeps a numeric `:http-status` and
  downstream `retry-error?`/`provider-error-kind` classify a transient
  mid-stream 5xx/overload as retryable instead of `:unknown` (the
  review-23 class the openai transports already handle).
- **HTTP-400 compatibility retry OAuth decision (review 22):**
  `handle-400-response!`'s `:without-all-betas` selection now uses the
  transport's COMPUTED OAuth decision — `build-request` attaches the
  computed `oauth?` boolean (built-in Anthropic model + OAuth-shaped key,
  review 11) to the request map as `::oauth?`, and the 400-fallback's
  beta-config reads it — instead of content-sniffing the merged request
  headers for the three Claude Code CLI markers (`Authorization: Bearer …`,
  `user-agent: claude-cli/…`, `x-app: cli`). A keyless custom provider whose
  custom `:headers` reproduce that marker set is no longer classified OAuth:
  on a beta-related 400 it now selects `:without-all-betas` (all beta
  headers stripped on the retry, custom headers preserved) instead of
  retaining every beta, repeating the same 400 and hard-failing. This is a
  custom-provider behavior change with its own CHANGELOG `Fixed` entry; the
  content-sniffing `oauth-auth-request?` predicate remains for error
  diagnostics only.
- **Shared keyless-predicate unification (review 22):**
  `request-support/resolve-api-key`'s keyless early-return now uses the
  shared `no-auth?` predicate (`:no-auth-header`, or a recognized
  `x-api-key`/`Authorization` header among custom `:headers` with no
  configured key) instead of testing `:no-auth-header` alone, so the
  function is safe for direct callers and the keyless contract lives in one
  predicate. Pure refactor — no behavior change (all real callers already
  gate on `no-auth?` first).

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
- **Mid-conversation system-message capability field (review 22, pulled into
  scope):** add `[:supports-mid-conversation-system-messages {:optional true}
  [:maybe boolean?]]` to the `ModelDef` schema in
  `components/ai/src/psi/ai/user_models.clj` (the canonical `Model` schema
  already declared it; models.edn custom providers could not declare it at
  all). It flows through `expand-model`'s verbatim merge, is documented in
  `doc/custom-providers.md` (what it gates, the `:anthropic-messages`
  default-false, the built-in-only `:openai`/`:openai-completions`
  inference), and the DeepSeek example notes advise setting it only after
  verifying the endpoint honours per-turn `system` changes.
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
- `ModelDef` in `user_models.clj` accepts `:adaptive-thinking true/false`
  (and the review-22 `:supports-mid-conversation-system-messages true/false`
  field) and the parsed model map carries it through unchanged; omitting it
  remains valid and behaves exactly as today (falsy → classic extended
  thinking / no mid-conversation-system-message capability).
- A unit test proves the `:anthropic-messages` transport builds a correct
  request (headers, URL, body) for a DeepSeek-shaped custom-provider model
  map, including the adaptive `output_config.effort` shape when
  `:adaptive-thinking true`. No change to
  `providers/anthropic.clj`'s request-shaping logic itself (thinking/
  adaptive/temperature/tools/headers) — only the schema gate in
  `user_models.clj` plus the review-driven provider-transport changes
  documented in the revision note.
- No existing built-in Anthropic model request shaping changes, and no
  custom-provider behaviour changes except the review-driven changes
  documented in the revision note (provider-scoped API-key resolution,
  `:no-auth-header` key tolerance, OAuth content-sniff gating to built-in
  models, case-insensitive capture redaction, the `:custom?` origin tag
  closing provider-name-based built-in detection, the HTTP-400-compatibility-
  retry OAuth decision (computed `::oauth?` from `build-request`, replacing
  the header content-sniff), mid-stream SSE error-event surfacing + the
  no-further-events-once-done guard on all three transports, `:thinking-end`
  labeling for thinking-block stops, and the review-47 streamed-usage-on-the-
  `message_stop`-terminal + SSE error `:status`-key extraction fixes);
  `gpt-5.5`/`gpt-5.6-*`/
  Opus 4.7/4.8/5 request shaping is unaffected.
- `bb test` green; `clj-kondo` clean.
- CHANGELOG `[Unreleased]` → `Added` entry.

Design is complete and unambiguous; ready for planning.
