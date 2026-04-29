# Preserve selected provider identity in OpenAI-compatible provider captures

## Goal

Make request/response captures for custom model providers using OpenAI-compatible transports preserve the selected provider identity instead of always reporting built-in `:openai`.

## Context

The Anthropic-compatible provider path was fixed so captures now reflect the selected provider while still reporting the transport API separately. OpenAI-compatible transports still appear to hard-code:

- `:provider :openai`
- `:api :openai-completions` or `:api :openai-codex-responses`

This means a custom provider such as:

```clojure
{:version 1
 :providers
 {"local"
  {:base-url "http://localhost:8080/v1"
   :api      :openai-completions
   :auth     {:api-key "local-key"}
   :models   [{:id "test-model"}]}}}
```

may execute correctly with custom auth and base URL, but provider request/response captures can still report `:provider :openai` instead of `:provider :local`.

## Why

Provider captures are part of the observable public runtime surface. They should preserve the same structural invariant used elsewhere:

- `:api` identifies the transport / protocol
- `:provider` identifies the selected provider configuration

Without that distinction, introspection and debugging collapse custom providers back into built-in OpenAI and hide the actual provider identity that the session selected.

## Scope

Investigate OpenAI transport capture paths and update them so request/response captures preserve the selected provider identity for:

- `:openai-completions`
- `:openai-codex-responses`

while keeping the `:api` field transport-specific.

## Constraints

- Preserve current request execution behavior.
- Preserve current auth resolution behavior.
- Do not rework OpenAI transport semantics beyond capture identity unless a directly adjacent issue is required for correctness.
- Add focused regression tests covering custom OpenAI-compatible providers.

## Acceptance

- A custom provider using `:api :openai-completions` preserves its selected provider identity in provider request/response captures.
- A custom provider using `:api :openai-codex-responses` preserves its selected provider identity in provider request/response captures.
- Built-in OpenAI provider behavior remains unchanged.
- The capture payload still reports the correct OpenAI transport API in `:api`.
