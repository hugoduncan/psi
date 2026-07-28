# Plan

## Approach

Implement the fix as an evidence-gated runtime policy change rather than a blind catalog edit. The design is complete enough to plan: the only acceptable implementation is one that stops OpenAI OAuth-backed `gpt-5.6` from using the known-rejected ChatGPT/Codex backend id `gpt-5.6`, while preserving the working `gpt-5.5` OAuth/Codex path.

1. Characterize the current catalog and runtime override behaviour in code and tests, especially `psi.ai.model-registry` and the built-in model catalog entries for `gpt-5.5` / `gpt-5.6`.
2. Establish or preserve structured diagnostic evidence for any candidate `gpt-5.6` OAuth runtime policy. Probes must report request target, status, and response body; they must not be assertion-based smoke tests.
3. Choose the explicit OAuth runtime policy for user-visible `gpt-5.6`:
   - supported ChatGPT/Codex backend id; or
   - different supported OAuth-compatible transport.
   If no supported policy is evidenced, do not silently alias or fallback.
4. Encode the selected policy in the model registry and related metadata so catalog entries and runtime transport/model-id resolution stay coherent.
5. Add regression tests that cover OAuth-backed `gpt-5.6`, OAuth-backed `gpt-5.5`, any explicit alias/transport decision, and non-OAuth/API-key OpenAI resolution.
6. Update user-facing documentation or `CHANGELOG.md` only if model selection behaviour changes in a user-visible way.
7. Validate with focused model-registry tests, relevant AI component tests, lint, and a final coherence review.

## Key decisions

- The platform Chat Completions `429 insufficient_quota` response is not policy evidence for OAuth-backed ChatGPT execution.
- A literal `gpt-5.6` Codex backend id is negative evidence and must not remain the OAuth runtime target.
- No silent fallback is permitted. Any alias or transport change must be explicit runtime/catalog policy and covered by tests.
- `gpt-5.5` is the control case and must remain OAuth/Codex-capable.
- Permanent regression coverage belongs in project tests; `/tmp` probes are diagnostic artifacts only unless deliberately promoted.

## Risks

- The correct supported backend id or transport for `gpt-5.6` may not be discoverable from existing code and may require a fresh structured probe against live OAuth credentials.
- OAuth credentials/account capabilities may vary by account, so evidence must be documented narrowly as ChatGPT-account OAuth policy evidence.
- Existing tests may assume both `gpt-5.5` and `gpt-5.6` are members of the same OAuth/Codex override set and will need careful updates.
- Changing runtime ids without clear catalog metadata could create confusing user-visible behaviour.
- If no supported OAuth policy for user-visible `gpt-5.6` can be evidenced, implementation may need to make the model unavailable for that credential/transport path rather than pretending it works.

## Slice order

1. Characterize current catalog/runtime resolution and locate existing test coverage.
2. Decide whether permanent structured probe support is needed, and gather/record any required backend evidence.
3. Establish the supported `gpt-5.6` OAuth runtime policy or explicitly conclude that no fallback is allowed without evidence.
4. Update model-registry policy and related catalog/runtime metadata.
5. Add regression tests for OAuth, non-OAuth, alias/transport, and `gpt-5.5` preservation cases.
6. Update changelog/docs if behaviour changes are user-visible.
7. Validate with focused and relevant broader tests, lint, and final diff review.
