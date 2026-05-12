# Plan

## Approach

Three vertical slices executed in dependency order:

1. **Remove conversation injection** — delete the projection code in `prompt_request.clj` and update affected tests. This is a clean subtraction with no new code.

2. **Enrich turn-finished event** — thread logprobs from `terminal-result` through `prompt-finish-base-result` into the `session_turn_finished` payload. Small, surgical change in `turn/handlers.clj`.

3. **Logprobs extension + workflow update** — create the extension with event subscription, per-session storage, and `logprobs/perplexity` operation. Update the `local-logprobs` workflow to use the invoke step.

## Decisions

- **Event enrichment over separate event**: use the existing `session_turn_finished` event rather than adding a new event type. The data belongs to the turn lifecycle; extensions that don't need logprobs ignore the key.

- **Carry data in payload**: the logprobs vector is carried directly in the event payload (not queried back via EQL). The extension is in-process and the vector is a reference — no serialization overhead.

- **Last-turn only storage**: the extension stores only the most recent turn's logprobs per session. A history buffer is a future extension concern.

- **Perplexity formula**: `exp(-1/N * Σ logprob_i)` where `logprob_i` is the natural-log probability of each token. Standard definition — lower is more confident.

## Risks

- **Workflow `local-logprobs` step output shape change**: the workflow currently uses a session step to parse projected text. The new shape uses an invoke step returning structured data. The workflow output contract changes but this workflow has no downstream consumers.

- **Extension load order**: `logprobs/perplexity` must be registered before any workflow invokes it. Extension init runs before workflow execution so this is fine by construction.
