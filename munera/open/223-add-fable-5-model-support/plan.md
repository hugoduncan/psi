# 223 — Plan

## Approach

Additive catalog change following the existing `:opus-4.8` shape, plus the
resolved live-test parameterization, changelog, and one targeted doc edit. The
final catalog entry, pricing, capabilities, native-structured-output keying, and
test shape are all fixed in `design.md` ("Final catalog entry", "Resolved
policy", "Resolved ambiguities") — implementation is mechanical transcription
against those resolved facts.

Key decisions (catalog/live-test/docs decisions inherited from design; the
non-live test structure was decided during the plan/steps ambiguity-review
pass, since design.md resolved only the live-test shape):

- Add `:fable-5` to `anthropic-models` in
  `components/ai/src/psi/ai/models.clj`, structurally near-identical to
  `:opus-4.8`, using the design's exact target entry (id `"claude-fable-5"`,
  pricing `10.0`/`50.0`/`1.0`/`12.5`, 1M context, 128k max-tokens,
  adaptive-thinking + images + text + mid-conversation system messages).
- Add `:fable-5` to the `anthropic-json-schema-native-model-keys` set for native
  JSON-Schema structured output.
- Prove non-live catalog resolution + structured-output capability by
  **extending** the existing deftests in
  `components/ai/test/psi/ai/model_registry_test.clj` (no new deftests):
  extend `init-built-ins-only-test` to assert
  `registry/find-model :anthropic "claude-fable-5"` (string id) and
  `built-in/all-models` keyed by keyword `:fable-5`; extend
  `built-in-structured-output-capabilities-test` with a Fable 5 block that is a
  **full mirror** of the Opus 4.8 block (model_registry_test.clj:144-151) —
  asserting the catalog-metadata fields (`(some? model)`,
  `:name "Claude Fable 5"`, `:adaptive-thinking true`,
  `:supports-mid-conversation-system-messages true`) **and** the public
  `structured-output/effective-capability` surface (`:supported? true`,
  `:native-mechanism :anthropic/json-schema-output`,
  `:provider-native` ∈ `:strategies`). The catalog-metadata assertions are
  required because no other step asserts those Fable 5 field values, so the full
  mirror is what covers the acceptance criterion "capabilities … matching the
  agreed spec" in the non-live suite. This non-live test structure was decided
  by the plan/steps ambiguity-review pass (not design.md).
- Parameterize the live Anthropic models-api test over a set
  `target-model-ids #{"claude-opus-4-8" "claude-fable-5"}`, retaining Opus 4.8
  coverage; rename the two deftests to drop Opus-specific names.
- Add a CHANGELOG `[Unreleased]` → `Added` entry (design supplies the draft).
- Make exactly one prose-doc edit: add Claude Fable 5 to the
  `doc/extension-api.md` mid-conversation system-message support enumeration.

Verification: catalog resolution exercised through
`model-registry/find-model` and structured-output capability; `bb test`
(non-live) green; clj-kondo clean. The live `/v1/models` assertions remain
opt-in (gated by `PSI_LIVE_ANTHROPIC_MODELS_API=1` + `ANTHROPIC_API_KEY`) and
are not part of the default green-bar requirement.

## Risks

- **Map-key ordering / keyword form**: the catalog key is `:fable-5` (no dot),
  unlike the dotted `:opus-4.8`; ensure consistency between the entry key and
  the native-keys set membership. Low risk — verified by a resolution test.
- **Test rename collisions**: renamed deftests must not shadow existing names
  and must keep the `^:integration` metadata + opt-in gating macro. Low risk.
- **Live test not run by default**: acceptance includes the live assertion but
  it is opt-in; CI/`bb test` cannot prove the id is real without the env flags.
  This is by design (the live proof is a manual/opt-in gate). No mitigation
  beyond preserving the gating.
- **Doc enumeration drift**: only the definitive `extension-api.md` enumeration
  changes; do not touch the illustrative `configuration.md`/`tui.md` examples
  (explicitly out of scope per design).

## Slice order

1. **Catalog entry** — add `:fable-5` model + native-structured-output keying in
   `models.clj`; prove resolution and structured-output capability.
2. **Live verification test** — parameterize and rename the Anthropic
   models-api test over the target-id set.
3. **Docs + changelog** — `doc/extension-api.md` enumeration edit + CHANGELOG
   `[Unreleased]` → `Added` entry.
4. **Verify + finalize** — `bb test` (non-live) green, clj-kondo clean, coherence
   check, commit.
