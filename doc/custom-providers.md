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
