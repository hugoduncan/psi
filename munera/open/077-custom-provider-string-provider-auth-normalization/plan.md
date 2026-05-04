# Plan

## Approach

Fix the regression at the shared provider-auth boundary by normalizing provider identity before auth and provider-option lookup.

The implementation should preserve the existing architecture:

- `:api` chooses the transport / protocol implementation
- `:provider` chooses provider-scoped configuration

The issue is not transport selection; it is provider identity shape drift between:

- live session-state model maps, which store provider as a string
- registry/auth lookups, which are keyed by provider keyword

The fix should therefore make shared provider-auth resolution shape-stable so all prompt-preparation and runtime helper callers benefit automatically.

## Planned slices

1. Reproduce the regression with real session-shaped provider identity
   - add prompt-request coverage using session data with `{:provider "minimax" ...}`
   - add runtime helper coverage using ai-model maps with `{:provider "minimax" ...}`
   - preserve the existing keyword-shaped coverage as a guard against regressions in the opposite direction

2. Normalize provider identity at the shared boundary
   - inspect `provider_auth.clj` for auth and request-option lookup seams
   - add one canonical provider normalization helper there
   - use it for every shared provider-auth lookup path that uses provider identity as a registry key
   - this includes model-registry auth lookup and provider-request option shaping when they share the same seam
   - apply the same normalization to OAuth lookup if that path consumes the same provider identity surface

3. Verify canonical prompt-preparation behavior
   - ensure `prompt-request/session->request-options` resolves custom auth for both string and keyword provider identities
   - ensure explicit runtime override precedence remains intact
   - ensure built-in provider missing-auth behavior remains unchanged

4. Verify the reported custom Anthropic-compatible regression is covered
   - prove with at least one new regression test that a selected custom `:anthropic-messages` provider with configured auth does not surface the built-in Anthropic missing-key failure solely because the provider is string-shaped

## Key decisions

- Fix provider shape drift once in shared provider-auth code rather than in prompt-request and runtime separately.
- Treat string and keyword provider identities as equivalent representations of the same selected provider.
- Do not patch the Anthropic transport or reintroduce provider-specific special cases.
- Prefer tests that match live session-state shape over unit-only synthetic keyword paths.

## Risks

- Provider identity may cross both model-registry and OAuth lookups; a partial normalization could leave one path inconsistent.
- Over-normalizing in the wrong place could affect call sites that intentionally distinguish nil/blank/invalid provider values.
- Existing tests currently exercise the keyword path and may give false confidence unless string-shaped regression coverage is added first.

## Verification

- focused `psi.agent-session.prompt-request-test`
- focused `psi.agent-session.runtime-test`
- focused coverage for provider-request option lookup if it shares the same seam
- at least one new string-provider regression proof showing the MiniMax custom `:anthropic-messages` path no longer drops configured auth or surfaces the built-in Anthropic missing-key error
