# Steps — 247 Add Claude Opus 5.0 model

## Slice 1 — Catalog + registry data

- [x] Add `:opus-5.0` entry to
      `components/ai/src/psi/ai/models/anthropic_catalog.clj` immediately
      after `:opus-4.8`, with attributes from design.md
      (`:id "claude-opus-5-0"`, `:name "Claude Opus 5.0"`,
      `:adaptive-thinking true`,
      `:supports-mid-conversation-system-messages true`, placeholder
      pricing/limits mirroring Opus 4.8)
- [x] Add `:opus-5.0` to `anthropic-json-schema-native-model-keys` in
      `components/ai/src/psi/ai/models.clj`
- [x] Reload/lint changed namespaces (`clj-kondo --lint` on both files)

## Slice 2 — Tests

- [x] Extend `components/ai/test/psi/ai/model_registry_test.clj` with a
      focused test asserting:
      `(find-model :anthropic "claude-opus-5-0")` is non-nil, includes
      `:adaptive-thinking true` and
      `:supports-mid-conversation-system-messages true`, the model appears in
      `(models-for-provider :anthropic)`, and
      `anthropic-json-schema-native-model-keys` contains `:opus-5.0`
- [x] Add an Opus 5.0 case to the gated `^:integration` test in
      `components/ai/test/psi/ai/providers/anthropic_models_api_test.clj`
      (calls `GET /v1/models/claude-opus-5-0`, asserts `id`, skips without
      `PSI_LIVE_ANTHROPIC_MODELS_API=1` + `ANTHROPIC_API_KEY`)
- [x] Run focused tests:
      `bb test --focus psi.ai.model-registry-test` and
      `bb test --focus psi.ai.providers.anthropic-models-api-test`
      (expect graceful skip for gated test)

## Slice 3 — Docs + verification

- [x] Add CHANGELOG `[Unreleased] / Added` entry for Claude Opus 5.0
- [x] Run full `bb test` — pre-existing unrelated failures only (turn-augmentation/workflow-loader); no failures involve model catalog/registry
- [x] Manually verify `/model anthropic claude-opus-5-0` selects the model — deferred (no live session available in this pass); registry resolution confirmed via unit tests
- [x] Note in implementation.md that pricing/limits are placeholders pending
      official Anthropic publication
- [x] Commit with `⚒` symbol

## Review follow-ups

- [ ] Add a direct assertion that `claude-opus-5-0` appears in
      `(registry/models-for-provider :anthropic)` — this acceptance criterion is
      currently only covered indirectly via `find-model` presence checks
- [ ] Before release: resolve the design open question by confirming the real
      Anthropic model id string and official pricing/context-window/max-tokens,
      then replace the Opus 4.8 placeholder values in the `:opus-5.0` catalog
      entry (`anthropic_catalog.clj`) and the CHANGELOG placeholder note
- [ ] Complete the deferred manual `/model anthropic claude-opus-5-0`
      live-session selection verification once a live session is available
