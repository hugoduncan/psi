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
  - There is a curated set (`openai-chat-completions-native-model-keys`)
    that lists which keys use OpenAI chat-completions native behaviour;
    `:gpt-5.5` is a member.
  - There is a second nearby set (lines ~612–623) that also lists model
    keys `:gpt-5.5` belongs to — confirm which set(s) a new entry must
    join.
- `components/ai/src/psi/ai/model_registry.clj` — `openai-oauth-runtime-model`
  special-cases `gpt-5.5` to use the ChatGPT/Codex backend
  (`:openai-codex-responses`, `https://chatgpt.com/backend-api`) under
  OpenAI OAuth. Decide whether GPT-5.6 needs the same OAuth transport
  override.
- `components/shared-config/src/psi/shared_config/session_profiles.clj`
  references `"gpt-5.5"` in a `comment`/example (line ~278) — likely not
  required to change, but review.

## Open questions (resolve before plan.md)

1. **Model specs** — exact `:id`, display `:name`, `:context-window`,
   `:max-tokens`, and per-token costs (`:input-cost`, `:output-cost`,
   `:cache-read-cost`, `:cache-write-cost`) for GPT-5.6. Needs
   authoritative source (OpenAI pricing/model docs). Placeholder values
   are not acceptable in the catalog.
2. **Capabilities** — does GPT-5.6 support reasoning / images / text?
   (Assume all `true` like `gpt-5.5` unless docs say otherwise.)
3. **API / transport** — standard `:openai-completions` against
   `https://api.openai.com/v1`, same as gpt-5.5?
4. **Native-capability set membership** — should `:gpt-5.6` join
   `openai-chat-completions-native-model-keys` and the second key set?
5. **OAuth backend** — does GPT-5.6 need the ChatGPT/Codex OAuth
   transport override in `openai-oauth-runtime-model` (mirroring
   gpt-5.5), or only the standard API-key transport?
6. **Variants** — is this only the base `gpt-5.6`, or also
   `-mini`/`-codex`/`-pro`/`-chat-latest` variants? Scope to base unless
   requested.

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
