Goal: Add a per-session toggle that enables log-probability collection on the OpenAI
chat-completions endpoint (and compatible endpoints such as llama.cpp), surfacing the
collected data in both session telemetry and the session journal.

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
- Session telemetry already carries `:provider-requests` and `:provider-replies`;
  per-turn logprob accumulation fits naturally alongside these.
- `session-entry-kind-schema` enumerates journalled entry kinds; a new `:logprobs` kind
  will carry the per-turn normalized token data.

## Control

`:logprobs-enabled` is a **transient session-state boolean** — it lives on the
session data map, is togglable within a session, and is NOT journalled as a
session-entry (no persistence across session restarts, no config file write).

Toggle path:

```
/logprobs [on|off]
  → slash-command handler in commands.clj
  → :session/set-logprobs-enabled dispatch event
  → session_mutations handler: {:root-state-update (assoc % :logprobs-enabled enabled?)}
                                (no journal-append effect, no persist effect)
  → session->request-options propagates :logprobs-enabled (and :top-logprobs when set)
```

`:top-logprobs` (optional int 1–20, default absent) is also a transient session field,
set via `/logprobs N` where N is 1–20.

Both fields default to absent in `initial-session`; absent `:logprobs-enabled` is
treated as disabled.

## Surfacing

### 1 — Per-turn telemetry accumulation

During a turn, logprob data arriving in stream chunks is accumulated into a transient
buffer and, on turn completion, written to the session telemetry slot
`:last-turn-logprobs`. This replaces the previous value each turn. Queryable via a new
EQL resolver `:psi.agent-session/last-turn-logprobs`.

Shape (normalized across providers):

```clojure
[{:token      "Hello"
  :logprob    -0.500          ; natural log; nil if unavailable
  :top        [{:token "Hello" :logprob -0.500}
               {:token "Hi"    :logprob -1.200}]}
 ...]
```

Normalization:
- **OpenAI**: `choices[0].logprobs.content[i]` → `{:token .token :logprob .logprob :top .top_logprobs}`
- **llama.cpp**: `completion_probabilities[i]` → `{:token .content :logprob (Math/log prob) :top (map #(hash-map :token (:tok_str %) :logprob (Math/log (:prob %))) probs)}`

### 2 — Session journal entry

On turn completion, when logprobs are enabled, a `:logprobs` session-entry is appended
to the session journal immediately after the `:message` entry for the assistant turn.
Entry data:

```clojure
{:turn-id   "..."
 :tokens    [ ... normalized token vector (same shape as telemetry) ... ]}
```

`session-entry-kind-schema` gains `:logprobs`.

## Acceptance

- `/logprobs on` / `/logprobs off` toggles `:logprobs-enabled` on the session.
- `/logprobs N` (1 ≤ N ≤ 20) sets `:top-logprobs` and implicitly enables.
- `/logprobs` with no argument reports current state.
- When enabled, `build-request` adds `"logprobs": true` (and `"top_logprobs": N` when
  set) to the request body.
- When disabled (default), no logprob fields appear in the request body.
- On turn completion with logprobs enabled, `:last-turn-logprobs` in session telemetry
  contains the normalized token vector for that turn.
- `:psi.agent-session/last-turn-logprobs` EQL resolver returns the current value (nil
  when disabled or before any logprob turn).
- A `:logprobs` session-entry is appended to the journal on each logprob-enabled turn.
- Logprob collection is absent from the default session; no opt-out required.
- No changes to Anthropic or codex-responses providers.
- Behaviour is covered by unit tests: request building (ai component), options
  projection (agent-session), and telemetry accumulation.
