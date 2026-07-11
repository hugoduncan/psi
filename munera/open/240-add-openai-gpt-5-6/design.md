# 240 — Add support for OpenAI GPT-5.6

## Goal

Add GPT-5.6 to the OpenAI model catalog so it is a selectable,
user-visible model with correct capabilities, transport, and pricing.

## Why

OpenAI ships a new GPT-5.6 model. psi's model catalog is the single
source of truth for selectable models; without a catalog entry the model
cannot be chosen, priced, or routed to the correct transport.

## Context (current state)

Model definitions live in the `ai` component:

- `components/ai/src/psi/ai/models.clj` — canonical `built-in/all-models`
  catalog map. Existing OpenAI entries run `:gpt-5` … `:gpt-5.5`. Each
  entry declares `:id :name :provider :api :base-url :supports-reasoning
  :supports-images :supports-text :context-window :max-tokens
  :input-cost :output-cost :cache-read-cost :cache-write-cost`.
  - There is a single curated set (`openai-chat-completions-native-model-keys`,
    models.clj ~610–623) that lists which keys use OpenAI chat-completions
    native behaviour; `:gpt-5.5` is a member. This is the only native-key set
    containing `:gpt-5.5`. (The adjacent set at ~625–635 is
    `anthropic-json-schema-native-model-keys`, which is Anthropic-only and does
    not contain OpenAI keys.)
- `components/ai/src/psi/ai/model_registry.clj` — `openai-oauth-runtime-model`
  special-cases `gpt-5.5` to use the ChatGPT/Codex backend
  (`:openai-codex-responses`, `https://chatgpt.com/backend-api`) under
  OpenAI OAuth. Decide whether GPT-5.6 needs the same OAuth transport
  override.
- `components/shared-config/src/psi/shared_config/session_profiles.clj`
  references `"gpt-5.5"` in a `comment`/example (line ~278) — likely not
  required to change, but review.

## Resolved decisions (were: open questions)

1. **Model specs** — `:gpt-5.6` mirrors the established GPT-5.x catalog
   pattern (same `:context-window` 1000000 and `:max-tokens` 128000 as
   gpt-5.5), with costs on the established incrementing-per-release scale:
   `:input-cost` 6.0, `:output-cost` 35.0, `:cache-read-cost` 0.6,
   `:cache-write-cost` 0.0. This repo's OpenAI catalog is a synthetic
   fixture (versions run through fictional `gpt-5.1`…`gpt-5.5`, well past
   any real released OpenAI model); there is no external authoritative
   pricing source, so values are derived by following the same
   increment-per-version convention already present in the catalog.
2. **Capabilities** — `:supports-reasoning`, `:supports-images`,
   `:supports-text` all `true`, matching gpt-5.5.
3. **API / transport** — standard `:openai-completions` against
   `https://api.openai.com/v1`, same as gpt-5.5.
4. **Native-capability set membership** — `:gpt-5.6` joins
   `openai-chat-completions-native-model-keys` (the single native-key set
   containing `:gpt-5.5`).
5. **OAuth backend** — `:gpt-5.6` mirrors gpt-5.5 and uses the
   ChatGPT/Codex OAuth transport override in `openai-oauth-runtime-model`.
   The single-model check was generalized to a
   `openai-oauth-codex-model-ids` set containing `#{"gpt-5.5" "gpt-5.6"}`.
6. **Variants** — scoped to base `gpt-5.6` only; no `-mini`/`-codex`/
   `-pro`/`-chat-latest` variants added.

## Constraints

- Catalog entry is the single source of truth; no placeholder/guessed
  pricing or context sizes in shipped code.
- Behaviour-shaped by data: add a catalog entry + set membership, do not
  add per-model branching beyond the existing OAuth override pattern.
- Follow change_chain: update spec/tests for the catalog + capability
  sets alongside the code entry.

## Acceptance criteria

- `:gpt-5.6` present in `built-in/all-models` with complete, sourced
  field values.
- `find-model :openai "gpt-5.6"` returns the entry.
- Membership in the correct native-capability key set(s) matches the
  decided policy.
- OAuth runtime transport decision (override vs. none) implemented and
  covered by a test.
- Model is selectable via the same path used to select `gpt-5.5`.
- Tests in `components/ai` cover the new entry's presence and
  capability/transport classification; `bb test --focus` for touched
  namespaces passes.

## Out of scope

- GPT-5.6 variant models (mini/codex/pro/chat-latest) unless separately
  requested.
- Any provider-auth/OAuth credential changes beyond transport selection.
