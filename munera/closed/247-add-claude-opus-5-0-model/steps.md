# Steps — 247 Add Claude Opus 5 model

## Slice 1 — Catalog + registry data

- [x] Add `:opus-5` entry to
      `components/ai/src/psi/ai/models/anthropic_catalog.clj` immediately
      after `:opus-4.8`, with attributes from design.md
      (`:id "claude-opus-5"`, `:name "Claude Opus 5"`,
      `:adaptive-thinking true`,
      `:supports-mid-conversation-system-messages true`, placeholder
      pricing/limits mirroring Opus 4.8)
- [x] Add `:opus-5` to `anthropic-json-schema-native-model-keys` in
      `components/ai/src/psi/ai/models.clj`
- [x] Reload/lint changed namespaces (`clj-kondo --lint` on both files)

## Slice 2 — Tests

- [x] Extend `components/ai/test/psi/ai/model_registry_test.clj` with a
      focused test asserting:
      `(find-model :anthropic "claude-opus-5")` is non-nil, includes
      `:adaptive-thinking true` and
      `:supports-mid-conversation-system-messages true`, the model appears in
      `(models-for-provider :anthropic)`, and
      `anthropic-json-schema-native-model-keys` contains `:opus-5`
- [x] Add an Opus 5.0 case to the gated `^:integration` test in
      `components/ai/test/psi/ai/providers/anthropic_models_api_test.clj`
      (calls `GET /v1/models/claude-opus-5`, asserts `id`, skips without
      `PSI_LIVE_ANTHROPIC_MODELS_API=1` + `ANTHROPIC_API_KEY`)
- [x] Run focused tests:
      `bb test --focus psi.ai.model-registry-test` and
      `bb test --focus psi.ai.providers.anthropic-models-api-test`
      (expect graceful skip for gated test)

## Slice 3 — Docs + verification

- [x] Add CHANGELOG `[Unreleased] / Added` entry for Claude Opus 5
- [x] Run full `bb test` — pre-existing unrelated failures only (turn-augmentation/workflow-loader); no failures involve model catalog/registry
- [x] Manually verify `/model anthropic claude-opus-5` selects the model — deferred (no live session available in this pass); registry resolution confirmed via unit tests
- [x] Note in implementation.md that pricing/limits are placeholders pending
      official Anthropic publication
- [x] Commit with `⚒` symbol

## Review follow-ups

- [x] Add a direct assertion that `claude-opus-5` appears in
      `(registry/models-for-provider :anthropic)` — this acceptance criterion is
      currently only covered indirectly via `find-model` presence checks
- [ ] Before release: resolve the design open question by confirming the real
      Anthropic model id string and official pricing/context-window/max-tokens,
      then replace the Opus 4.8 placeholder values in the `:opus-5` catalog
      entry (`anthropic_catalog.clj`) and the CHANGELOG placeholder note
- [ ] Complete the deferred manual `/model anthropic claude-opus-5`
      live-session selection verification once a live session is available

## Implementation review follow-ups (247 review pass)

- [x] (optional) Assert `:supports-reasoning true` on the `:opus-5` model in
      `model_registry_test.clj` — the design lists it as a model attribute but no
      test currently guards it (all other capability flags are asserted)

## Test review follow-ups (247 test review)

- [x] Add an `opus-5-0-catalog-entry-test` to `model_registry_test.clj`
      mirroring `fable-5-catalog-entry-test` / `sonnet-5-catalog-entry-test`:
      assert the full design attribute table on `:opus-5` — `:provider`,
      `:api`, `:base-url`, `:supports-images`, `:supports-text`,
      `:context-window` (1000000), `:max-tokens` (128000), `:input-cost` (5.0),
      `:output-cost` (25.0), `:cache-read-cost` (0.5),
      `:cache-write-cost` (6.25). Currently the design's pricing/limits
      attributes are unguarded (only name + capability flags are asserted),
      so a regression or typo in the placeholder values would go undetected —
      unlike every sibling model, which has a dedicated pricing/limits test.
- [x] (optional) Guard the "placeholders mirror `:opus-4.8` exactly" design
      invariant with an assertion equating the `:opus-5` pricing/limits fields
      to the `:opus-4.8` ones, so intentional divergence (when real Opus 5.0
      values are published) is distinguished from accidental drift. Subsumed by
      the concrete-value catalog-entry test above if that is added first.
      — subsumed: `opus-5-0-catalog-entry-test` now pins the exact placeholder
      values; a separate mirror-invariant test would duplicate that guard and
      conflict once real Opus 5.0 values diverge, so not added.

## Test-shaper review follow-ups (247 test-shaper pass)

- [x] Deduplicate the two `:opus-5` tests in `model_registry_test.clj`. Since
      `opus-5-0-catalog-entry-test` was added, the "Claude Opus 5 is findable
      and declares native Anthropic JSON Schema output" block (in
      `anthropic-json-schema-output-test`, ~L276) now redundantly re-asserts
      `some?`, `:name`, `:adaptive-thinking`,
      `:supports-mid-conversation-system-messages`, and `:supports-reasoning` —
      all of which are already pinned by `opus-5-0-catalog-entry-test`. Reduce
      the "findable" block to its distinct concern: the structured-output
      capability (`:supported?`, `:native-mechanism`, `:strategies`) and the
      `models-for-provider` enumeration membership. (test-shaper `economical`:
      `minimal(redundant_tests)` ∧ `one_test_per_distinct_behavior`.)
- [x] Fix the `single_concern` violation in `anthropic-json-schema-output-test`:
      the `claude-opus-5` `models-for-provider` enumeration-membership
      assertion (~L289–291) is unrelated to that deftest's stated concern
      (native Anthropic JSON Schema output). Move it into a behaviour-focused
      home — e.g. the `opus-5-0-catalog-entry-test` or a dedicated
      provider-enumeration test — so each test asserts one concern.
      (test-shaper `single_concern`.)
- [x] (optional) Align Opus 5.0 test structure with its `:fable-5` / `:sonnet-5`
      siblings for `consistent(structure)`. Fable/Sonnet have a shared
      capability assertion in `anthropic-json-schema-output-test` plus one
      catalog-entry test; Opus 5.0 uniquely also carries a full per-model
      "findable" block mirroring Opus 4.8 (metadata + capability). Once the two
      dedup steps above land, Opus 5.0's shape should match its siblings —
      verify the remaining Opus 5.0 capability assertion matches the terse
      Fable/Sonnet form rather than the fuller Opus 4.8 form.

## Docs review follow-ups (247 review-task-docs pass)

- [x] Update `doc/extension-api.md` (~L217): the mid-conversation
      system-message capability enumeration lists "Claude Opus 4.8, Claude
      Fable 5, Claude Sonnet 5" but omits Claude Opus 5, which the catalog
      declares `:supports-mid-conversation-system-messages true`. Sibling task
      (Sonnet 5, commit `31d93c55c`) established the convention of adding each
      new supporting model to this list; the doc is now stale/incomplete for
      Opus 5.0. Add "Claude Opus 5" to that enumeration.
- [x] (optional) Refresh the `/model` example in `doc/tui.md` (~L68), which
      still uses Claude Opus 4.8 as the illustrative latest Anthropic
      adaptive-thinking / mid-conversation-system-message model. Opus 5.0 is
      now the latest Opus with the same capabilities; consider updating the
      example (or adding an Opus 5.0 line) for currency. Example-only, not
      strictly stale.

## Docs review follow-ups (247 review-task-docs pass 2)

- [x] (optional) Refresh the adaptive-thinking example in `doc/configuration.md`
      (~L254), which still cites "Claude Opus 4.8 and Claude Sonnet 5" as the
      illustrative Anthropic adaptive-thinking / `:xhigh`-distinct models. Opus
      5.0 is now the latest adaptive-thinking Opus (same capabilities); for
      currency and consistency with the already-refreshed `doc/tui.md` `/model`
      example, consider updating this list to lead with Claude Opus 5 (or add
      it). Example-only, not strictly stale.

## Test-shaper review follow-ups (247 test-shaper pass 2)

- [x] (optional) Resolve the residual `single_concern` blend in
      `opus-5-0-catalog-entry-test` (`model_registry_test.clj`, ~L338): its
      `testing` label reads "carries the agreed metadata, capability, and
      pricing values", but the deftest also asserts `models-for-provider`
      enumeration membership (~L362) — an undisclosed second concern that no
      sibling catalog-entry test (`fable-5`, `sonnet-5`) carries. Either widen
      the `testing` label to name both concerns, or move the enumeration-
      membership assertion into its own `testing` block / dedicated
      provider-enumeration test so the label matches the asserted behaviour and
      the Opus 5.0 test regains structural parity with its siblings.
      (test-shaper `single_concern` ∧ `consistent(structure)` ∧
      `meaningful_failures`.)
