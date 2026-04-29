# Preserve selected provider identity in OpenAI-compatible provider captures

## Goal

Make request/response captures for custom model providers using OpenAI-compatible transports preserve the selected provider identity instead of always reporting built-in `:openai`.

## Context

This task is the OpenAI-compatible follow-up to `067-custom-provider-anthropic-auth`.

Task 067 refined and proved a structural invariant that must also hold here:

- `:api` selects the transport / wire-protocol adapter
- `:provider` identifies the selected provider configuration

The Anthropic-compatible provider path was updated so captures now reflect the selected provider while still reporting the transport API separately. OpenAI-compatible transports still appear to hard-code:

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

Provider captures are part of the observable public runtime surface. They should preserve the same structural invariant already established for Anthropic-compatible providers:

- `:api` identifies the transport / protocol
- `:provider` identifies the selected provider configuration

Without that distinction, introspection and debugging collapse custom providers back into built-in OpenAI and hide the actual provider identity that the session selected.

## Scope

Investigate the OpenAI provider transport-layer request/response capture payloads and update them so those capture payloads preserve the selected provider identity for:

- `:openai-completions`
- `:openai-codex-responses`

while keeping the `:api` field transport-specific.

Scope is limited to the currently supported OpenAI-compatible transports listed above.

This is a capture-identity convergence task, not an auth-resolution redesign task.

## Constraints

- Preserve current request execution behavior.
- Preserve current auth resolution behavior.
- Do not rework OpenAI transport semantics beyond capture identity unless a directly adjacent issue is required for correctness.
- Fix the seam at the OpenAI transport capture layer rather than by aliasing custom providers back to built-in OpenAI.
- Add focused regression tests covering custom OpenAI-compatible providers.

## Provider identity rule

- Capture `:provider` must come from the resolved selected provider identity used for the model/provider configuration, not from transport `:api`.
- When the resolved selected provider identity is present, capture payloads must preserve it.
- For the built-in OpenAI provider, that selected provider identity remains `:openai`.

## Acceptance

- For a custom provider using `:api :openai-completions`, both request and response capture payloads report the selected provider identity in `:provider` and retain `:api :openai-completions`.
- For a custom provider using `:api :openai-codex-responses`, both request and response capture payloads report the selected provider identity in `:provider` and retain `:api :openai-codex-responses`.
- For the built-in OpenAI provider, request and response capture payloads continue to report `:provider :openai`.
- The implementation updates the OpenAI provider transport-layer capture payloads consistently rather than fixing only one user-visible derivative surface.
- Existing request execution and auth behavior remain unchanged aside from carrying selected provider identity into capture payloads.
