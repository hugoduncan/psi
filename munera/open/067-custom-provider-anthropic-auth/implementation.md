# Implementation notes

- Created to investigate custom provider auth resolution for `:anthropic-messages`.

## Investigation summary

- Refined the task design around a structural invariant:
  - `:api` selects the transport / wire-protocol adapter
  - `:provider` selects provider-scoped configuration such as auth, `:base-url`, headers, and provider identity
- Confirmed the canonical prepared-request path already mostly follows that invariant.

## Code-path findings

### Canonical prepared-request path

- `psi.agent-session.prompt-request/build-prepared-request` resolves the runtime model by selected provider + model id.
- `psi.agent-session.prompt-request/session->request-options` resolves auth using the selected provider identity from session data.
- `psi.agent-session.prompt-request/resolve-api-key` currently checks, in order:
  1. explicit `runtime-opts :api-key`
  2. session `:runtime-api-key`
  3. OAuth for the selected provider
  4. `model-registry/get-auth` for the selected provider
- `psi.agent-session.prompt-request/resolve-custom-provider-options` also uses `model-registry/get-auth` keyed by the selected provider.

This means the canonical request-preparation layer already knows how to inject inline custom-provider auth, including custom providers that use `:api :anthropic-messages`.

### Transport dispatch

- `psi.ai.core/resolve-provider` intentionally separates provider identity from transport identity.
- Resolution order is:
  1. exact match on `(:provider model)`
  2. fallback match on `(:api model)`
- This is the intended mechanism that allows a custom provider such as `:minimax` to reuse the Anthropic-compatible transport implementation while still keeping its own provider identity.

### Anthropic transport failure mode

- `psi.ai.providers.anthropic/resolve-api-key` throws only when no `:api-key` is present in the final options and `ANTHROPIC_API_KEY` is also absent.
- Therefore the observed user-facing error implies a request path reached the Anthropic transport without the selected provider's configured auth present in options.

### Drift seam identified

- `psi.agent-session.runtime/resolve-api-key-in` currently checks only OAuth state:
  - it does not consult `model-registry/get-auth`
  - it does not see inline custom provider auth from models config
- Multiple runtime/RPC call sites pre-seed `:runtime-opts` from that narrower helper:
  - `psi.rpc.session.prompt`
  - `psi.rpc.session.commands`
  - `psi.rpc.session`
  - `psi.agent-session.runtime`
- This creates auth-resolution drift:
  - canonical prompt preparation is provider-aware
  - runtime-side helper paths are narrower and built-in/OAuth-shaped

## Working diagnosis

The likely root cause is not Anthropic request building itself, nor provider dispatch by `:api`. The more likely cause is split auth-resolution ownership:

- the selected provider and model are resolved correctly
- transport selection correctly falls back to the Anthropic-compatible adapter
- one or more runtime-side seams reconstruct auth through OAuth-only lookup or otherwise fail to carry canonical prepared-request auth through execution
- the Anthropic transport then throws its built-in missing-key error because final execution options lack `:api-key`

## Intended implementation direction

- Prefer one canonical provider-aware auth resolver shared across prompt preparation and runtime-facing helper paths.
- Preserve explicit runtime overrides such as `:runtime-opts {:api-key ...}`.
- Preserve built-in Anthropic OAuth/env fallback behavior.
- Fix provider/auth resolution drift rather than adding a special-case patch in the Anthropic provider implementation.

## Suggested verification additions

- custom `:anthropic-messages` provider auth injection tests
- request-boundary regression proving custom auth and custom `:base-url` survive together
- negative regression for a custom anthropic-compatible provider with no auth
- unchanged built-in Anthropic behavior coverage
