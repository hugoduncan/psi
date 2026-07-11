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
