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

When enabled on the active runtime model, well-formed blocks are removed from assistant prose, converted into ordinary canonical tool calls, and then pass through the existing tool availability, authorization, execution, journaling, and result-recording path. Malformed, partial, unsupported, or oversized markup remains ordinary assistant text and does not execute a tool. Parameter text may contain ordinary shell text and newlines, but literal textual-tool-call tags such as `<tool_call>`, `<function=...>`, or `<parameter=...>` are unsupported inside parameter values and make that candidate malformed. Nested textual tool-call recovery is intentionally not supported. A single textual tool-call candidate block is supported up to 65,536 characters; longer blocks are intentionally left as text to keep malformed many-marker output bounded. Frontier/provider-native models should not enable this compatibility flag; leave the capability omitted unless the configured local runner is known to leak this textual markup.

Native capability declarations should only be used when the configured transport is known to support the provider mechanism:

- `:openai-completions` may use `:native-mechanism :openai/chat-completions-json-schema-response-format` when the compatible API supports Chat Completions `response_format` JSON Schema.
- `:anthropic-messages` may use `:native-mechanism :anthropic/json-schema-output` when the compatible API supports Anthropic Messages `output_format` JSON Schema plus the `structured-outputs-2025-11-13` beta/header. This is the preferred Anthropic native mechanism for supported models.
- `:anthropic-messages` may use `:native-mechanism :anthropic/forced-tool-use` when the compatible API supports forced tool choice with `input_schema`. This is a separate native tool-use mechanism, not the only Anthropic structured-output path.
- `:openai-codex-responses` may use `:native-mechanism :openai/responses-text-format-json-schema` for the ChatGPT/Codex OAuth transport when the backend supports streaming Responses-style `text.format` JSON Schema. This mechanism is distinct from Chat Completions `response_format`; non-streaming Codex structured output is not established by Psi's current contract.

Structured-output requests must supply an explicit `:json-schema`; Psi does not convert Malli/domain schemas in the AI adapter. Prompted JSON remains fallback only. Local workflow/runtime validation remains mandatory after provider generation, and OAuth/API tokens must not be written into docs, task files, fixtures, logs, or commits.

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
