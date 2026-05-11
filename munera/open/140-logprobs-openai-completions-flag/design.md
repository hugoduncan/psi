Goal: Add a runtime behavioural flag that enables log-probability collection on the OpenAI chat-completions endpoint (and compatible endpoints such as llama.cpp HTTP server).

Context:
- The OpenAI chat-completions API supports `"logprobs": true` and `"top_logprobs": N` (max 20) in the request body.
- llama.cpp's HTTP server surfaces the same fields, returning a `completion_probabilities` array with per-token log-probability data (natural log; exponentiate via `Math.exp` to get probability).
- Psi builds the completions request body in `psi.ai.providers.openai.chat-completions/build-request`; all provider-shaping options flow through `StreamOptions` and the per-session `session->request-options` projection.
- `StreamOptions` is open (`{:closed false}`), so additional provider-specific keys already pass through without schema breakage.
- The session state carries `thinking-level` as a first-class behavioural field; logprobs should follow the same pattern — a named field on the session state, projected into `StreamOptions` by `session->request-options`, and consumed in `build-request`.

Required behaviour:
- A boolean session-level flag `:logprobs-enabled` controls whether logprob fields are included in openai-completions requests.
- When enabled, the request body includes `"logprobs": true`. Optionally, `:top-logprobs` (integer 1–20) controls the `"top_logprobs"` field; when absent and logprobs is enabled, `"top_logprobs"` is omitted (the endpoint returns only the chosen-token logprob).
- The flag is off by default.
- The flag is scoped to the session state model (`agent-session-schema`) and projected through `session->request-options` into the `StreamOptions` map.
- `build-request` reads `:logprobs-enabled` and `:top-logprobs` from options and conditionally adds the fields to the request body.
- The raw `logprobs` data returned in the response stream is surfaced as-is in a new `:logprobs-delta` stream event so consumers can receive it without schema loss.
- No cost or usage computation is added for logprob tokens (they are not separately billed).

Acceptance:
- `session->request-options` propagates `:logprobs-enabled` and `:top-logprobs` from session state into the options map when set.
- `build-request` adds `"logprobs": true` (and optionally `"top_logprobs": N`) to the body iff `:logprobs-enabled` is truthy.
- When disabled (default), no logprob fields appear in the request body.
- `agent-session-schema` includes `:logprobs-enabled` (optional boolean) and `:top-logprobs` (optional int 1–20).
- A `:logprobs-delta` stream event type is added to `StreamEventType` and emitted from the chunk processor when logprob data is present in the response.
- Behaviour is covered by unit tests in the `ai` component (request building) and `agent-session` component (options projection).

Constraints:
- No changes to Anthropic or codex-responses providers; this flag is OpenAI-completions-specific.
- `StreamOptions` remains open; no closed-schema breakage.
- `initial-session` default leaves `:logprobs-enabled` absent (falsy); no opt-out required.
- Keep the change localized: session model → options projection → request builder → stream event.
