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
- **Injectable nullable provider HTTP boundary (test review 61):** provider
  stream/execute requests now cross an explicit `:http-boundary` option,
  defaulting to the production `clj-http` adapter. The production boundary
  also supplies a configurable nullable implementation that consumes scripted
  HTTP/SSE responses and records requests through its public API. Task-added
  stream tests use this boundary instead of globally redefining
  `clj-http.client/post`; the deterministic Codex terminal-balancing proofs
  retain the discriminating content indices `2`/`100` on both `:done` and
  `:error` paths.
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
- **EOF-level terminal flush + usage attachment + redacted_thinking
  typing + done?-first reset (reviews 48/49):** four `stream-anthropic` /
  `stream-openai` follow-up fixes. (a) Both non-codex transports now flush
  the terminal `:done` at EOF, mirroring the codex transport's post-doseq
  `(when-not @(:done? ...) ...)`: a stream that EOFs without an in-band
  terminal event (`message_stop`/`message_delta`-with-`stop_reason`/
  `"error"` on anthropic; a usage chunk / finish_reason+`[DONE]` / error
  chunk on openai) previously emitted no `:done`/`:error` and hung the turn
  until `llm-stream-idle-timeout-ms` — directly task-relevant since review
  47 established DeepSeek's streaming path is unverified. The openai flush
  emits the pending finish reason (else `:stop`) and attaches the last-seen
  usage chunk; the anthropic flush reuses the message_stop terminal helper
  (`:stop`, review-47 usage-with-cost shape). (b) The openai transport now
  accumulates the last-seen usage chunk so the flushed terminal `:done`
  carries it when one was seen; a usage-omitting endpoint's `:done` carries
  no `:usage` (zero usage/cost — the documented consequence). (c)
  `"redacted_thinking"` content blocks (Anthropic's first extended-thinking
  block) are skipped in `content-block-start-event` /
  `content-block-stop-event` (nil event, guarded by `consume-event!` at both
  call sites) instead of being mislabeled `:text-start`/`:text-end` — the
  review-43 typing fix's completion for the built-in Anthropic path (not
  reachable on DeepSeek). (d) `emit-terminal-done!` (shared by the
  `message_stop` branch and the EOF flush) resets `done?` FIRST — before
  the structured-output-result emissions and the `:done` consume — so a
  downstream exception during the terminal processing cannot propagate to
  the outer catch with `done?` still false and emit a second `:error`
  (the double-terminal class eliminated on every other terminal path).
- **`:start`-before-terminal emission + explicit redacted_thinking delta
  skip (review 50):** two `stream-anthropic` / `stream_events.clj`
  follow-up fixes. (a) `stream-anthropic` now tracks `started?` and emits
  `:start` once before the terminal when the stream never received
  `message_start` — `emit-terminal-done!` (shared by the `message_stop`
  branch and the review-48 EOF flush) and the `"error"` SSE branch emit
  `:start` first when not started, mirroring the sibling transports'
  `emit-chat-completion-finish!`/`emit-codex-start!` — so an empty/truncated
  body that EOFs before `message_start` or a malformed stream starting with
  `message_stop`/`"error"` yields `[:start :done]`/`[:start :error]`
  instead of `[:done]`/`[:error]`: the last three-transport asymmetry in
  the review-48 EOF-level flush (benign for the consumer — `:start` is a
  no-op handler — but a real cross-transport inconsistency in the exact
  class this task has repeatedly treated as actionable). (b) the
  `"redacted_thinking"` skip in `content-block-delta-event` is now an
  explicit branch returning nil (mirroring the start/stop branches) instead
  of the implicit fall-through that returned nil only because
  `redacted_thinking_delta` carries no `:text` — the delta skip is
  shape-independent, so a future delta with a `:text` key (or a renamed
  payload field) still emits no phantom `:text-delta` for a block whose
  start/stop are skipped.
- **Terminal events from the turn statechart's initial state + two openai
  error-surface alignments (review 51):** three follow-up fixes. (a) the
  turn statechart's `:idle` state now accepts `:turn/error` → `:error` and
  `:turn/done` → `:done` (mirroring the `:text-accumulating` /
  `:tool-accumulating` terminal transitions) — `:idle` previously accepted
  only `:turn/start`, so a direct `create-turn-context` consumer feeding a
  provider `:error`/`:done` as the FIRST event got a silent drop, `done-p`
  never delivered, and only the 20-minute `llm-stream-idle-timeout-ms`
  ended the turn (whose own `:turn/error` send was dropped too). Not
  reachable through the live-turn path (`create-live-turn-context` sends
  the turn-level `:turn/start` first) but closes a latent structural gap
  in the "exactly one terminal event per turn" invariant the
  review-43/44/46/48 CHANGELOG entries claim. (b) `emit-chat-error!`
  (`:openai-completions`) status extraction now also reads top-level
  `:http_status` (the review-47-aligned anthropic `"error"` branch reads
  that location too) — an OpenAI-compatible endpoint emitting a mid-stream
  error chunk with the status under a top-level `http_status` key
  (`{"http_status": 529, "error": {...}}`) keeps its numeric
  `:http-status` and downstream `retry-error?`/`provider-error-kind`
  classify a transient 5xx/overload as retryable instead of `:unknown`.
  (c) `stream-openai-codex`'s HTTP-error branch now passes the FULL error
  map (headers/body-text) through to `emit-codex-error!` (whose 4-arity
  already accepts headers, used by the SSE `response.failed`/`error`
  branches) instead of destructuring away `:headers` — a codex HTTP error
  (401/429/500 from the ChatGPT backend or a custom codex endpoint) now
  keeps its `request-id`-style headers on the `:error` event for
  diagnostics, the cross-transport error-surface inconsistency in the
  exact class this task's reviews 13/43/47 aligned.
- **`:start`-before-terminal completion on the error paths + codex
  catch-block headers + codex capture-once (review 52):** three follow-up
  fixes completing reviews 50/51. (a) The review-50 `:start`-before-terminal
  fix is extended to the ERROR paths and the remaining terminal gap:
  `emit-chat-error!` (`:openai-completions`) and `emit-codex-error!`
  (`:openai-codex-responses`) now emit `:start` first when the stream never
  emitted it (an error-FIRST stream — the error arrives before any output
  event; for codex this also covers the HTTP-error and exception paths that
  share the emitter), and the anthropic `message_delta`-with-`stop_reason`
  terminal branch emits `:start` first when the stream never received
  `message_start` (review 50 tested `message_stop`-first and empty-body,
  not `message_delta`-first) — so an error-first stream yields
  `[:start :error]` and a `message_delta`-first stream `[:start :done]`
  on all three transports, closing the last `:start`-before-terminal gaps
  in the review-50 class. (b) `stream-openai-codex`'s catch block (a
  stream-read exception surfaced via `exception->error`) now passes
  `:headers` through to `emit-codex-error!`'s 4-arity instead of
  destructuring them away — an exception whose ex-data carries response
  headers keeps them on the `:error` event, the review-51 one-line class on
  the sibling catch branch. (c) codex mid-stream SSE errors
  (`response.failed`/`error`) are captured exactly once — the raw-event
  capture in `handle-codex-event!` is skipped for the error event types, so
  only the constructed `:error` (with normalized `:http-status`/`:headers`)
  is captured via `emit-codex-error!`, matching the codex HTTP-error path
  and giving a capture-count-consistent transport set (anthropic/openai
  capture the raw line once; codex the constructed error once).
- **Catch-block `:start`-before-terminal on stream-read exceptions (review
  53):** the last gap in the review-50/52 `:start`-before-terminal class —
  `stream-anthropic`'s and `stream-openai`'s outer CATCH blocks (a
  stream-read exception before any output event, e.g. a connection reset on
  the first read) previously emitted `[:error]` with no preceding `:start`
  (the codex catch already gets `:start` via `emit-codex-error!`'s review-52
  `emit-codex-start!`), while every in-band terminal/error emitter had been
  fixed to emit `[:start ...]`. Both catch blocks now emit `:start` once
  (compare-and-set on the started?/stream-started? atoms — the anthropic
  side via the shared top-level `emit-start!` helper, moved out of the
  letfn so the out-of-scope catch can use it) before the `:error`, so a
  first-read exception yields `[:start :error]` on all three transports.
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
- **Content-block `:start` + unknown-index skip + shared `:start` emitter
  (review 54):** `stream-anthropic`'s content-block branches
  (`content_block_start`/`content_block_delta`/`content_block_stop`) now
  emit `:start` once before the first content event when the stream never
  received `message_start` (the non-terminal half of the review-50
  `:start`-before-first-event class — previously a content-block-first
  stream emitted `:start` only at the terminal, AFTER the content events,
  unlike the openai/codex siblings). `content_block_delta`/`content_block_stop`
  for an UNKNOWN index (no prior `content_block_start`) are now skipped
  instead of emitting unbalanced phantom `:text-delta`/`:text-end` for a
  block that never had a `:text-start`. The `:start`-once emitter is
  extracted to a shared `request-support/emit-start!` used by all three
  transports (was three byte-identical per-transport copies — the
  review-14 triplication class). Pure event-emission changes — no
  request-shaping change, no custom-provider config change; the only
  cross-transport inconsistency class this task has repeatedly treated as
  actionable.
- **Terminal open-block balancing at EOF (review 55):** the anthropic and
  openai chat-completions EOF-flush terminals now close content blocks /
  tool calls that were started but never stopped before the terminal
  `:done` — `stream-anthropic` tracks open content-block indices
  (conj on a consumed start, dissoc on stop) and `emit-terminal-done!`
  emits the matching `:toolcall-end`/`:thinking-end`/`:text-end` for each
  open index before the `:done` (mirroring codex's `open-tool-indexes`
  doseq); the chat-completions EOF flush calls
  `force-start-pending-chat-tools!` + `emit-chat-tool-ends!` (the exact
  helpers the finish-reason branches use) so a truncated stream degrades
  like a finish_reason-terminated one. The turn accumulator never
  finalizes with an OPEN block index (the no-phantom-or-unbalanced-block
  invariant reviews 43/48/50 asserted, via the EOF path).
- **Open-block/tool balancing on the error + message_delta terminals
  (review 56):** the review-55 balancing covered only the `:done`/EOF
  paths — a stream ending with a mid-stream provider error (an Anthropic
  SSE `error` event, an OpenAI chat-completions error chunk, a codex
  `response.failed`/`error`, or a stream-read exception on any transport)
  finalized the turn accumulator with OPEN block/tool indices, and the
  Anthropic `message_delta`-with-`stop_reason` terminal (an inline `:done`
  separate from `emit-terminal-done!`, kept inline to preserve the real
  `stop_reason`) did not balance. The balancing is now applied before the
  `:error` on every error path (anthropic: shared `balance-open-blocks!`
  helper used by the `"error"` branch, the `message_delta` terminal and
  the catch block; chat-completions: `force-start-pending-chat-tools!` +
  `emit-chat-tool-ends!` in `emit-chat-error!` and the catch block; codex:
  `emit-codex-error!` doseqs `:toolcall-end` over `open-tool-indexes`
  like `emit-codex-done!`) — the HTTP-error paths need no balancing (no
  SSE line has been consumed before they fire, so no block/tool is open).
  The no-phantom-or-unbalanced-block invariant now holds at every
  terminal, `:done` or `:error`.
- **Deterministic Codex terminal balancing (review 58):** the shared Codex
  terminal-balancing helper closes multiple open tool calls in ascending
  content-index order before both `:done` and `:error`. Open tool indices
  are stored as a set, whose traversal order is not a sequencing contract;
  sorting at the shared boundary makes the provider event stream
  deterministic and replayable and prevents the done/error paths from
  drifting. Tests open indices in an insertion order whose persistent-set
  traversal differs from numeric order and require ordered `:toolcall-end`
  events before each terminal.

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
  labeling for thinking-block stops, the review-47 streamed-usage-on-the-
  `message_stop`-terminal + SSE error `:status`-key extraction fixes, the
  review-48/49 EOF-level terminal flush + openai usage attachment +
  redacted_thinking block typing + done?-first reset fixes, the review-50
  `:start`-before-terminal emission + explicit redacted_thinking delta
  skip, the review-51 turn-statechart `:idle` terminal transitions +
  openai top-level `http_status` extraction + codex HTTP-error header
  preservation, and the review-52 `:start`-before-terminal completion on
  the error paths (`emit-chat-error!`/`emit-codex-error!` + the anthropic
  `message_delta` terminal) + codex catch-block header pass-through +
  codex mid-stream-error capture-once, and the review-53 catch-block
  `:start`-before-terminal on stream-read exceptions, the review-54
  content-block-first `:start` emission + unknown-index content-block skip
  (with the `:start`-once emitter extracted to the shared
  `request-support/emit-start!`), and the review-55/56 terminal open-block
  balancing on the anthropic and openai chat-completions transports
  (`:toolcall-end`/`:thinking-end`/`:text-end` for blocks started but
  never stopped, at every terminal — the EOF flush / `message_stop` /
  `message_delta`-with-`stop_reason` `:done` AND every error path: the
  mid-stream SSE error branches, the HTTP-400/stream-read exception
  catch blocks, `emit-chat-error!`/`emit-codex-error!`), and the
  review-57 non-streaming execute response-mapping fix (a `tool_use`
  content block in a non-streaming `execute-anthropic` response now maps
  to a `:tool-call` block — id/name/arguments with `:input` JSON-encoded —
  instead of being silently dropped, and `thinking` blocks are preserved
  as `:thinking` too, in wire order, mirroring the streaming accumulator
  and the `:openai-completions` sibling; `:stop-reason :tool_use` was
  already preserved, so the turn runtime now classifies the turn
  `:turn.outcome/tool-use` and the tool call executes instead of being
  silently lost on `response-mode :non-streaming` sessions with tools),
  and review-58 deterministic Codex terminal balancing (multiple open tool
  calls close in ascending content-index order before both `:done` and
  `:error`, never in the unspecified traversal order of the backing set),
  plus test-review-61's injectable nullable provider HTTP boundary (production
  defaults to real `clj-http`; tests configure scripted responses and inspect
  recorded requests without globally redefining infrastructure));
  `gpt-5.5`/`gpt-5.6-*`/
  Opus 4.7/4.8/5 request shaping is unaffected.
- `bb test` green; `clj-kondo` clean.
- CHANGELOG `[Unreleased]` → `Added` entry.

Design is complete and unambiguous; ready for planning.
