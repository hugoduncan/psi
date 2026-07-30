🎯 OAuth-backed provider runtime policy must be decided from evidence against the *runtime-equivalent* backend and account class — not the platform API surface.

For OpenAI OAuth (ChatGPT account), the platform Chat Completions endpoint (`api.openai.com/v1/chat/completions`) returned `429 insufficient_quota` for candidate models — a red herring that is NOT the runtime surface. The authoritative probe is the ChatGPT/Codex backend the runtime actually uses (`chatgpt.com/backend-api/codex/responses`). There, `gpt-5.5` was accepted (failing only because the one-off probe was non-streaming) while `gpt-5.6` was explicitly rejected: "model is not supported when using Codex with a ChatGPT account."

Rules:
- Explicit model-support rejection from the runtime-equivalent backend = hard negative evidence for that backend/id combo.
- A quota/billing error on a *different* surface proves nothing about routing policy.
- No silent fallback to another model/transport without equivalent structured probe evidence + explicit encoded policy + regression tests.
- Diagnostic probes should print structured status/body, not assert via `clojure.test`; keep them as `/tmp` diagnostics, pin the resolved policy in deterministic unit tests. (task 245)
