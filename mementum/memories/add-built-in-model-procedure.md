🔁 add-built-in-model-procedure

Adding a built-in AI model is a coordinated additive change across a few sites:

1. **Authoritative facts first** — record concrete provider citations/API endpoints in the task implementation notes for id, limits, capabilities, structured output, and pricing; do not rely on uncited prose. When official values are unpublished, mirror the newest sibling as an explicit, sanctioned placeholder and record an open question to revisit before release.
2. **Catalog entry** — add the model map under its provider group. Anthropic models live in a dedicated `psi/ai/models/anthropic_catalog.clj` (`anthropic-models`); OpenAI models in `psi/ai/models.clj`. Both merge through `all-models` / `built-in-catalog` automatically. Place the new entry immediately after its newest sibling and mirror it field-for-field.
3. **Native-capability key set** — for native structured output, add the catalog keyword to the relevant set such as `anthropic-json-schema-native-model-keys` (in `psi/ai/models.clj`); this known two-site edit is the established pattern.
4. **Tests at multiple boundaries** — cover catalog/registry lookup, structured-output effective capability, model-selection helper resolution, and the user command path (e.g. `/model provider id`) so selectable means selectable by users. Give the entry a dedicated catalog-entry test pinning the *full* attribute table (pricing/limits included), mirroring sibling `*-catalog-entry-test`s — unguarded placeholder values silently drift.
5. **Live opt-in proof** — extend gated provider API tests over a target-id set; keep skip path green.
6. **User-visible surfaces** — add CHANGELOG `[Unreleased] → Added`; update docs only for definitive capability enumerations (e.g. mid-conversation-system-message support lists), not illustrative examples.

Capabilities (adaptive-thinking, mid-conversation system messages, structured-output shaping) dispatch generically on model metadata — a new model is pure keyed data; never touch provider/dispatch logic.
