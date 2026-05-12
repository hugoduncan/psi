# Logprobs Out-of-Band Extension

## Goal

Move logprob data out of the conversation context and into a dedicated logprobs extension that stores token probabilities out-of-band, provides analysis via deterministic operations, and removes the current lossy/verbose synthetic-user-message projection.

## Why

The current logprobs implementation (task 140) injects token probability data into the conversation as synthetic user messages. This is:

- **Verbose**: uncertain-token tables consume context window
- **Lossy**: only tokens below a 0.90 threshold are projected; full distribution is discarded
- **Semantically wrong**: the model sees logprob analysis framed as "user said this"
- **Not composable**: no structured API for analysis — workflows must ask a session to read and interpret the projected text

Moving logprobs out-of-band makes the data available for structured analysis without polluting the conversation.

## Scope

Three changes:

1. **Remove conversation injection**: delete the synthetic-user-message projection of `:logprobs` journal entries from `journal->provider-messages`
2. **Enrich the turn-finished event**: add `:logprobs` to the `session_turn_finished` extension event payload so extensions receive the data
3. **Create a logprobs extension** at `extensions/logprobs/` that subscribes to the event, stores per-session logprob data, and registers a `logprobs/perplexity` deterministic operation

## Desired behaviour

### Conversation injection removal

`:logprobs` journal entries remain in the persistence journal (they are the record) but are no longer projected into provider messages by `journal->provider-messages`. The `format-logprob-message`, `format-logprob-line`, and `logprob-uncertain-threshold` code in `prompt_request.clj` is removed.

### Turn-finished event enrichment

When logprobs are present on a completed turn, the `session_turn_finished` extension event payload includes them:

```clojure
{:session-id "..."
 :turn-id    "..."
 :logprobs   [{:token "hello" :logprob -0.023 :top_logprobs [...]} ...]}
```

When logprobs are absent (session not configured for logprob collection), the `:logprobs` key is absent from the payload. Extensions that don't care ignore it.

The logprobs data is carried directly in the event payload (not queried back). The extension is in-process; the vector is just a reference.

### Logprobs extension

A new extension at `extensions/logprobs/` that:

- Subscribes to `session_turn_finished` via `(:on api)`
- Stores per-session last-turn logprobs in an atom
- Registers one deterministic operation: `logprobs/perplexity`

#### `logprobs/perplexity` operation

Calculates the perplexity of the most recent reply for a given session: the exponentiated average negative log-likelihood across all tokens in the reply.

```
perplexity = exp( -1/N * Σ log_prob_i )
```

Input: `{:session-id "..."}`

Output:
```clojure
{:status :ok
 :data {:perplexity 4.23
        :token-count 157}}
```

When no logprobs are stored for the session:
```clojure
{:status :ok
 :data {:perplexity nil
        :token-count 0}}
```

### Workflow update

The existing `local-logprobs` workflow is updated to use the deterministic operation instead of asking a session to parse projected text:

1. `run` step: session step that runs the prompt with logprobs enabled (unchanged)
2. `perplexity` step: `:invoke` step calling `logprobs/perplexity` with the run step's session-id
3. `report` step: session step that reports the message text and the perplexity value

## Data flow

```
provider SSE chunks
→ turn-runtime accumulator (`:logprob-delta` → `:logprob-buffer`)
→ turn completion (flattened `:logprobs` on turn-data)
→ execute-live-turn! / execute-prepared-request! (`:logprobs` in result)
→ prompt-record-response-handler (`:logprobs` journal entry + `:last-turn-logprobs` session-data)
→ prompt-finish-handler (`:logprobs` on terminal-result carried into event payload)
→ session_turn_finished extension event (`:logprobs` in payload)
→ logprobs extension receives event, stores per-session
→ logprobs/perplexity deterministic op computes on stored data
```

## What stays in core

- Turn-runtime logprob accumulation (mechanism)
- `:logprobs` journal entry persistence (record)
- `:last-turn-logprobs` on session-data (lightweight resolver backing)
- `:psi.agent-session/last-turn-logprobs` EQL resolver
- `:logprobs-enabled` / `:top-logprobs` session-data keys
- Workflow session-config `:logprobs` / `:top-logprobs` controls (task 142)

## What is removed from core

- `format-logprob-message` function in `prompt_request.clj`
- `format-logprob-line` function in `prompt_request.clj`
- `logprob-uncertain-threshold` constant in `prompt_request.clj`
- The `:logprobs` kind branch in `journal->provider-messages` that produces synthetic user messages
- Any test coverage proving the synthetic-message projection

## What is new

- `:logprobs` key in `session_turn_finished` event payload (in `prompt-finish-base-result`)
- `extensions/logprobs/` extension directory
- `logprobs/perplexity` deterministic operation
- Updated `local-logprobs` workflow using the deterministic op

## Constraints

- The `:logprobs` journal entry kind remains valid and persisted — only its projection into provider messages is removed
- The logprobs extension stores only the last turn's logprobs per session (not a history buffer) — keep it minimal
- No new slash commands or user-facing controls
- The extension follows the same init/registration pattern as `psi/github`

## Acceptance

- `:logprobs` journal entries are no longer projected as synthetic user messages in provider conversations
- `session_turn_finished` event payload includes `:logprobs` when present on the terminal result
- `logprobs/perplexity` deterministic operation returns correct perplexity for a session with logprobs data
- `logprobs/perplexity` returns `{:perplexity nil :token-count 0}` when no data is available
- `local-logprobs` workflow uses the `logprobs/perplexity` invoke step and reports the numeric perplexity
- Focused tests cover: perplexity calculation, event subscription/storage, conversation projection removal
