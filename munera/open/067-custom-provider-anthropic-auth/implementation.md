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

- `psi.agent-session.runtime/resolve-api-key-in` originally checked only OAuth state:
  - it did not consult `model-registry/get-auth`
  - it did not see inline custom provider auth from models config
- Multiple runtime/RPC call sites pre-seed `:runtime-opts` from that narrower helper:
  - `psi.rpc.session.prompt`
  - `psi.rpc.session.commands`
  - `psi.rpc.session`
  - `psi.agent-session.runtime`
- This created auth-resolution drift:
  - canonical prompt preparation was provider-aware
  - runtime-side helper paths were narrower and built-in/OAuth-shaped

## Implemented changes

### Provider-aware runtime helper auth resolution

- Updated `psi.agent-session.runtime/resolve-api-key-in` to resolve provider auth in this order:
  1. OAuth/runtime credential for the selected provider
  2. model-registry auth for the selected provider when auth headers are enabled
- This preserves built-in OAuth behavior while allowing runtime-facing helper paths to see inline custom provider auth from models config.

### Shared provider-auth resolver follow-on

- Added `psi.agent-session.provider-auth` as a small shared helper for provider-scoped auth lookup.
- Moved shared logic for:
  - provider auth lookup
  - provider API key resolution
  - provider-scoped request option shaping
- Updated both `prompt-request` and `runtime` to depend on the shared helper so provider-auth precedence no longer has to be maintained in two separate implementations.

### Anthropic provider capture identity

- Updated Anthropic provider request/response capture payloads to report the selected model provider identity rather than always reporting `:anthropic`.
- This keeps introspection and request-boundary diagnostics aligned with the selected provider while still using the Anthropic-compatible transport.

## Added regression coverage

### Prompt-request tests

- Added coverage proving a custom `:anthropic-messages` provider injects provider-scoped inline auth into request options.
- Preserved built-in Anthropic no-registry-auth behavior coverage.

### Runtime helper tests

- Added coverage proving `runtime/resolve-api-key-in`:
  - falls back to model-registry provider auth for a custom `:anthropic-messages` provider when OAuth is absent
  - still prefers OAuth when OAuth credentials exist for the selected provider
  - still returns nil for built-in Anthropic when neither OAuth nor registry auth is configured

### Anthropic transport tests

- Added request-boundary coverage proving a custom anthropic-compatible model:
  - uses the configured custom `:base-url`
  - preserves selected provider identity in request capture payloads
  - still reports `:api :anthropic-messages`
  - still fails with the existing missing-auth error when no auth is configured

## Verification run

Focused verification passed:

- `psi.agent-session.prompt-request-test`
- `psi.agent-session.runtime-test`
- `psi.ai.providers.anthropic-test`

Result:
- `28 tests, 175 assertions, 0 failures`

## Working diagnosis

The root cause was split auth-resolution ownership. The selected provider and model were already being resolved correctly, and transport selection by `:api` was already correct. The failure came from runtime-facing helper paths that reconstructed auth through OAuth-only lookup and therefore dropped inline custom-provider auth before execution reached the Anthropic-compatible transport.

## Follow-on note

The earlier optional architecture follow-on has now been executed: provider-auth precedence is centralized in `psi.agent-session.provider-auth`, reducing future drift risk between prompt preparation and runtime helper paths.
