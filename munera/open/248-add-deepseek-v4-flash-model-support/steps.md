# Steps

## Slice 1 — schema + tests

- [x] Add `[:adaptive-thinking {:optional true} [:maybe boolean?]]` to
      `ModelDef` in `components/ai/src/psi/ai/user_models.clj`.
- [x] `user_models_test.clj`: test that a model def with
      `:adaptive-thinking true` parses without error and the expanded model
      map has `:adaptive-thinking true`.
- [x] `user_models_test.clj`: test that omitting `:adaptive-thinking`
      remains valid (no error) and the expanded model map has it
      absent/falsy (unchanged current behaviour).
- [x] `anthropic_test.clj`: add a request-shaping test using a raw
      DeepSeek-shaped custom-provider model map (`:provider :deepseek`,
      `:api :anthropic-messages`, `:adaptive-thinking true`,
      `:supports-reasoning true`, plus the other resolved fields) that
      asserts `output_config.effort` and `thinking.type "adaptive"` appear
      (no `budget_tokens`), mirroring `build-request-adaptive-thinking-test`
      but proving it works off a non-catalog model map.
- [x] Run `bb clojure:test:scry --namespace psi.ai.user-models-test` and
      `bb clojure:test:scry --namespace psi.ai.providers.anthropic-test`;
      confirm green.

## Slice 2 — docs + changelog

- [x] `doc/custom-providers.md`: add a "DeepSeek-compatible example"
      subsection (after the existing "Anthropic-compatible example"
      section) with the resolved `models.edn` snippet
      (`https://api.deepseek.com/anthropic`, `:anthropic-messages`,
      `deepseek-v4-flash`, `env:DEEPSEEK_API_KEY`, `:adaptive-thinking
      true`, resolved pricing/context-window/max-tokens).
- [x] `doc/custom-providers.md`: document the new `:adaptive-thinking`
      field — what it does, that it only applies to `:api
      :anthropic-messages` custom providers, and when to set it (provider
      supports Anthropic's adaptive `output_config.effort` request shape).
- [x] CHANGELOG `[Unreleased]` → `Added`: entry announcing the DeepSeek
      custom-provider example and the new `:adaptive-thinking` custom-model
      field.

## Verification

- [x] `bb clojure:test:scry --namespace psi.ai.user-models-test` green
      (13 tests, 77 assertions).
- [x] `bb clojure:test:scry --namespace psi.ai.providers.anthropic-test` green
      (15 tests, 84 assertions).
- [x] `clj-kondo --lint components/ai/src` clean (0 errors, 0 warnings).
- [x] Re-read `doc/custom-providers.md` end to end — existing examples
      unaffected, DeepSeek subsection follows established pattern.
- [x] Committed.
