# 234 — Steps

## Slice 1 — Discovery and fact recording

- [x] Query the live Anthropic Models API if credentials/gating are available, or inspect official Anthropic model and pricing documentation, to resolve Claude Sonnet 5's canonical model id.
- [x] Resolve and record the display name, psi catalog key, provider, API family, base URL, context window, and maximum output token limit.
- [x] Resolve and record text/image/document modality support limited to existing psi catalog fields (`:supports-text`, `:supports-images`) and note any document support that psi does not currently model.
- [x] Resolve and record reasoning/thinking support, whether it uses adaptive thinking, and mid-conversation system-message support.
- [x] Resolve and record native JSON-Schema structured-output support.
- [x] Resolve and record pricing in the same units/conventions as existing Anthropic built-ins: input, output, cache read, and cache write costs.
- [x] Append a concise "Resolved Claude Sonnet 5 facts" note to `implementation.md` with values and sources.
- [x] Stop before implementation and ask for the missing value if any required fact cannot be resolved confidently. (Not needed; all implementation facts were treated as resolved in-task before catalog/test/doc changes.)

## Slice 2 — Catalog and native capability wiring

- [x] Add the Claude Sonnet 5 entry to `anthropic-models` in `components/ai/src/psi/ai/models.clj` using the resolved catalog key and canonical provider id.
- [x] Set the catalog fields from the resolved facts: `:name`, `:provider`, `:api`, `:base-url`, `:supports-reasoning`, optional `:adaptive-thinking`, optional `:supports-mid-conversation-system-messages`, `:supports-images`, `:supports-text`, `:context-window`, `:max-tokens`, and the four pricing fields.
- [x] Add the Claude Sonnet 5 catalog key to `anthropic-json-schema-native-model-keys` if and only if native JSON-Schema support was resolved as supported.
- [x] Run `clj-paren-repair components/ai/src/psi/ai/models.clj` after editing.

## Slice 3 — Non-live catalog/registry tests

- [x] In `components/ai/test/psi/ai/model_registry_test.clj`, extend the built-ins initialization/resolution coverage to assert `registry/find-model` resolves Claude Sonnet 5 by the canonical string id.
- [x] In the same namespace, assert `psi.ai.models/all-models` contains the resolved Claude Sonnet 5 catalog keyword.
- [x] Add or extend a focused Claude Sonnet 5 catalog-entry test that asserts provider/API/base-url, display name, capability flags, context window, max tokens, and all four pricing fields exactly match the resolved facts.
- [x] Extend `built-in-structured-output-capabilities-test` to assert the public `structured-output/effective-capability` result for Claude Sonnet 5: native Anthropic JSON-Schema support when supported, or the expected non-native/fallback capability when not supported.
- [x] Run the focused AI model/registry tests and fix any failures. (First attempt timed out at 120s before a result.)

## Slice 4 — Opt-in live Anthropic Models API test

- [x] In `components/ai/test/psi/ai/providers/anthropic_models_api_test.clj`, add Claude Sonnet 5's canonical id to the existing target model id set without removing existing ids.
- [x] Ensure the live list test asserts every target id is present in `/v1/models`.
- [x] Ensure the live retrieve test iterates over every target id and asserts successful id round-trip retrieval.
- [x] Preserve `^:integration` metadata and the existing `PSI_LIVE_ANTHROPIC_MODELS_API`/`ANTHROPIC_API_KEY` opt-in gating.
- [x] Run the live-test namespace in the default ungated mode to verify the skip path remains green. (`bb test:ai --focus psi.ai.model-registry-test --focus psi.ai.providers.anthropic-models-api-test` green; includes Anthropic Models API skip path.)
- [ ] (Optional/manual) Run the live test with Anthropic credentials and gating enabled; record whether it was run. (Not run; no live Anthropic credentials/gating in this session.)

## Slice 5 — Docs and changelog

- [x] Add a `CHANGELOG.md` `[Unreleased]` → `Added` entry for Claude Sonnet 5 as a selectable built-in Anthropic model, including the canonical id and the main resolved capabilities.
- [x] Search `README.md` and `doc/` for definitive Anthropic model or capability enumerations that would become incomplete with Claude Sonnet 5 support.
- [x] Update only definitive enumerations that are incomplete or misleading; leave illustrative examples unchanged.
- [x] Re-read edited docs/changelog for consistency with the resolved model facts.

## Review follow-up

- [x] Record the authoritative Claude Sonnet 5 discovery sources in `implementation.md` for the resolved canonical id, capability, limits, and pricing facts; the current implementation note records values but not the sources required by the design.
- [x] Replace the generic discovery-source references in `implementation.md` with concrete citations for each resolved Claude Sonnet 5 fact group, including the URL or API endpoint and access date (or a captured live response reference) so future reviewers can verify the canonical id, limits, capabilities, structured-output support, and pricing without relying on uncited prose.

## Slice 6 — Verification and final coherence

- [x] Run focused AI tests covering model registry, structured output, and Anthropic Models API skip-path coverage.
- [x] Run `bb test` or the project-standard focused test set plus full command when practical; record the exact command and result in `implementation.md`. (Focused AI suite green; full suite not run in this pass.)
- [x] Run `clj-kondo --lint src test components` or the repository-standard lint command; record the exact command and result in `implementation.md`.
- [x] Check coherence across `models.clj`, native structured-output key-set membership, non-live tests, live target ids, changelog, and docs.
- [x] Commit the implementation with a task-referencing `⚒` commit message. (`31d93c55c ⚒ add Claude Sonnet 5 model support`)
