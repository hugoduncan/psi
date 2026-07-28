# Plan — 247 Add Claude Opus 5.0 model

## Approach

Pure catalog-data change following the established Opus 4.8 pattern (task 192):

1. Add an `:opus-5.0` entry to `anthropic_catalog.clj` immediately after
   `:opus-4.8` (line ~168), using the attribute table in design.md verbatim
   (placeholder pricing/limits mirror Opus 4.8).
2. Add `:opus-5.0` to `anthropic-json-schema-native-model-keys` in
   `models.clj` (line ~483).
3. Tests: extend the focused unit tests in
   `components/ai/test/psi/ai/model_registry_test.clj` (registry resolution +
   capability flags) and add an Opus 5.0 case to the gated live models-API test
   in `components/ai/test/psi/ai/providers/anthropic_models_api_test.clj`
   (gate: `PSI_LIVE_ANTHROPIC_MODELS_API=1` + `ANTHROPIC_API_KEY`, graceful
   skip otherwise).
4. CHANGELOG `[Unreleased] / Added` entry.

No provider-layer changes: adaptive-thinking, mid-conversation system
messages, and structured-output shaping all dispatch on model metadata.

## Key decisions

- Model id `claude-opus-5-0` per established `claude-opus-N-M` naming.
- Placeholder pricing/context/max-tokens mirror Opus 4.8 until Anthropic
  publishes official values (sanctioned by design.md).
- Mirror the task-192 test structure rather than inventing new test files.

## Risks

- Model id or pricing may differ once officially published — placeholders must
  be revisited before release (tracked as design open question; non-blocking).
- Live integration test cannot be verified without the env gate + API key;
  unit tests and graceful-skip behaviour are the verifiable surface.

## Slice order

1. **Catalog + registry data**: catalog entry + json-schema-native key.
2. **Tests**: unit tests (registry + capabilities), gated live API test.
3. **Docs + verification**: CHANGELOG entry, full `bb test`, commit.
