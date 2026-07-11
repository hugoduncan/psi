# Steps

- [x] Add `:gpt-5.6` catalog entry to `built-in/all-models` in
      `components/ai/src/psi/ai/models.clj` (mirrors gpt-5.5 shape/transport).
- [x] Add `:gpt-5.6` to `openai-chat-completions-native-model-keys`.
- [x] Generalize `openai-oauth-runtime-model` in
      `components/ai/src/psi/ai/model_registry.clj` to
      `openai-oauth-codex-model-ids` set `#{"gpt-5.5" "gpt-5.6"}`.
- [x] Add/extend tests in
      `components/ai/test/psi/ai/model_registry_test.clj`:
      catalog presence, OAuth routing, structured-output capability.
- [x] Run `bb test --focus psi.ai.model-registry-test` and
      `bb test --focus psi.ai.core-test` — pass.
- [x] `clj-kondo --lint components/ai/src` — clean.

## Implementation-review follow-ups

- [x] Fix bogus negative-control in `resolve-runtime-model-openai-oauth-routing-test`
      ("other openai models preserve catalog transport under oauth",
      `model_registry_test.clj` ~113–121): it uses `"gpt-5.4"`, whose *catalog*
      entry already has `:api :openai-codex-responses` /
      `:base-url "https://chatgpt.com/backend-api"` (models.clj 546–560). Because
      `gpt-5.4` ∉ `openai-oauth-codex-model-ids`, the override returns nil and
      `resolve-runtime-model` falls back to `find-model`, which returns codex
      transport anyway — so the assertion passes regardless of whether the OAuth
      override is applied and proves nothing about non-member behaviour. Use a
      genuine negative control whose catalog transport is `:openai-completions`
      (e.g. `gpt-5.4-mini` or `gpt-5`) and assert it stays `:openai-completions`
      under oauth ctx.
- [x] Add the missing symmetric "no oauth context" assertion for `gpt-5.6`
      (mirroring the existing `gpt-5.5` "remains chat-completions without oauth
      context" case, `model_registry_test.clj` ~80–84): assert
      `(resolve-runtime-model nil :openai "gpt-5.6")` yields `:openai-completions`
      / `https://api.openai.com/v1`. This closes the "selectable via the same path
      as gpt-5.5" AC and proves gpt-5.6 differs from an already-codex catalog entry.
