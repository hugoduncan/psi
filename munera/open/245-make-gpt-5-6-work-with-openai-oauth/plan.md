# Plan

## Approach

Implement the fix as an evidence-gated runtime policy change rather than a blind catalog edit.

1. Reconfirm the current model-resolution behaviour in tests: OpenAI OAuth-backed `gpt-5.6` currently resolves onto the ChatGPT/Codex transport with backend id `gpt-5.6`, while `gpt-5.5` resolves onto the accepted Codex path.
2. Add or update structured diagnostic probe support, if needed, so candidate OAuth runtime policy can be checked against the ChatGPT/Codex backend without assertion-style smoke failures. Probe output should preserve status and response body for decision-making.
3. Determine the supported OAuth runtime policy for user-visible `gpt-5.6`:
   - preferred: an explicitly supported ChatGPT/Codex backend id for `gpt-5.6`; or
   - alternative: a different OAuth-compatible transport with evidence from the same account/backend class.
4. Encode the selected policy in `psi.ai.model-registry` and related model metadata so the user-visible catalog key remains coherent with runtime transport/model-id resolution.
5. Add regression tests for OAuth-backed OpenAI runtime resolution:
   - `gpt-5.6` must not resolve to the known-rejected Codex backend id `gpt-5.6`.
   - `gpt-5.5` must remain on the working ChatGPT/Codex path.
   - if `gpt-5.6` aliases to another backend id, tests must prove the user-visible `gpt-5.6` entry resolves to that explicit backend id.
   - non-OAuth/API-key OpenAI resolution must keep catalog-defined behaviour.
6. Update user-facing documentation or `CHANGELOG.md` only if model selection behaviour changes in a user-visible way.
7. Run focused model-registry tests, then the relevant broader AI/component test set.

## Key decisions

- The platform Chat Completions `429 insufficient_quota` response is not policy evidence for OAuth-backed ChatGPT execution.
- A literal `gpt-5.6` Codex backend id is currently negative evidence and must not remain the OAuth runtime target.
- No silent fallback is permitted. Any alias or transport change must be explicit in runtime policy and covered by tests.
- `gpt-5.5` is the control case and must remain OAuth/Codex-capable.

## Risks

- The correct supported backend id or transport for `gpt-5.6` may not be discoverable from the existing codebase and may require a fresh structured probe against live OAuth credentials.
- OAuth credentials/account capabilities may vary by account, so probe evidence should be documented narrowly as ChatGPT-account OAuth policy evidence.
- Existing tests may assume both `gpt-5.5` and `gpt-5.6` are members of the same OAuth/Codex override set and will need careful updates.
- Changing runtime ids without clear catalog metadata could create confusing user-visible behaviour.

## Slice order

1. Characterize current catalog/runtime resolution and locate existing test coverage.
2. Add or refine structured OAuth/Codex diagnostic probe tooling only if permanent support is needed.
3. Establish the supported `gpt-5.6` OAuth runtime policy from backend evidence.
4. Update model-registry policy and any related metadata.
5. Add regression tests for OAuth, non-OAuth, alias/transport, and `gpt-5.5` preservation cases.
6. Update changelog/docs if behaviour changes are user-visible.
7. Validate with focused and relevant broader tests.
