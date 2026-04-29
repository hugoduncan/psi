# Investigate and fix custom Anthropic-compatible provider auth resolution

## Goal

Make custom model providers configured with `:api :anthropic-messages` use the selected provider's configured authentication, including inline `:auth {:api-key ...}`, instead of requiring Anthropic-specific credentials.

## Context

A user configured a custom provider like:

```clojure
{:version 1
 :providers
 {"minimax"
  {:base-url "https://api.minimax.io/anthropic"
   :api      :anthropic-messages
   :auth     {:api-key "sk-cp-my-minimax-key-that-works-in-pi"}
   :models   [{:id "MiniMax-M2.7"}
              {:id "MiniMax-M2.7-highspeed"}]}}}
```

and selected it with:

```clojure
{:version 1
 :agent-session {:model-provider "minimax"
                 :model-id       "MiniMax-M2.7"}}
```

Instead of using the configured provider auth, psi reports:

```text
Missing Anthropic API key. Set ANTHROPIC_API_KEY or login via /login anthropic.
```

This shows a drift between provider selection and transport selection: the request is correctly using the Anthropic-compatible transport, but auth resolution is behaving as though `:api :anthropic-messages` implied the built-in `:anthropic` provider.

## Why

Custom providers are part of psi's extensibility story. Anthropic-compatible endpoints should be usable through explicit config without forcing users to masquerade as Anthropic or copy credentials into unrelated environment variables.

More generally, psi needs a clear invariant here so custom providers do not regress whenever a built-in transport implementation is reused.

## Structural invariant

Provider identity and transport identity are distinct decisions:

- `:api` selects the wire-protocol / transport adapter.
- `:provider` selects provider-scoped configuration.

For provider-scoped configuration, the selected provider entry is authoritative for:
- authentication
- `:base-url`
- custom headers
- provider identity used by fallback auth lookup

A custom provider using `:api :anthropic-messages` is therefore Anthropic-compatible at the protocol layer, but it is not the built-in Anthropic provider unless the selected provider itself is `:anthropic`.

## Scope

Investigate the provider/model resolution and request-auth path for `:anthropic-messages`, identify where provider-scoped auth is dropped or bypassed, and change the implementation so the selected provider config drives authentication consistently.

The implementation should reduce auth-resolution drift between canonical prompt preparation and any runtime / RPC / mutation call paths that currently reconstruct auth independently.

## Constraints

- Preserve support for the built-in Anthropic provider.
- Preserve existing fallback behaviour when a selected provider truly has no configured auth.
- Fix the root cause in provider/auth resolution rather than documenting an environment-variable workaround.
- Add tests covering custom Anthropic-compatible providers with explicit inline auth.
- Do not reframe custom Anthropic-compatible providers as aliases for built-in Anthropic.

## Required behaviour

For a selected custom provider using `:api :anthropic-messages`:
- request execution must still use the Anthropic-compatible transport
- request URL construction must still use the selected provider's configured `:base-url`
- auth resolution must use the selected provider's configured auth before any built-in Anthropic-specific fallback
- psi must not require `ANTHROPIC_API_KEY` or `/login anthropic` when selected-provider auth is already present

For the built-in Anthropic provider:
- preserve current Anthropic auth behaviour unchanged

For a selected provider with no usable auth configured:
- preserve the current missing-auth failure behaviour

## Acceptance

- A selected custom provider using `:api :anthropic-messages` and inline `:auth {:api-key ...}` is accepted without requiring `ANTHROPIC_API_KEY` or `/login anthropic`.
- Requests for such providers still use the configured custom `:base-url`.
- The effective request path preserves the selected provider identity while dispatching to the Anthropic-compatible transport.
- Built-in Anthropic provider behaviour remains unchanged.
- A custom Anthropic-compatible provider with no configured auth still produces the existing missing-auth failure.
- Tests fail before the fix and pass after it.

## Suggested verification surface

- focused provider-auth resolution tests for custom `:anthropic-messages` providers
- request-building or execution tests proving custom `:base-url` and auth are carried through together
- regression coverage for built-in Anthropic fallback behaviour
