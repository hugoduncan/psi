# Steps

## Slice 1 — Characterize current state

- [x] Read `components/ai/src/psi/ai/model_registry.clj` and identify the current OpenAI OAuth runtime override path.
- [x] Read `components/ai/src/psi/ai/models.clj` and confirm the built-in catalog entries for `gpt-5.5` and `gpt-5.6`.
- [x] Read existing AI model-registry tests and identify assertions that assume `gpt-5.5` and `gpt-5.6` share the OAuth/Codex override set.
- [x] Run the focused model-registry test namespace to capture the pre-change baseline.
- [x] Record any surprising current behaviour in `implementation.md` before changing code.

## Slice 2 — Probe support and evidence capture

- [x] Decide whether the existing `/tmp` diagnostic probes are sufficient evidence for implementation, or whether a project-local diagnostic helper is warranted.
- [x] If adding a diagnostic helper, implement it so it prints structured request target, status, and response body instead of using failing `clojure.test` assertions.
- [x] If considering a ChatGPT/Codex alias for `gpt-5.6`, probe the candidate backend id against `https://chatgpt.com/backend-api/codex/responses` using the same ChatGPT-account OAuth credential class.
- [x] If considering a different OAuth-compatible transport for `gpt-5.6`, probe that transport with structured status/body output.
- [x] Record the selected policy evidence, or the absence of sufficient evidence, in `implementation.md` before encoding runtime policy.

## Slice 3 — Encode runtime policy

- [x] Update `psi.ai.model-registry/openai-oauth-runtime-model` policy so OAuth-backed `gpt-5.6` no longer resolves to ChatGPT/Codex backend id `gpt-5.6`.
- [x] Preserve OAuth-backed `gpt-5.5` resolution onto the ChatGPT/Codex transport with backend id `gpt-5.5`.
- [x] If `gpt-5.6` uses an alias backend id, encode the alias explicitly rather than deriving it as an implicit fallback.
- [x] If `gpt-5.6` uses a different OAuth-compatible transport, encode that transport policy explicitly and keep catalog/runtime metadata coherent.
- [x] If no supported OAuth policy for `gpt-5.6` is evidenced, encode the model as unavailable/unsupported for that OAuth path rather than silently falling back.
- [x] Ensure nil and unknown model handling remains safe when runtime override resolution consults the catalog.

## Slice 4 — Regression tests

- [x] Add or update a test proving OAuth-backed OpenAI `gpt-5.6` does not resolve to ChatGPT/Codex backend id `gpt-5.6`.
- [x] Add or update a test proving OAuth-backed OpenAI `gpt-5.6` resolves to the explicitly selected supported backend id or transport, or is explicitly rejected as unsupported if no supported policy is evidenced.
- [x] Add or update a test proving OAuth-backed OpenAI `gpt-5.5` still resolves to ChatGPT/Codex transport with id `gpt-5.5`.
- [x] Add or update a test proving non-OAuth/API-key OpenAI `gpt-5.6` keeps its catalog-defined runtime behaviour.
- [x] Add or update a test for nil/unknown model handling if touched by the runtime override change.
- [x] Update any existing assertions or comments that incorrectly state literal `gpt-5.6` is Codex-supported.

## Slice 5 — Documentation and changelog

- [x] Determine whether the selected policy changes user-visible model selection behaviour.
- [x] If user-visible behaviour changes, add a `CHANGELOG.md` `[Unreleased]` entry before committing the behaviour change.
- [x] If docs mention OpenAI OAuth model support, update the relevant `README.md` or `doc/` page to match the new policy.
- [x] Re-read changed docs or changelog entries for consistency with the implementation and tests.

## Slice 6 — Validation

- [x] Run focused tests for the model-registry namespace.
- [x] Run the relevant AI component test suite.
- [x] Run lint on changed Clojure files.
- [x] Run broader `bb test` if focused validation indicates cross-component risk.
- [x] Review the final diff for catalog/runtime/test/doc coherence.
- [x] Update `implementation.md` with final decisions, validation results, and any remaining follow-up.
