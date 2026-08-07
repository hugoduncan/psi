# Custom providers

Psi supports custom LLM providers through `models.edn` files.

This lets you add providers such as MiniMax, Ollama, LM Studio, vLLM, llama.cpp,
or any other service that exposes an OpenAI-compatible or Anthropic-compatible API.

## File locations

You can define custom providers in either or both of these files:

- user-global: `~/.psi/agent/models.edn`
- project-local: `<worktree>/.psi/models.edn`

If the same custom provider/model pair appears in both places, the project-local
entry wins.

Built-in models remain available alongside custom ones.

## What a provider definition contains

Each provider entry defines:

- a provider id, such as `"minimax"` or `"ollama"`
- `:base-url` — the API root for that provider
- `:api` — which wire protocol psi should use
- optional `:auth` settings
- one or more `:models`

Supported custom-provider API protocols are:

- `:openai-completions`
- `:anthropic-messages`
- `:openai-codex-responses`

In practice, most custom hosted providers fit the first two.

## Structured output capability

Custom model definitions may opt into structured-output requests with a model-level capability map:

```clojure
{:capabilities
 {:structured-output
  {:supported? true
   :strategies [:prompted-json]
   :native-mechanism nil
   :notes "Use adapter-owned JSON-only prompt fallback."}}}
```

Omitting `:capabilities :structured-output` is valid and normalizes to unsupported. Psi will not inject prompted-JSON fallback instructions for omitted legacy/custom models; add `:strategies [:prompted-json]` when that behavior is wanted.

Native structured-output capability declarations should only be used when the configured transport is known to support the provider mechanism:

- `:openai-completions` may use `:native-mechanism :openai/chat-completions-json-schema-response-format` when the compatible API supports Chat Completions `response_format` JSON Schema.
- `:anthropic-messages` may use `:native-mechanism :anthropic/json-schema-output` when the compatible API supports Anthropic Messages `output_format` JSON Schema plus the `structured-outputs-2025-11-13` beta/header. This is the preferred Anthropic native mechanism for supported models.
- `:anthropic-messages` may use `:native-mechanism :anthropic/forced-tool-use` when the compatible API supports forced tool choice with `input_schema`. This is a separate native tool-use mechanism, not the only Anthropic structured-output path.
- `:openai-codex-responses` may use `:native-mechanism :openai/responses-text-format-json-schema` for the ChatGPT/Codex OAuth transport when the backend supports streaming Responses-style `text.format` JSON Schema. This mechanism is distinct from Chat Completions `response_format`; non-streaming Codex structured output is not established by Psi's current contract.

Structured-output requests must supply an explicit `:json-schema`; Psi does not convert Malli/domain schemas in the AI adapter. Prompted JSON remains fallback only. Local workflow/runtime validation remains mandatory after provider generation, and OAuth/API tokens must not be written into docs, task files, fixtures, logs, or commits.

## Textual tool-call compatibility

Some local model runners emit tool-call markup as assistant text instead of returning provider-native tool-call events. A custom/local model can opt into Psi's narrow recovery parser with:

```clojure
{:capabilities
 {:textual-tool-calls #{:xml}}}
```

The `:xml` format recognizes only the strict compatibility form:

```xml
<tool_call>
<function=bash>
<parameter=command>
pwd
</parameter>
</function>
</tool_call>
```

When enabled on the active runtime model, well-formed blocks are removed from assistant prose, converted into ordinary canonical tool calls, and then pass through the existing tool availability, authorization, execution, journaling, and result-recording path. Frontier/provider-native models should not enable this compatibility flag; leave the capability omitted unless the configured local runner is known to leak this textual markup.

The parser contract is deliberately strict:

- tag names are exact and lowercase: `<tool_call>`, `<function=...>`, and `<parameter=...>` only;
- tool and parameter identifiers must match `[A-Za-z0-9_-]+`; names with whitespace, quotes, colons/namespaces, dots, slashes, attributes, or entity encoding are not accepted;
- each `<tool_call>...</tool_call>` block must contain exactly one function block;
- all parameters must be inside that function block;
- each function must contain one or more parameters;
- duplicate parameter names and misnested/out-of-function parameter blocks make the candidate malformed;
- parameter text is trimmed at tag boundaries but otherwise preserved, including internal newlines and shell metacharacters;
- tag-looking textual-tool-call markup inside parameter text is unsupported and makes the candidate malformed, including `<tool_call>`, `</tool_call>`, `<function=...>`, `</function>`, `<parameter=...>`, and `</parameter>`;
- nested textual tool-call recovery is intentionally unsupported; a well-formed-looking block inside another candidate remains ordinary text and is not executed;
- malformed, partial, unsupported, or oversized markup remains ordinary assistant text and does not execute a tool;
- later independent well-formed blocks in the same assistant response are still recovered; and
- a single textual tool-call candidate block is supported up to 65,536 characters; longer blocks are intentionally left as text to keep malformed many-marker output bounded.

## OpenAI-compatible example: MiniMax

Illustrative example: confirm the provider's current base URL and model ids in
its own docs, then place a definition like this in `~/.psi/agent/models.edn` or
`.psi/models.edn`:

```clojure
{:version 1
 :providers
 {"minimax"
  {:base-url "https://api.minimax.chat/v1"
   :api      :openai-completions
   :auth     {:api-key "env:MINIMAX_API_KEY"}
   :models   [{:id                 "MiniMax-M1"
               :name               "MiniMax M1"
               :supports-reasoning true
               :supports-text      true
               :context-window     128000
               :max-tokens         16384
               :latency-tier       :medium
               :cost-tier          :medium}]}}}
```

Then export your key:

```bash
export MINIMAX_API_KEY=...
```

Notes:
- the provider id here is `minimax`
- psi will route requests through its OpenAI-compatible transport because `:api`
  is `:openai-completions`
- you can define multiple models under the same provider

## Anthropic-compatible example

If a provider exposes an Anthropic Messages-compatible API, configure it the
same way but set `:api` to `:anthropic-messages`.

```clojure
{:version 1
 :providers
 {"my-anthropic-proxy"
  {:base-url "https://example.com/anthropic"
   :api      :anthropic-messages
   :auth     {:api-key "env:MY_PROXY_API_KEY"}
   :models   [{:id                 "proxy-sonnet"
               :name               "Proxy Sonnet"
               :supports-reasoning true
               :supports-text      true
               :context-window     200000
               :max-tokens         8192}]}}}
```

For Anthropic-compatible providers, psi uses the Anthropic transport and will
send the configured key through the compatible auth path.

API-key resolution is provider-scoped: psi resolves a custom provider's key
from its own `:auth` configuration (literal or `env:VAR`) — it never falls
back to `ANTHROPIC_API_KEY`. If the configured key is unset/blank, the request
fails with a provider-specific "Missing API key" error instead of silently
sending your Anthropic key to the custom provider's endpoint. Only built-in
Anthropic catalog models fall back to the `ANTHROPIC_API_KEY` environment
variable.

### Adaptive thinking

Anthropic-compatible models may declare `:adaptive-thinking true` to opt into
Anthropic's adaptive-thinking request shape (the same one used by Claude Opus
4.7 and later): psi sends `output_config.effort` (derived from
`/thinking`/`/effort`) instead of the older `thinking.budget_tokens` shape.
This field is only meaningful for `:api :anthropic-messages` custom providers
(and built-in Anthropic catalog models) — it is ignored for OpenAI-compatible
(`:openai-completions` / `:openai-codex-responses`) custom providers. Only set
it when the compatible provider actually honours `output_config.effort` —
check its own compatibility docs first. Omitting the field (or setting it
`false`) keeps the classic extended-thinking shape, which remains the correct
default for most Anthropic-compatible providers.

Trade-off: adaptive-thinking models never send `temperature` — psi omits it
from the request body even when thinking is off, because Anthropic rejects
`temperature` on adaptive-thinking models. Declaring `:adaptive-thinking true`
therefore forfeits temperature control for that model; only set it when the
provider honours `output_config.effort` and you do not need per-request
temperature.

## DeepSeek-compatible example

DeepSeek exposes an Anthropic Messages-compatible endpoint at
`https://api.deepseek.com/anthropic` (see
[DeepSeek's Anthropic API guide](https://api-docs.deepseek.com/guides/anthropic_api/)),
so it configures like any other Anthropic-compatible provider. DeepSeek's
`deepseek-v4-flash` model supports Anthropic's adaptive-thinking
`output_config.effort` field (its `thinking.budget_tokens` is accepted but
ignored), so this example sets `:adaptive-thinking true`:

```clojure
{:version 1
 :providers
 {"deepseek"
  {:base-url "https://api.deepseek.com/anthropic"
   :api      :anthropic-messages
   :auth     {:api-key "env:DEEPSEEK_API_KEY"}
   :models   [{:id                 "deepseek-v4-flash"
               :name               "DeepSeek V4 Flash"
               :supports-reasoning true
               :adaptive-thinking  true
               :supports-images    false
               :supports-text      true
               :context-window     1000000
               :max-tokens         384000
               :input-cost         0.14
               :output-cost        0.28
               :cache-read-cost    0.0028
               :cache-write-cost   0.14}]}}}
```

Then export your key:

```bash
export DEEPSEEK_API_KEY=...
```

Notes:
- pricing/context-window figures above are from DeepSeek's published pricing
  page as of this writing; confirm current figures in DeepSeek's own docs
  before relying on them for cost tracking
- DeepSeek's compat table lists `temperature` as fully supported, but
  `:adaptive-thinking true` forfeits temperature control — psi never sends
  `temperature` for adaptive-thinking models (even with thinking off). If you
  need temperature control, set `:adaptive-thinking false` (or omit it) and
  rely on the classic extended-thinking shape DeepSeek accepts (it honours
  `type: "enabled"` and ignores `budget_tokens`)
- `output_config.effort` is confirmed supported (DeepSeek's compat table:
  "output_config: Only effort is supported"; the Thinking Mode guide
  documents the Anthropic-format effort control as
  `{"output_config": {"effort": "low/high/max"}}`), but the `thinking.type
  "adaptive"` value psi pairs it with is NOT among DeepSeek's documented
  honored values — the Thinking Mode guide documents the Anthropic-format
  thinking toggle as `{"thinking": {"type": "enabled/disabled"}}` only, and
  "adaptive" appears nowhere in DeepSeek's Anthropic API docs (verified
  2026-08-07). What DeepSeek does with `type: "adaptive"` is unverified: a
  strict endpoint may reject it (400); a lenient one may ignore it, leaving
  thinking ON (DeepSeek's default) with the effort applied. Verify against a
  live turn (blocked: no `DEEPSEEK_API_KEY` in env) before relying on the
  adaptive shape; if DeepSeek rejects it, fall back to
  `:adaptive-thinking false` — the classic shape's `type: "enabled"` IS a
  documented honored value (`budget_tokens` is ignored).
  Also note psi's effort values vs DeepSeek's documented set: the Thinking
  Mode guide documents Anthropic-format effort as `"low/high/max"`, but
  psi's adaptive path emits `"low"` (`/thinking minimal` or `/thinking
  low`), `"medium"` (`/thinking medium`), `"high"` (`/thinking high`) and
  `"highest"` (`/thinking xhigh`, and `effort-override :xhigh`) — it never
  emits `"max"`. `"low"` and `"high"` are within DeepSeek's documented set;
  `"medium"` and `"highest"` are undocumented (a strict endpoint may 400, a
  lenient one may map them unpredictably), and `"highest"` does not
  correspond to DeepSeek's `"max"`. Unverified live (blocked: no
  `DEEPSEEK_API_KEY` in env); until verified, prefer `/thinking minimal` /
  `/thinking low` or `/thinking high` for documented-safe effort values.
- thinking-off is not honoured through the omitted-field path: psi never sends
  an explicit thinking-disabled signal — when `/thinking off` is active it
  simply omits the `thinking` field. On Anthropic's own API omission means
  thinking disabled, but DeepSeek's Anthropic-compatible endpoint defaults to
  thinking ON, so an omitted `thinking` field leaves thinking enabled and
  `/thinking off` on `deepseek-v4-flash` is silently ignored (with or without
  `:adaptive-thinking`). If you need thinking-off control on DeepSeek, verify
  against a live turn whether the endpoint honours an explicit
  `thinking: {:type "disabled"}` (psi does not emit it today) before relying
  on it.
- API keys are provider-scoped: a custom `:anthropic-messages` provider never
  falls back to the `ANTHROPIC_API_KEY` env var. If the provider's configured
  `:api-key` (e.g. `env:DEEPSEEK_API_KEY`) resolves nil and the provider does
  not declare `:auth-header? false` (or carry a recognized
  `x-api-key`/`Authorization` header in custom `:headers`), the request
  fails fast with a provider-scoped "Missing API key" error — your Anthropic
  key can never be sent to `https://api.deepseek.com/anthropic/v1/messages`.
  Only built-in Anthropic models fall back to `ANTHROPIC_API_KEY`. (Keyless
  exemptions: `:auth-header? false`, or a recognized auth header among
  custom `:headers` with no configured key — see "Local servers and custom
  headers".)
- cache-cost fields are illustrative: psi bills cache usage from
  Anthropic-shaped `usage.cache_read_input_tokens` (at `:cache-read-cost`)
  and `usage.cache_creation_input_tokens` (at `:cache-write-cost`). DeepSeek
  publishes no separate cache-write price, so `:cache-write-cost 0.14` mirrors
  the cache-miss/input rate as the effective write-path cost (Anthropic-style
  accounting reports the write/miss portion separately from `input_tokens`, so
  this does not double-count the miss). This assumes DeepSeek's usage payload
  uses those Anthropic field names — not yet verified against a live payload;
  if DeepSeek reports usage in its native OpenAI-style shape, cache costs may
  not be captured and the example costs should be adjusted.
- DeepSeek's Anthropic-compatible endpoint does not document a
  JSON-Schema-native structured-output mechanism, so this example omits
  `:capabilities :structured-output` (defaults to unsupported); add
  `:strategies [:prompted-json]` if you want prompted-JSON fallback
- image, document, and search-result content blocks are not supported by
  DeepSeek's Anthropic-compatible endpoint
- psi's fast speed mode (`/fast` on) is unverified on `deepseek-v4-flash`:
  psi sends `"speed": "fast"` in the request body (plus the
  `fast-mode-2026-02-01` beta header), but DeepSeek's compat table does not
  list `speed`, and Anthropic-compatible endpoints typically reject unknown
  body fields (400). Not verified against a live turn — blocked on the same
  missing `DEEPSEEK_API_KEY` as the optional live smoke test; assume fast
  mode is unsupported on DeepSeek until verified.
  And a `speed`-field 400 is not auto-recoverable: psi's compatibility
  retry for HTTP 400 strips the `fast-mode-2026-02-01` beta header
  (`:without-all-betas` step) but leaves `"speed": "fast"` in the retried
  body, so a 400 caused by the unverified `speed` field retries once with
  the same field and hard-fails. Turn fast mode off (`/fast off`) to avoid
  it; do not rely on the auto-retry to degrade gracefully.

Custom providers do not define their own proxy fields. When a custom provider
uses psi's built-in OpenAI-compatible or Anthropic-compatible transport path, it
inherits the same environment-driven outbound proxy behavior documented in
[`doc/configuration.md`](configuration.md).

## Local servers and custom headers

The `:auth` map supports more than just an API key:

```clojure
{:auth {:api-key "env:LOCAL_LLM_KEY"
        :auth-header? false
        :headers {"X-Client" "psi"}}}
```

Use cases:
- `:api-key` — literal key or `"env:VAR_NAME"`
- `:auth-header? false` — omit the normal auth header for servers that reject it
- `:headers` — add custom request headers

A common use for `:auth-header? false` is an OpenAI-compatible local server that
accepts requests without a bearer token and rejects unexpected auth headers.
The same keyless pattern works for `:anthropic-messages` custom providers:
with `:auth-header? false`, psi does not require an API key and sends no
`x-api-key`/`Authorization` header — the configured `:headers` (if any) are
merged in as-is. Without `:auth-header? false`, a custom `:headers` map still
exempts the key requirement when it carries a *recognized* auth header —
`x-api-key` or `Authorization`, matched case-insensitively — with no
`:api-key` configured: psi treats that header as the auth and sends no auth
header of its own. Incidental headers (e.g. `X-Client` in the example above)
do NOT imply keyless: with no `:api-key` configured, the request fails fast
with a provider-scoped "Missing API key" error rather than silently sending a
keyless request.

For local `:openai-completions` models, psi also projects the normal session
`/thinking` control onto a local-only compatibility extension when thinking is
set to `off`: the request body includes
`{:chat_template_kwargs {:enable_thinking false}}`. This helps local
OpenAI-compatible servers that expose hidden reasoning via a nonstandard
`chat_template_kwargs.enable_thinking` flag.

Models may also declare `:parallel-tool-calls false`. When tools are available,
psi sends this as OpenAI-compatible `parallel_tool_calls: false` for that model.
This is useful for local OpenAI-compatible servers whose streaming tool-call
support is more reliable when the model emits at most one tool call per turn.

## Reload after editing

If psi is already running, reload the definitions after editing either models
file:

```text
/reload-models
```

That reloads:
- `~/.psi/agent/models.edn`
- `<worktree>/.psi/models.edn`

## Switch to the configured model

After reloading, use the normal model-selection surface.

In-session:

```text
/model minimax MiniMax-M1
```

or, for the Anthropic-compatible example:

```text
/model my-anthropic-proxy proxy-sonnet
```

Once selected, the custom model behaves like any other model in psi.

## Multiple providers

You can define multiple providers in the same file, for example:

- `minimax`
- `ollama`
- `staging-openai`
- `company-anthropic-proxy`

This already satisfies the issue's requested workflow of configuring multiple
providers in a config file and switching between them at runtime.

## Troubleshooting

- If psi does not see a newly added provider, run `/reload-models`.
- If a models file is malformed, psi logs a warning and keeps built-in models available.
- If a custom provider uses the same `(provider, model-id)` as a built-in model,
  the custom definition is skipped to avoid shadowing built-ins.
- If a project-local and user-global definition use the same `(provider, model-id)`,
  the project-local definition wins.

## Related docs

- [`doc/configuration.md`](configuration.md)
- [`spec/custom-providers.allium`](../spec/custom-providers.allium)
