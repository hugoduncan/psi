# Steps

## Slice 1 — Characterize current state

- [ ] Read `components/ai/src/psi/ai/model_registry.clj` and identify the current OpenAI OAuth runtime override path.
- [ ] Read `components/ai/src/psi/ai/models.clj` and confirm the built-in catalog entries for `gpt-5.5` and `gpt-5.6`.
- [ ] Read `components/ai/test/psi/ai/model_registry_test.clj` and identify tests that assume `gpt-5.5` and `gpt-5.6` share the OAuth/Codex override set.
- [ ] Run the focused model-registry test namespace to capture the pre-change baseline.

## Slice 2 — Probe support and evidence capture

- [ ] Decide whether existing `/tmp` smoke probes are enough for implementation, or whether a project-local diagnostic probe is warranted.
- [ ] If adding a diagnostic probe, implement it so it prints structured request target, status, and body instead of using failing `clojure.test` assertions.
- [ ] If testing an alias or transport candidate, run a structured probe against `https://chatgpt.com/backend-api/codex/responses` or the selected OAuth-compatible backend with the same ChatGPT-account OAuth credential class.
- [ ] Record the selected policy evidence in the task `implementation.md` before changing runtime policy.

## Slice 3 — Encode runtime policy

- [ ] Update `psi.ai.model-registry/openai-oauth-runtime-model` policy so OAuth-backed `gpt-5.6` no longer resolves to Codex backend id `gpt-5.6`.
- [ ] Preserve OAuth-backed `gpt-5.5` resolution onto the ChatGPT/Codex transport.
- [ ] If `gpt-5.6` uses an alias backend id, encode the alias explicitly rather than deriving it as an implicit fallback.
- [ ] If `gpt-5.6` uses a different OAuth-compatible transport, encode that transport policy explicitly and keep catalog/runtime metadata coherent.
- [ ] Ensure nil/unknown model handling remains safe when a runtime override asks for a catalog model.

## Slice 4 — Regression tests

- [ ] Add or update a test proving OAuth-backed OpenAI `gpt-5.6` does not resolve to ChatGPT/Codex backend id `gpt-5.6`.
- [ ] Add or update a test proving OAuth-backed OpenAI `gpt-5.6` resolves to the explicitly selected supported backend id or transport.
- [ ] Add or update a test proving OAuth-backed OpenAI `gpt-5.5` still resolves to ChatGPT/Codex transport with id `gpt-5.5`.
- [ ] Add or update a test proving non-OAuth/API-key OpenAI `gpt-5.6` keeps its catalog-defined runtime behaviour.
- [ ] Update any existing assertions or comments that incorrectly state literal `gpt-5.6` is Codex-supported.

## Slice 5 — Documentation and changelog

- [ ] Determine whether the selected policy changes user-visible model selection behaviour.
- [ ] If user-visible behaviour changes, add a `CHANGELOG.md` `[Unreleased]` entry.
- [ ] If docs mention OpenAI OAuth model support, update the relevant `README.md` or `doc/` page to match the new policy.
- [ ] Re-read changed docs/changelog for consistency with the implementation and tests.

## Slice 6 — Validation

- [ ] Run focused tests for `psi.ai.model-registry-test`.
- [ ] Run the relevant AI component test suite.
- [ ] Run lint on changed Clojure files.
- [ ] Run broader `bb test` if the focused suite indicates cross-component risk.
- [ ] Review the final diff for catalog/runtime/test/doc coherence.
