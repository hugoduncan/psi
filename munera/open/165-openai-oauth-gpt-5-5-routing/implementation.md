2026-05-21

- Root cause: built-in `gpt-5.5` resolved to `:openai-completions` / `https://api.openai.com/v1`, while the active OpenAI auth mode was ChatGPT OAuth. That platform path returned `insufficient_quota`, even though the same account could use `gpt-5.5` through Codex.
- Added `psi.ai.model-registry/resolve-runtime-model` as the shared runtime-resolution seam. The visible model catalog remains canonical, but runtime resolution can shape transport by auth context.
- Added OpenAI OAuth policy: `openai/gpt-5.5` + stored OAuth credential resolves at runtime to `:openai-codex-responses` / `https://chatgpt.com/backend-api` while preserving visible id `gpt-5.5`.
- Added provider-auth helpers to detect stored OAuth credential type without conflating it with general API-key resolution.
- Updated prompt-request, app-runtime, RPC, TUI frontend action, and `/model` command paths to use auth-aware runtime resolution.
- Tests: `clojure -M:test --focus psi.provider-auth.core-test --focus psi.ai.model-registry-test --focus psi.agent-session.runtime-test --focus psi.agent-session.prompt-request-test` => 32 tests, 162 assertions, 0 failures.
- Live reload/eval verified `gpt-5.5` resolves to Codex transport under current OAuth session, then user successfully switched `/model openai gpt-5.5`.
