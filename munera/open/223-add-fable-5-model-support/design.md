# 223 — Add support for the latest Fable 5 model

## Intent

Add first-class support for the latest **Fable 5** model to psi so a user can
select and run it like any other built-in model, with correct capabilities,
pricing, and structured-output behaviour, and with a live opt-in proof that the
model id is real on the provider's model-listing endpoint.

## Problem / context

psi's model catalog is the single source of truth for selectable models
(`components/ai/src/psi/ai/models.clj`, indexed by
`components/ai/src/psi/ai/model_registry.clj`). Each built-in model declares
provider, api protocol, capabilities (`supports-reasoning`, `supports-images`,
`adaptive-thinking`, `supports-mid-conversation-system-messages`,
context-window, max-tokens) and pricing (input/output/cache costs). Structured
output capability is assigned in `models.clj` via per-model-key native-capability
sets (e.g. `anthropic-json-schema-native-model-keys`).

A live, opt-in integration test
(`components/ai/test/psi/ai/providers/anthropic_models_api_test.clj`) verifies
that a target model id (currently `claude-opus-4-8`) is actually present and
retrievable on the Anthropic `/v1/models` endpoint, gated by
`PSI_LIVE_ANTHROPIC_MODELS_API=1` and `ANTHROPIC_API_KEY`.

Fable 5 is not yet in the catalog. Adding it must follow the existing catalog
shape and extend the live-verification proof to cover its canonical id.

## Scope

In scope:

- Add a Fable 5 model entry to the built-in catalog with correct metadata.
- Wire its structured-output capability via the appropriate native-capability
  key set.
- Extend (or add) a live opt-in `/v1/models`-style verification test for the
  Fable 5 canonical id.
- Update user-facing docs / changelog if model availability is documented.

Out of scope (adjacent, separate tasks if needed):

- Changing any default model selection.
- New provider transport/api protocol implementation beyond what an existing
  `:api` value already supports (unless Fable 5 requires a genuinely new
  protocol — see open questions).
- Custom user/project model-file changes (`models.edn`).

## Acceptance criteria

- Fable 5 appears in `all-models` with provider, api, capabilities, and pricing
  matching the agreed spec.
- The model is selectable/resolvable through `model-registry/find-model` and the
  normal model-cycling path.
- Structured output capability resolves correctly for Fable 5.
- The live-verification test asserts Fable 5's canonical id is present and
  retrievable on the provider model-listing endpoint (opt-in, gated by the
  existing env flags).
- `bb test` green (non-live suite); clj-kondo clean.
- Docs/changelog updated where model availability is user-visible.

## Resolved facts (from live Anthropic `/v1/models`, 2026-06)

Discovered by querying the Anthropic Models API with the agent OAuth token:

- **Provider/family**: Anthropic. `:provider :anthropic`, `:api
  :anthropic-messages`, `:base-url "https://api.anthropic.com"`.
- **Canonical id**: `"claude-fable-5"`; `display_name "Claude Fable 5"`.
  Proposed catalog keyword: `:fable-5`.
- **Thinking**: adaptive only (`thinking.types.enabled=false`,
  `adaptive=true`) → `:supports-reasoning true` + `:adaptive-thinking true`
  (same protocol family as Opus 4.7/4.8).
- **Capabilities**: `image_input` supported (`:supports-images true`),
  text (`:supports-text true`), `pdf_input` supported,
  `context-window 1000000` (`max_input_tokens`), `max-tokens 128000`.
- **Structured output**: `structured_outputs` supported → native JSON-schema →
  add `:fable-5` to `anthropic-json-schema-native-model-keys` in `models.clj`.
- **Test placement**: extend the existing Anthropic models-api test
  (`anthropic_models_api_test.clj`) to also assert `"claude-fable-5"`.

The catalog entry is structurally near-identical to `:opus-4.8`.

## Resolved policy (from user, 2026-06)

- **Pricing**: `:input-cost 10.0`, `:output-cost 50.0` (per Mtok, confirmed by
  user). Cache costs not separately provided → apply the Anthropic standard
  ratio used by every existing built-in entry (`cache-read = input × 0.1`,
  `cache-write = input × 1.25`): `:cache-read-cost 1.0`,
  `:cache-write-cost 12.5`.
- **Mid-conversation system messages**:
  `:supports-mid-conversation-system-messages true`.
- **Default model**: additive only — Fable 5 changes no default selection.

## Final catalog entry (target)

```clojure
:fable-5
{:id "claude-fable-5"
 :name "Claude Fable 5"
 :provider :anthropic
 :api :anthropic-messages
 :base-url "https://api.anthropic.com"
 :supports-reasoning true
 :adaptive-thinking true
 :supports-mid-conversation-system-messages true
 :supports-images true
 :supports-text true
 :context-window 1000000
 :max-tokens 128000
 :input-cost 10.0
 :output-cost 50.0
 :cache-read-cost 1.0
 :cache-write-cost 12.5}
```

Plus: add `:fable-5` to `anthropic-json-schema-native-model-keys`; extend
`anthropic_models_api_test.clj` to assert `"claude-fable-5"` present and
retrievable.

Design is complete and unambiguous; ready for planning.
