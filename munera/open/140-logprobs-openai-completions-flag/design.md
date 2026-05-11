Goal: Add a per-session toggle that enables log-probability collection on the OpenAI
chat-completions endpoint (and compatible endpoints such as llama.cpp), accumulates the
data in session telemetry, stores it in the session journal, and projects it back into
the conversation so the LLM can see and reason about its own token-level uncertainty.

## Context

- The OpenAI chat-completions API accepts `"logprobs": true` and `"top_logprobs": N`
  (max 20) in the request body. The response includes per-token logprob data under
  `choices[0].logprobs.content`.
- llama.cpp's HTTP server accepts the same fields and returns
  `completion_probabilities` — an array of `{content, probs: [{prob, tok_str}]}` where
  `prob` is a plain probability (not a log-probability).
- Psi builds the completions request in
  `psi.ai.providers.openai.chat-completions/build-request`; provider-shaping options
  flow through `StreamOptions` (open schema) and the per-session
  `session->request-options` projection.
- `journal->provider-messages` projects `:message` journal entries into the
  provider-visible conversation. Logprob context is projected by adding a `:logprobs`
  entry kind that `journal->provider-messages` converts to a synthetic user message.
- Session telemetry already carries `:provider-requests` and `:provider-replies`;
  per-turn logprob accumulation fits naturally alongside these.

## Control

`:logprobs-enabled` is a **transient session-state boolean** — it lives on the session
data map, is togglable within a session, and is NOT journalled as a session-entry (no
persistence across session restarts, no config file write).

`:top-logprobs` is a **transient session-state integer** (1–20). Default: **3**.

Toggle path:

```
/logprobs on|off|N
  → slash-command handler in commands.clj
  → :session/set-logprobs dispatch event
  → session_mutations handler:
      {:root-state-update (assoc % :logprobs-enabled enabled?
                                   :top-logprobs top-n)}
      (no journal-append effect, no persist effect)
  → session->request-options propagates :logprobs-enabled and :top-logprobs
```

Command forms:
- `/logprobs` — report current state (enabled/disabled, top-N)
- `/logprobs on` — enable with current top-N (default 3 if unset)
- `/logprobs off` — disable
- `/logprobs N` (1 ≤ N ≤ 20) — set top-N and implicitly enable

Both fields default to absent in `initial-session`; absent `:logprobs-enabled` is
treated as disabled.

## Data normalization

Normalized shape (common across providers):

```clojure
[{:token   "Hello"
  :logprob -0.500          ; natural log (ln); nil if unavailable
  :top     [{:token "Hello" :logprob -0.500}
             {:token "Hi"   :logprob -1.200}
             {:token "Hey"  :logprob -2.500}]}
 ...]
```

Provider-specific mapping:
- **OpenAI**: `choices[0].logprobs.content[i]` →
  `{:token .token :logprob .logprob :top .top_logprobs}`
- **llama.cpp**: `completion_probabilities[i]` →
  `{:token .content :logprob (Math/log prob) :top (map #({:token (:tok_str %) :logprob (Math/log (:prob %))}) probs)}`

## Surfacing

### 1 — Per-turn telemetry accumulation

During a turn, logprob chunks are accumulated into a transient buffer and, on turn
completion, written to the session telemetry slot `:last-turn-logprobs` (replaces
previous value). Queryable via a new EQL resolver
`:psi.agent-session/last-turn-logprobs`.

### 2 — Session journal entry + LLM projection

On turn completion, when logprobs are enabled, a `:logprobs` session-entry is appended
to the journal immediately after the `:message` entry for the assistant turn:

```clojure
{:kind :logprobs
 :data {:turn-id "..."
        :tokens  [ ... normalized token vector ... ]}}
```

`session-entry-kind-schema` gains `:logprobs`.

`journal->provider-messages` converts `:logprobs` entries to **synthetic user
messages** injected into the provider-visible conversation immediately after the
corresponding assistant message. The LLM therefore sees its own per-token uncertainty
at every subsequent turn.

Synthetic message format (compact text block):

```
[logprob context — previous response]
Uncertain tokens (p < 0.90):
  "was" 0.72  |  "is" 0.19  "had" 0.07
  "important" 0.65  |  "crucial" 0.22  "significant" 0.10
All other tokens: p ≥ 0.90
```

If no tokens fall below the threshold (0.90), the message is still emitted but
abbreviated: `[logprob context — previous response]\nAll tokens p ≥ 0.90`.

Probabilities shown as plain values (`exp(logprob)`), not log-probabilities, for LLM
readability. The `:token` is formatted with surrounding quotes; whitespace-only tokens
use visible escape form (e.g. `" "`, `"\n"`).

## Acceptance

- `/logprobs on` / `/logprobs off` toggles `:logprobs-enabled` on the session.
- `/logprobs N` (1 ≤ N ≤ 20) sets `:top-logprobs` to N and enables.
- `/logprobs` with no argument reports current state.
- Default top-N is 3 when enabled without an explicit N.
- When enabled, `build-request` adds `"logprobs": true` and `"top_logprobs": N` to the
  request body.
- When disabled (default), no logprob fields appear in the request body.
- On turn completion with logprobs enabled, `:last-turn-logprobs` in session telemetry
  contains the normalized token vector for that turn.
- `:psi.agent-session/last-turn-logprobs` EQL resolver returns the current value (nil
  when disabled or before any logprob turn).
- A `:logprobs` session-entry is appended to the journal on each logprob-enabled turn.
- `journal->provider-messages` converts `:logprobs` entries to synthetic user messages;
  the LLM receives the formatted uncertainty context on every turn after a logprob turn.
- Behaviour is covered by unit tests: request building (ai component), options
  projection (agent-session), telemetry accumulation, and message projection
  (journal->provider-messages with :logprobs entries).
- No changes to Anthropic or codex-responses providers.
