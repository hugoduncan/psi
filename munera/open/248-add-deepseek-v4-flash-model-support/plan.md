# Plan

## Approach

Two small, independent slices, both additive and config/docs/schema-only —
no changes to `providers/anthropic.clj`'s request-shaping logic itself:

1. **Schema slice**: add `:adaptive-thinking` to the custom-provider
   `ModelDef` schema in `user_models.clj`. `expand-model` already merges all
   `model-def` keys through verbatim, so this is a pure schema-permission
   change. Prove it with `user_models_test.clj` tests (accepted, flows
   through, optional/absent-safe) and an `anthropic_test.clj` request-shaping
   test using a raw custom-provider-shaped model map (following the existing
   `MiniMax-M2.7` literal pattern already in that file) asserting the
   adaptive `output_config.effort` shape appears when `:adaptive-thinking
   true` is set on a non-catalog model map.
2. **Docs slice**: add the DeepSeek `models.edn` example to
   `doc/custom-providers.md` (new subsection, following the existing MiniMax
   /Anthropic-compatible-proxy example shape) and document the new
   `:adaptive-thinking` field there. Add the CHANGELOG entry.

Slice 1 before slice 2, since the docs example uses `:adaptive-thinking
true` and should only be written once the field is actually schema-valid and
proven.

## Risks

- Low risk overall: additive optional schema field, no default-value change,
  no existing custom-provider config becomes invalid.
- `structured-output/normalize-model` runs after the merge in `expand-model`
  — confirm it does not strip or choke on an unrecognized-to-it
  `:adaptive-thinking` key (it currently operates only on
  `:capabilities :structured-output`, so this should be a no-op, but verify
  via the parse test rather than assuming).
- Pricing/context-window numbers in the docs example are sourced from
  DeepSeek's published pricing page (2026-07) and may drift; the example is
  illustrative, consistent with how the existing MiniMax example already
  disclaims exact figures ("confirm the provider's current base URL and
  model ids in its own docs").

## Slice order

1. `user_models.clj` schema change + `user_models_test.clj` tests +
   `anthropic_test.clj` adaptive-thinking-via-custom-model-map test.
2. `doc/custom-providers.md` DeepSeek example + `:adaptive-thinking` field
   docs + CHANGELOG entry.
3. Full verification (`bb test`, `clj-kondo`) and commit.
