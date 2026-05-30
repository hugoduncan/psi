# 190 — Add Claude Opus 4.8 model

## Goal

Register `claude-opus-4-8` as a supported model in the psi model catalog so
users can configure and use it via `model-id: "claude-opus-4-8"`.

## Context

The model catalog lives in `components/ai/src/psi/ai/models.clj`.  New
Anthropic models follow the established pattern:

- Add a keyed entry to `anthropic-models` with provider, API, context window,
  pricing, and capability flags.
- Add the key to `anthropic-json-schema-native-model-keys` if the model
  supports native JSON Schema structured output (all Anthropic models from
  4.5 onward do).
- Add `:adaptive-thinking true` if the model uses the adaptive thinking API
  (introduced with Opus 4.7).

Opus 4.8 is the next Opus model after 4.7.  It uses the same adaptive-thinking
API protocol and the same Anthropic Messages API transport.

## Anthropic Models API

Anthropic exposes a models endpoint documented at
https://docs.anthropic.com/en/api/models:

- `GET /v1/models` — lists all available models for the authenticated key.
- `GET /v1/models/{model_id}` — retrieves a single model by ID.

Both endpoints require the standard `x-api-key` and `anthropic-version` headers.
The `GET /v1/models/claude-opus-4-8` response is the authoritative source for
the model's `id`, `display_name`, and `created_at` fields.  Pricing and
capability flags (context window, max tokens, adaptive-thinking) are not
returned by the API and must be sourced from Anthropic's published documentation.

## Scope

Changes in `components/ai/src/psi/ai/models.clj`:

1. Add `:opus-4.8` entry to `anthropic-models`.
2. Add `:opus-4.8` to `anthropic-json-schema-native-model-keys`.

No provider-layer changes are needed: the `adaptive-thinking?` predicate in
`providers/anthropic.clj` already dispatches on the `:adaptive-thinking` flag,
so the new model inherits correct request shaping automatically.

New gated test file `components/ai/test/psi/ai/providers/anthropic_models_api_test.clj`:

3. `^:integration` test gated on `PSI_LIVE_ANTHROPIC_MODELS_API=1` and
   `ANTHROPIC_API_KEY` that calls `GET /v1/models` and asserts
   `"claude-opus-4-8"` appears in the response.
4. `^:integration` test that calls `GET /v1/models/claude-opus-4-8` and
   asserts the response `id` field equals `"claude-opus-4-8"`.

Both tests skip gracefully (pass with a skip message) when the env-var gate or
API key is absent, following the same pattern as
`anthropic_live_structured_output_test.clj`.

## Model attributes

| Attribute | Value |
|---|---|
| `:id` | `"claude-opus-4-8"` |
| `:name` | `"Claude Opus 4.8"` |
| `:provider` | `:anthropic` |
| `:api` | `:anthropic-messages` |
| `:base-url` | `"https://api.anthropic.com"` |
| `:supports-reasoning` | `true` |
| `:adaptive-thinking` | `true` |
| `:supports-images` | `true` |
| `:supports-text` | `true` |
| `:context-window` | `1000000` |
| `:max-tokens` | `128000` |
| `:input-cost` | `5.0` ($/M tokens — placeholder, update when pricing is published) |
| `:output-cost` | `25.0` |
| `:cache-read-cost` | `0.5` |
| `:cache-write-cost` | `6.25` |

Pricing mirrors Opus 4.7 as a placeholder; update once Anthropic publishes
official pricing for 4.8.

## Acceptance criteria

- `(psi.ai.model-registry/find-model :anthropic "claude-opus-4-8")` returns a
  non-nil model map.
- The returned map includes `:adaptive-thinking true`.
- The model appears in `(psi.ai.model-registry/models-for-provider :anthropic)`.
- `psi.ai.models/anthropic-json-schema-native-model-keys` contains `:opus-4.8`.
- Existing model tests remain green (`bb test`).
- A focused unit test confirms the new model entry and its structured-output
  capability annotation.
- A gated `^:integration` test (env `PSI_LIVE_ANTHROPIC_MODELS_API=1` +
  `ANTHROPIC_API_KEY`) calls `GET /v1/models` and asserts `"claude-opus-4-8"`
  is present in the response.
- A gated `^:integration` test calls `GET /v1/models/claude-opus-4-8` and
  asserts the response `id` equals `"claude-opus-4-8"`.
- Both gated tests skip gracefully when the gate or key is absent.
