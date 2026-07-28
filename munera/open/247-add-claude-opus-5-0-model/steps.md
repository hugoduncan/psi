# Steps — 247 Add Claude Opus 5.0 model

## Slice 1 — Catalog + registry data

- [ ] Add `:opus-5.0` entry to
      `components/ai/src/psi/ai/models/anthropic_catalog.clj` immediately
      after `:opus-4.8`, with attributes from design.md
      (`:id "claude-opus-5-0"`, `:name "Claude Opus 5.0"`,
      `:adaptive-thinking true`,
      `:supports-mid-conversation-system-messages true`, placeholder
      pricing/limits mirroring Opus 4.8)
- [ ] Add `:opus-5.0` to `anthropic-json-schema-native-model-keys` in
      `components/ai/src/psi/ai/models.clj`
- [ ] Reload/lint changed namespaces (`clj-kondo --lint` on both files)

## Slice 2 — Tests

- [ ] Extend `components/ai/test/psi/ai/model_registry_test.clj` with a
      focused test asserting:
      `(find-model :anthropic "claude-opus-5-0")` is non-nil, includes
      `:adaptive-thinking true` and
      `:supports-mid-conversation-system-messages true`, the model appears in
      `(models-for-provider :anthropic)`, and
      `anthropic-json-schema-native-model-keys` contains `:opus-5.0`
- [ ] Add an Opus 5.0 case to the gated `^:integration` test in
      `components/ai/test/psi/ai/providers/anthropic_models_api_test.clj`
      (calls `GET /v1/models/claude-opus-5-0`, asserts `id`, skips without
      `PSI_LIVE_ANTHROPIC_MODELS_API=1` + `ANTHROPIC_API_KEY`)
- [ ] Run focused tests:
      `bb test --focus psi.ai.model-registry-test` and
      `bb test --focus psi.ai.providers.anthropic-models-api-test`
      (expect graceful skip for gated test)

## Slice 3 — Docs + verification

- [ ] Add CHANGELOG `[Unreleased] / Added` entry for Claude Opus 5.0
- [ ] Run full `bb test` — all green
- [ ] Manually verify `/model anthropic claude-opus-5-0` selects the model in
      a live session (or note if deferred)
- [ ] Note in implementation.md that pricing/limits are placeholders pending
      official Anthropic publication
- [ ] Commit with `⚒` symbol
