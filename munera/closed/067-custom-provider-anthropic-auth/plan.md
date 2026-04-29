# Plan

## Approach

Tighten auth-resolution ownership so provider-scoped configuration is derived from the selected provider identity, not inferred from the transport adapter.

The implementation should keep the existing architectural split:
- `:api` selects the transport implementation
- `:provider` selects auth, `:base-url`, headers, and provider identity

The main change is to remove drift between canonical prompt preparation and runtime/RPC helper paths that currently pre-resolve API keys through narrower OAuth-only logic.

## Planned slices

1. Reproduce the failing seam with focused tests
   - add coverage for a custom provider using `:api :anthropic-messages`
   - prove selected-provider auth is present in prepared request options
   - prove built-in Anthropic behavior remains unchanged

2. Unify provider-aware auth resolution
   - extract or refactor toward one canonical auth-resolution path shared by prompt preparation and runtime-facing helpers
   - ensure model-registry provider auth participates wherever runtime helpers currently only consult OAuth

3. Remove or narrow duplicate runtime auth reconstruction
   - update runtime/RPC call sites so they do not silently drop selected-provider auth
   - preserve explicit per-call overrides such as `:runtime-opts {:api-key ...}`

4. Prove end-to-end behavior at the request boundary
   - add regression coverage showing custom anthropic-compatible providers carry custom auth and custom base-url into execution
   - add negative coverage showing truly missing auth still fails with the current missing-auth behavior

## Key decisions

- Do not special-case custom anthropic-compatible providers as aliases of built-in `:anthropic`.
- Prefer a single canonical provider-auth resolver over parallel ad hoc resolution logic.
- Preserve existing precedence where explicit runtime overrides win.
- Keep the fix rooted in provider/auth resolution; do not patch around it in the Anthropic transport.

## Risks

- Multiple prompt entry paths exist across app-runtime, RPC, runtime helpers, and mutations; updating only one may leave drift elsewhere.
- Some call sites may rely on current `resolve-api-key-in` behavior implicitly, so test coverage should include both prepared-request shaping and prompt execution seams.
- Built-in Anthropic OAuth/env fallback must remain intact.

## Verification

- focused `prompt_request` tests for custom `:anthropic-messages` providers
- focused runtime/RPC or execution-path regression proving provider-scoped auth is not lost before transport execution
- existing Anthropic provider tests still green
- relevant unit suites green
