# Make GPT-5.6 work with OpenAI OAuth

## Goal

Make selecting `gpt-5.6` work when the OpenAI provider is backed by stored OAuth credentials.

## Context

A one-off smoke test showed that the OpenAI OAuth credential is present and includes a ChatGPT account id. Direct platform Chat Completions requests to `https://api.openai.com/v1/chat/completions` return `429 insufficient_quota` for both `gpt-5.5` and `gpt-5.6`, which is not the relevant runtime surface for ChatGPT OAuth-backed execution.

Testing the ChatGPT/Codex OAuth backend at `https://chatgpt.com/backend-api/codex/responses` showed:

- `gpt-5.5` is accepted far enough to reject only the non-streaming one-off body with `{:detail "Stream must be set to true"}`.
- `gpt-5.6` is explicitly rejected with `{:detail "The 'gpt-5.6' model is not supported when using Codex with a ChatGPT account."}`.

The current runtime model override in `components/ai/src/psi/ai/model_registry.clj` includes:

```clojure
(def ^:private openai-oauth-codex-model-ids
  #{"gpt-5.5" "gpt-5.6"})
```

That causes OAuth-backed OpenAI `gpt-5.6` requests to use the ChatGPT/Codex backend with literal model id `gpt-5.6`, which the backend rejects.

## Problem

`gpt-5.6` is exposed in the built-in model catalog and is routed as OAuth/Codex-capable, but the observed ChatGPT OAuth backend does not support the literal `gpt-5.6` model id on the Codex endpoint.

## Constraints

- Preserve working `gpt-5.5` OAuth behaviour.
- Do not treat the platform `429 insufficient_quota` response as the root cause for OAuth-backed behaviour.
- Avoid assertion-based one-off smoke tests; diagnostic probes should print structured status and body instead of failing on `clojure.test` assertions.
- Keep catalog entries and runtime transport overrides coherent.
- Do not silently fall back to another model unless the fallback is explicitly specified by catalog/runtime policy and covered by tests.

## Acceptance criteria

- Selecting `gpt-5.6` with OpenAI OAuth credentials no longer routes to a backend/model-id combination known to return `The 'gpt-5.6' model is not supported when using Codex with a ChatGPT account.`
- The implementation defines the correct OAuth runtime policy for `gpt-5.6`: either a supported ChatGPT/Codex model id or a different supported transport.
- Tests cover runtime model resolution for OAuth-backed OpenAI `gpt-5.6` and ensure `gpt-5.5` remains on the working OAuth/Codex path.
- If the supported runtime id differs from user-visible `gpt-5.6`, tests prove the user-visible catalog key still resolves to a runtime model that sends the supported backend id.
- Documentation or changelog is updated if user-visible model selection behaviour changes.

## Initial diagnostic artifacts

One-off local probes were created under `/tmp` during diagnosis:

- `/tmp/psi-gpt56-oauth-smoke.clj` — direct platform Chat Completions probe.
- `/tmp/psi-gpt56-oauth-codex-smoke.clj` — ChatGPT/Codex OAuth backend probe.

These are not project artifacts and should be recreated or promoted only if useful for permanent regression coverage.
