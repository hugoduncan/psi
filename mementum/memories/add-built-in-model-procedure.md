🔁 add-built-in-model-procedure

Adding a built-in AI model is a purely additive data change across a few
coordinated sites in `components/ai`:

1. **Catalog entry** — add the model map under its provider group in
   `psi/ai/models.clj` (e.g. `anthropic-models`). Mirror a sibling entry
   field-for-field (same key order, only id/name/pricing differ). It flows
   through `all-models` / `built-in-catalog` automatically — no extra
   registration.
2. **Native-capability key set** — for native structured output, add the
   catalog keyword (e.g. `:fable-5`) to the relevant set such as
   `anthropic-json-schema-native-model-keys`. This catalog/native-key-set
   dual edit is a known non-orthogonality (two sites per model); it is the
   established pattern — do not template it away.
3. **Cache pricing convention** — when only input/output costs are given,
   derive `:cache-read-cost = input × 0.1`, `:cache-write-cost = input × 1.25`
   (the ratio every existing entry uses).
4. **Live opt-in proof** — extend `anthropic_models_api_test.clj`
   (gated by `PSI_LIVE_ANTHROPIC_MODELS_API=1` + `ANTHROPIC_API_KEY`) to assert
   the canonical id on `/v1/models`. Parameterize ids over a set, don't fork.
5. CHANGELOG `[Unreleased] → Added` (user-visible); docs only if a definitive
   capability enumeration (not illustrative examples) names models.
