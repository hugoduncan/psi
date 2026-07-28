# 247 — Add Claude Opus 5 model

## Goal

Add the Anthropic **Claude Opus 5** model to the psi model catalog so it is
selectable via `/model`, resolvable through the model registry, and correctly
request-shaped by the Anthropic provider.

## Context

The Anthropic catalog lives in
`components/ai/src/psi/ai/models/anthropic_catalog.clj`. New Anthropic models
follow the established keyed-entry pattern (see `:opus-4.8`, the current latest
Opus). Opus 5.0 is the next Opus after 4.8 and uses the same transports and
capability protocols:

- `:api :anthropic-messages` transport.
- `:adaptive-thinking true` (Opus 4.7+ adaptive-thinking API protocol; the
  `adaptive-thinking?` predicate in `providers/anthropic.clj` dispatches on this
  flag, so request shaping is inherited automatically — no provider changes).
- `:supports-mid-conversation-system-messages true` (Opus 4.8+ capability).
- Native JSON Schema structured output → add `:opus-5` to
  `anthropic-json-schema-native-model-keys` in
  `components/ai/src/psi/ai/models.clj`.

The authoritative source for the model `id`, `display_name`, and `created_at`
is Anthropic's `GET /v1/models/{model_id}` endpoint. Pricing, context window,
max-tokens, and capability flags are not returned by that API and must be
sourced from Anthropic's published docs; mirror Opus 4.8 as a placeholder until
official values are published.

## Scope

1. `components/ai/src/psi/ai/models/anthropic_catalog.clj`: add `:opus-5`
   entry immediately after `:opus-4.8`.
2. `components/ai/src/psi/ai/models.clj`: add `:opus-5` to
   `anthropic-json-schema-native-model-keys`.

No provider-layer changes are required — adaptive-thinking, mid-conversation
system-message support, and structured-output shaping all dispatch on model
metadata already handled generically.

## Model attributes

| Attribute | Value |
|---|---|
| `:id` | `"claude-opus-5"` |
| `:name` | `"Claude Opus 5"` |
| `:provider` | `:anthropic` |
| `:api` | `:anthropic-messages` |
| `:base-url` | `"https://api.anthropic.com"` |
| `:supports-reasoning` | `true` |
| `:adaptive-thinking` | `true` |
| `:supports-mid-conversation-system-messages` | `true` |
| `:supports-images` | `true` |
| `:supports-text` | `true` |
| `:context-window` | `1000000` (placeholder — Opus 4.8 value) |
| `:max-tokens` | `128000` (placeholder) |
| `:input-cost` | `5.0` (placeholder — update on published pricing) |
| `:output-cost` | `25.0` (placeholder) |
| `:cache-read-cost` | `0.5` (placeholder) |
| `:cache-write-cost` | `6.25` (placeholder) |

Placeholder values mirror Opus 4.8; replace once Anthropic publishes official
Opus 5.0 pricing and limits.

## Acceptance criteria

- `(psi.ai.model-registry/find-model :anthropic "claude-opus-5")` returns a
  non-nil model map.
- The returned map includes `:adaptive-thinking true` and
  `:supports-mid-conversation-system-messages true`.
- The model appears in
  `(psi.ai.model-registry/models-for-provider :anthropic)`.
- `psi.ai.models/anthropic-json-schema-native-model-keys` contains `:opus-5`.
- A focused unit test confirms the new model entry and its structured-output +
  mid-conversation-system-message capability annotations.
- Optional gated `^:integration` test (env `PSI_LIVE_ANTHROPIC_MODELS_API=1` +
  `ANTHROPIC_API_KEY`) calls `GET /v1/models/claude-opus-5` and asserts the
  response `id` equals `"claude-opus-5"`; skips gracefully when the gate or
  key is absent (follow the pattern used by the Opus 4.8 task).
- `/model anthropic claude-opus-5` selects the model in a live session.
- Existing model tests remain green (`bb test`).
- CHANGELOG `[Unreleased]` gains an `Added` entry for the new model.

## Open questions

- ~~Confirm the real Anthropic model id string~~ **RESOLVED**: the initially
  assumed id `claude-opus-5-0` was wrong and 404'd against the live API when
  selected via OAuth. The real id is **`claude-opus-5`** (display name "Claude
  Opus 5"), confirmed via `GET /v1/models` using live OAuth credentials. The
  catalog entry, model key (`:opus-5`), tests, docs, and CHANGELOG now use
  `claude-opus-5`. Premature closure was reverted to apply this fix.
- Official pricing/context-window/max-tokens still unpublished — placeholders
  mirror Opus 4.8 and must be revisited before release.
