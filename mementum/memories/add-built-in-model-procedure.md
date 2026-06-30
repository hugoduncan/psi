🔁 add-built-in-model-procedure

Adding a built-in AI model is a coordinated additive change across a few sites:

1. **Authoritative facts first** — record concrete provider citations/API endpoints in the task implementation notes for id, limits, capabilities, structured output, and pricing; do not rely on uncited prose.
2. **Catalog entry** — add the model map under its provider group in `psi/ai/models.clj` (e.g. `anthropic-models`). Mirror a sibling entry field-for-field. It flows through `all-models` / `built-in-catalog` automatically.
3. **Native-capability key set** — for native structured output, add the catalog keyword to the relevant set such as `anthropic-json-schema-native-model-keys`; this known two-site edit is the established pattern.
4. **Tests at multiple boundaries** — cover catalog/registry lookup, structured-output effective capability, model-selection helper resolution, and the user command path (e.g. `/model provider id`) so selectable means selectable by users.
5. **Live opt-in proof** — extend gated provider API tests over a target-id set; keep skip path green.
6. **User-visible surfaces** — add CHANGELOG `[Unreleased] → Added`; update docs only for definitive capability enumerations, not illustrative examples.
