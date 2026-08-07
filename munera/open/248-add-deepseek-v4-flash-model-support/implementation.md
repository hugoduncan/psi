# Implementation notes — 248-add-deepseek-v4-flash-model-support

## Slice 1 — schema + tests (2026-08-07)

- Added `[:adaptive-thinking {:optional true} [:maybe boolean?]]` to `ModelDef` in `user_models.clj`. No other schema or code changes needed: `expand-model` already merges all `model-def` keys (except `:name`) into the expanded model map, so the field flows through verbatim.
- `structured-output/normalize-model` operates only on `:capabilities :structured-output` and is a no-op for `:adaptive-thinking` — confirmed by the parse test.
- Added 3 tests in `user_models_test.clj`: `:adaptive-thinking true` accepted and flows through; `:adaptive-thinking false` accepted and flows through; omitted field remains valid and absent/falsy.
- Added 2 tests in `anthropic_test.clj` using a literal custom-provider model map (`deepseek-custom-provider-model`) proving the Anthropic transport emits `thinking.type "adaptive"` + `output_config.effort` (no `budget_tokens`) when `:adaptive-thinking true`, and emits neither when thinking is off.
- All tests green: `psi.ai.user-models-test` (13 tests, 77 assertions), `psi.ai.providers.anthropic-test` (15 tests, 84 assertions).

## Slice 2 — docs + changelog (2026-08-07)

- Added "DeepSeek-compatible example" subsection to `doc/custom-providers.md` with the resolved `models.edn` snippet.
- Documented the new `:adaptive-thinking` field in the Anthropic-compatible example section.
- Added CHANGELOG `[Unreleased]` → `Added` entry.

## Verification (2026-08-07)

- `clj-kondo --lint components/ai/src` clean (0 errors, 0 warnings).
- Targeted test namespaces green.
- No existing built-in Anthropic model or custom-provider behaviour changed.

- Review (2026-08-07): added 5 follow-up steps to be addressed.
