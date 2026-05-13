# Plan

## Approach

Three vertical slices executed in dependency order:

1. **Remove conversation injection** — delete the projection code in `prompt_request.clj` and the duplicate formatting code in `step_execution.clj`. Update `:transcript` raw output to assistant-message-only. Update affected tests. This is a clean subtraction.

2. **Add `:session-id` to session-step raw outputs** — one-line addition to `execute-session-step!` in `step_execution.clj`. Enables downstream invoke steps to reference the child session-id.

3. **Enrich turn-finished event** — thread logprobs and structured assistant-message from `terminal-result` through `prompt-finish-base-result` into the `session_turn_finished` payload. Small, surgical change in `turn/handlers.clj`.

4. **Logprobs extension + workflow update** — create the extension at `extensions/logprobs/` with `extensions.logprobs` namespace, event subscription, single-snapshot storage, and `logprobs/perplexity` operation (using `turn-execution-contract/assistant-message-text` for reply-text). Update the `local-logprobs` workflow to three steps (run → perplexity invoke → report) with explicit variable bindings.

## Decisions

- **Event enrichment over separate event**: use the existing `session_turn_finished` event rather than adding a new event type. The data belongs to the turn lifecycle; extensions that don't need logprobs ignore the key.

- **Carry data in payload**: the logprobs vector is carried directly in the event payload (not queried back via EQL). The extension is in-process and the vector is a reference — no serialization overhead.

- **Single-snapshot storage**: the extension stores only one most-recent logprob-bearing snapshot total. The snapshot carries `:session-id`; `logprobs/perplexity` returns data only when the requested session matches that snapshot. A history buffer or per-session cache is a future extension concern.

- **Perplexity formula**: `exp(-1/N * Σ logprob_i)` where `logprob_i` is the natural-log probability of each token. Standard definition — lower is more confident.

- **Remove `step_execution.clj` logprob formatting**: the duplicate formatting code in `step_execution.clj` is removed along with the `prompt_request.clj` copy. `:transcript` output becomes the assistant message only. Consistent with "no synthetic messages" — logprob analysis is the extension's job.

- **Session-id as session-step raw output**: add `:session-id` to `execute-session-step!` raw outputs. This is the simplest mechanism — the value is already available as `(:session-id execution-session)`. No new infrastructure needed.

- **`:assistant-message` is structured**: the block-array form from execution-result, not a flattened string. `:reply-text` is derived via `turn-execution-contract/assistant-message-text`.

- **`extensions.logprobs` namespace**: follows the majority convention (`extensions.*`), not the `psi.github.*` outlier.

## Risks

- **Workflow `local-logprobs` step output shape change**: the workflow currently uses a session step to parse projected text. The new shape uses an invoke step returning structured data. The workflow output contract changes but this workflow has no downstream consumers.

- **Extension load order**: `logprobs/perplexity` must be registered before any workflow invokes it. Extension init runs before workflow execution so this is fine by construction.
