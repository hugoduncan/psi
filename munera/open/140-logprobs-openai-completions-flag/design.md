Goal: Add a per-session toggle that enables log-probability collection on the OpenAI
chat-completions endpoint (and compatible endpoints such as llama.cpp), accumulates the
data in session telemetry, stores it in the session journal, and projects it back into
the conversation so the LLM can see and reason about its own token-level uncertainty.

## Context

- The OpenAI chat-completions API accepts `"logprobs": true` and `"top_logprobs": N`
  (max 20) in the request body. The response includes per-token logprob data under
  `choices[0].logprobs.content` in each SSE delta chunk.
- llama.cpp's HTTP server accepts the same fields but returns `completion_probabilities`
  only in the **final non-streaming response body** (not in SSE delta chunks). It is an
  array of `{content, probs: [{prob, tok_str}]}` where `prob` is a plain probability
  (not a log-probability). Psi does NOT issue a separate non-streaming request for
  llama.cpp; logprob data is extracted from the final SSE chunk only.
- Psi builds the completions request in
  `psi.ai.providers.openai.chat-completions/build-request`; provider-shaping options
  flow through `StreamOptions` (open schema, `{:closed false}`) and the per-session
  `session->request-options` projection. `:logprobs-enabled` and `:top-logprobs` pass
  through as opaque extra keys — no explicit `StreamOptions` schema entries needed.
- `journal->provider-messages` projects `:message` journal entries into the
  provider-visible conversation. Logprob context is projected by adding a `:logprobs`
  entry kind that `journal->provider-messages` converts to a synthetic user message.
- Session telemetry already carries `:provider-requests` and `:provider-replies`;
  per-turn logprob accumulation fits naturally alongside these.

## Control

`:logprobs-enabled` is a **transient session-state boolean** — it lives on the session
data map, is togglable within a session, and is NOT journalled as a session-entry (no
persistence across session restarts, no config file write). Schema:
`[:logprobs-enabled {:optional true} :boolean]` — absent = disabled; `initial-session`
does not set it explicitly.

`:top-logprobs` is a **transient session-state integer** (1–20). Default: **3**. Schema:
`[:top-logprobs {:optional true} [:int {:min 1 :max 20}]]` — absent = 3 when enabled.

Toggle path:

```
/logprobs on|off|N
  → slash-command handler in commands.clj
  → session-settings/set-logprobs-in! (session_settings.clj)
  → dispatch/dispatch! :session/set-logprobs
  → session_mutations handler:
      {:root-state-update (assoc % :logprobs-enabled enabled?
                                   :top-logprobs top-n)}
      (no journal-append effect, no persist effect)
  → session->request-options propagates :logprobs-enabled and :top-logprobs
```

`set-logprobs-in!` in `session_settings.clj` matches the `set-thinking-level-in!`
pattern: `(defn set-logprobs-in! [ctx session-id enabled? top-n] ...)`. `commands.clj`
calls `session-settings/set-logprobs-in!` directly (not `dispatch/dispatch!`).

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

## SSE extraction pipeline

A new private fn `extract-logprob-delta` is added to `chat_completions.clj`:
- Called from `emit-chat-chunk!` for per-chunk OpenAI logprobs:
  `choices[0].logprobs.content` (present when `"logprobs": true` in request).
- Called from `finish-chat-chunk!` for the final-chunk llama.cpp field:
  `completion_probabilities` (present only on the final object with usage/finish_reason).

When non-nil, emits `{:type :logprob-delta :tokens [...normalized...]}` via `consume-fn`.

**Event routing** (`core.clj` `make-provider-event-consumer`): `:logprob-delta` is added
to the `case` dispatch calling `(call-action! :on-logprob-delta {:tokens (:tokens event)})`.

**Accumulation** (`accumulator.clj` `make-turn-actions`): `:on-logprob-delta` handler
calls `(swap! td update :logprob-buffer (fnil conj []) (:tokens data))`, appending the
normalized token vector for each event into `:logprob-buffer` on `turn-data`. On
`:on-done`, `handle-done!` flattens the buffer with `(into [] cat (:logprob-buffer @td))`
and stores it as `:logprobs` on `turn-data` before delivering `done-p`.

**Extraction to execution-result** (`core.clj` `execute-live-turn!`): after
`await-assistant-message!` returns, reads `(get @(:turn-data turn-ctx) :logprobs)` and
includes it in the return map as `:logprobs`. `execute-prepared-request!` destructures
`:logprobs` from the `execute-live-turn!` return and includes it as
`:execution-result/logprobs` in the result map (nil when logprobs were not collected).

## Command registration

`/logprobs` is added to `prefixed-command-prefixes` in `commands.clj` (alongside
`/model`, `/thinking`). `builtin-slash-commands` in `tui/app/shared.clj` gains
`"/logprobs"` for TUI autocomplete. Help text:
`"  /logprobs [on|off|N] — toggle logprob collection or set top-N (1–20)\n"`.

## Surfacing

### 1 — Per-turn telemetry accumulation

During a turn, `:logprob-delta` events emitted by `consume-fn` are accumulated into a
transient buffer in the turn-runtime accumulator. On `:done`, the buffer is finalized
into `:execution-result/logprobs` on the execution result. `build-record-response`
reads this key and writes the normalized token vector to the session-data slot
`:last-turn-logprobs` (replaces previous value; written via `session-update`, same path
as `:last-execution-result-summary`). Queryable via a new EQL resolver
`:psi.agent-session/last-turn-logprobs`.

### 2 — Session journal entry + LLM projection

On turn completion, when logprobs are enabled and `(:execution-result/logprobs
execution-result)` is non-empty, a `:logprobs` session-entry is appended to the journal
immediately after the `:message` entry for the assistant turn. The append is added to
the `:effects` vector in `build-record-response` alongside `append-message-effect`,
using `turn-id` from the same `build-recording-decision` binding:

```clojure
{:kind :logprobs
 :data {:turn-id "..."
        :tokens  [ ... normalized token vector ... ]}}
```

`session-entry-kind-schema` gains `:logprobs`.

`journal->provider-messages` in `prompt_request.clj` is extended: in addition to
`:message` entries, it converts `:logprobs` entries to **synthetic user messages**
injected immediately after the corresponding assistant message. Orphaned `:logprobs`
entries (whose paired `:message` was compacted away) are silently dropped — the
compaction summary already captures that context. `:logprobs` entries are not
message-like for compaction purposes: `message-like-entry?` and `valid-cut-point?` in
`compaction.clj` require no change. Entries before the compaction cut-point are
excluded; entries after are preserved and projected normally.

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

The threshold 0.90 is a **fixed display constant** named `logprob-uncertain-threshold`
in the projection namespace. Rationale: tokens above 0.90 are near-certain and add
noise; tokens below represent meaningful uncertainty. Configurable threshold is deferred
to a follow-on task.

## Acceptance

- `/logprobs on` / `/logprobs off` toggles `:logprobs-enabled` on the session.
- `/logprobs N` (1 ≤ N ≤ 20) sets `:top-logprobs` to N and enables.
- `/logprobs` with no argument reports current state.
- Default top-N is 3 when enabled without an explicit N.
- When enabled, `build-request` adds `"logprobs": true` and `"top_logprobs": N` to the
  request body.
- When disabled (default), no logprob fields appear in the request body.
- On turn completion with logprobs enabled, `:last-turn-logprobs` in session-data
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
