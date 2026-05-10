Goal: Add Claude Opus 4.7 (Anthropic) and GPT-5.5 (OpenAI) to psi's built-in model catalog, and update any model-capability checks that need to cover the new generations.

## Intent

Keep psi's built-in model list current so users can select and use the latest Anthropic and OpenAI models without needing a custom `models.edn` workaround. The task is purely additive: new entries in `psi.ai.models` plus targeted updates to any capability predicates that gate on model generation.

## Problem statement

`psi.ai.models` currently tops out at:
- Anthropic: `claude-sonnet-4-6`, `claude-opus-4-6`, `claude-haiku-4-5`
- OpenAI: `gpt-5.4` (and family: mini, nano, pro)

Both providers have released newer generations. The confirmed specs are:

### Anthropic (from platform.claude.com/docs/en/about-claude/models/overview and anthropic.com/pricing)

| Model | API ID | Context | Max output | Input $/MTok | Output $/MTok | Cache write | Cache read | Reasoning |
|---|---|---|---|---|---|---|---|---|
| Claude Opus 4.7 | `claude-opus-4-7` | 1M | 128k | $5 | $25 | $6.25 | $0.50 | adaptive (xhigh) |
| Claude Sonnet 4.6 | `claude-sonnet-4-6` | 1M | 64k | $3 | $15 | $3.75 | $0.30 | extended thinking |
| Claude Haiku 4.5 | `claude-haiku-4-5` | 200k | 64k | $1 | $5 | $1.25 | $0.10 | extended thinking |

Note: Sonnet 4.6 and Haiku 4.5 are already in `psi.ai.models`. **Only Opus 4.7 is new.**

Opus 4.7 characteristics:
- Adaptive thinking (not extended thinking) — `supports-reasoning true`
- No dated snapshot ID in the public API alias; `claude-opus-4-7` is the canonical alias
- AWS Bedrock: `anthropic.claude-opus-4-7`
- Context 1M tokens, max output 128k tokens

### OpenAI (from developers.openai.com/api/docs/models)

| Model | API ID | Context | Max output | Input $/MTok | Output $/MTok | Reasoning |
|---|---|---|---|---|---|---|
| GPT-5.5 | `gpt-5.5` | 1M | 128k | $5 | $30 | none/low/medium/high/xhigh |
| GPT-5.4 | `gpt-5.4` | 1M | 128k | $2.50 | $15 | (already present) |
| GPT-5.4 mini | `gpt-5.4-mini` | 400k | 128k | $0.75 | $4.50 | (already present) |

Note: GPT-5.4 is already in `psi.ai.models`. **Only GPT-5.5 is new.** GPT-5.4-mini is not yet in psi's catalog and should be added too.

GPT-5.5 characteristics:
- Supports reasoning at all effort levels including xhigh
- `supports-reasoning true`
- API: `:openai-completions` (same as gpt-5.4)
- Cache read cost: OpenAI does not publish a separate cache-read price for 5.5 (use 0.0 as current placeholder, same as gpt-5.4)

## Scope

In scope:
- Add `:opus-4.7` to `psi.ai.models/anthropic-models`.
- Add `:gpt-5.5` to `psi.ai.models/openai-models`.
- Add `:gpt-5.4-mini` to `psi.ai.models/openai-models` (present in the OpenAI catalog but missing from psi).
- Update the `supportsXhigh` logic in `models.ts` equivalent in psi — specifically check if `providers/openai/reasoning.clj` or `providers/anthropic.clj` has any generation-gated capability checks that need updating for the new models.
- Update `model_registry_test.clj` to assert presence of at least one new model per provider.

Out of scope:
- Removing or deprecating older models — separate decision, not resolved here.
- Adding models from other providers (Bedrock, Azure, Vertex, etc.).
- Changes to the model registry merge logic or user-models loading.
- UI display changes (model selector labels, footer rendering).
- Pricing changes for existing models.
- Cache read pricing for GPT-5.5 if OpenAI does not publish it.

## Acceptance criteria

- `psi.ai.models/anthropic-models` contains `:opus-4.7` with correct `:id "claude-opus-4-7"`, `:supports-reasoning true`, context 1M, max-tokens 128k (expressed as 131072 to match the existing convention), and pricing $5/$25/$6.25/$0.50.
- `psi.ai.models/openai-models` contains `:gpt-5.5` with `:id "gpt-5.5"`, `:supports-reasoning true`, context 1M, max-tokens 128k, pricing $5/$30.
- `psi.ai.models/openai-models` contains `:gpt-5.4-mini` with `:id "gpt-5.4-mini"`, `:supports-reasoning true`, context 400k, max-tokens 128k, pricing $0.75/$4.50.
- `psi.ai.models/all-models` includes all three new entries (derived automatically).
- `model_registry_test.clj` `init-built-ins-only-test` asserts `(registry/find-model :anthropic "claude-opus-4-7")` and `(registry/find-model :openai "gpt-5.5")` are non-nil.
- Any model-capability predicate gated on model generation (thinking-level budget, xhigh eligibility) covers the new models.
- Full unit suite remains green.

## Minimum concepts

- **Model entry**: map with `:id`, `:name`, `:provider`, `:api`, `:base-url`, `:supports-reasoning`, `:supports-images`, `:supports-text`, `:context-window`, `:max-tokens`, and cost fields. Follows the existing shape in `psi.ai.models`.
- **Capability predicate**: function that inspects a model map to determine support for a feature (e.g., xhigh thinking). Must cover new generations.

## Implementation approach

**Additive map entries** — add new key/value pairs to `anthropic-models` and `openai-models` in `psi.ai.models`. No structural change to the registry or merge logic.

Steps:
1. Add `:opus-4.7` to `anthropic-models` following the existing `:opus-4.6` shape.
2. Add `:gpt-5.5` and `:gpt-5.4-mini` to `openai-models` following the existing `:gpt-5.4` shape.
3. Check `providers/anthropic.clj` `thinking-level->budget` map — Opus 4.7 uses adaptive thinking; confirm whether the existing `:xhigh 32000` budget applies or needs a different value.
4. Check `providers/openai/reasoning.clj` `thinking-level->effort` map — GPT-5.5 supports xhigh; confirm the existing `"high"` mapping is correct.
5. Update `model_registry_test.clj` to assert the new models.
6. Run full unit suite.

## Architecture alignment

- Follow the existing `psi.ai.models` map structure exactly — no new keys, no schema changes.
- `all-models` is derived from `(merge anthropic-models openai-models)` with `annotate-model`; new entries flow through automatically.
- `model_registry.clj` and the user-models overlay system require no changes.
- Tests follow the existing `init-built-ins-only-test` pattern: `(registry/find-model :provider "model-id")`.

## Notes

- Opus 4.7 uses "adaptive thinking" rather than "extended thinking". In the current psi model, this maps to `supports-reasoning true` — no new field needed.
- The `:xhigh` budget for Opus 4.7 on Anthropic: adaptive thinking uses a dynamic budget rather than a fixed token budget. The existing `thinking-level->budget` map uses `:xhigh 32000` for Opus 4.6 — check whether Anthropic's API treats adaptive thinking differently at the protocol level, or whether the same budget parameter is simply passed and the model manages it adaptively. If no protocol difference, the existing map entry suffices.
- GPT-5.5 cache read cost: OpenAI's published pricing page does not list a separate cache-read price for 5.5 at time of writing. Use `0.0` as placeholder consistent with the existing gpt-5.4 entry, and note this in a comment for future update.
- Older model removal: deliberately deferred. If the user wants to remove 3.x Anthropic or gpt-4o/o1 entries, that should be a separate task.
