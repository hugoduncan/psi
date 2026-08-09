(ns psi.ai.providers.anthropic
  "Anthropic Messages transport: streaming (stream-anthropic) and
   non-streaming (execute-anthropic) execution for the :anthropic-messages
   provider, plus the SSE event normalization that drives the turn
   accumulator (open-block balancing, :start emission, usage accumulation,
   error surfacing).

   Request construction (build-request, headers/body shaping) lives in
   psi.ai.providers.anthropic.request — split out (review 56, 2026-08-09)
   to stay under the 800-line file-length gate; this namespace re-exports
   build-request and transform-messages so callers/tests keep the same
   public vars."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clj-http.client :as http]
            [cheshire.core :as json]
            [psi.ai.models :as models]
            [psi.ai.proxy :as proxy]
            [psi.ai.providers.anthropic.capture :as capture]
            [psi.ai.providers.anthropic.error :as anthropic-error]
            [psi.ai.providers.anthropic.request :as anthropic-request]
            [psi.ai.providers.anthropic.request-support :as anthropic-request-support]
            [psi.ai.providers.anthropic.stream-events :as stream-events]
            [psi.ai.providers.anthropic.structured-output :as anthropic-structured-output]
            [psi.ai.providers.anthropic.usage :as usage]
            [psi.ai.structured-output :as structured-output]))

;; Re-exports from the request-construction namespace (split out review 56
;; for the 800-line file-length gate; callers/tests reference these vars on
;; this namespace).
(def build-request anthropic-request/build-request)
(def transform-messages anthropic-request/transform-messages)

(defn parse-sse-line
  [line]
  (when (str/starts-with? (or line "") "data: ")
    (let [data (subs line 6)]
      (when (not= data "[DONE]")
        (try
          (json/parse-string data true)
          (catch Exception _
            nil))))))

(defn- balance-open-blocks!
  "Close any content blocks that were started but never stopped before a
   terminal event, so the turn accumulator never finalizes with an OPEN
   block index (the no-phantom-or-unbalanced-block invariant reviews
   43/48/50/55 asserted — review 55 scoped it to the :done/EOF paths, and
   review 56 extends it to every remaining terminal path).

   The matching end event (:toolcall-end/:thinking-end/:text-end) is shaped
   from the tracked block type via the same content-block-stop-event helper
   the in-band stop branch uses (consume-event! nil-guards a skipped type —
   no tracked block is ever redacted_thinking, but defensive). Indices are
   sorted so the balancing is deterministic. A no-op when no blocks are
   open (e.g. the HTTP-error paths, which fire before any SSE line has been
   consumed)."
  [consume-fn open-blocks]
  (doseq [idx (sort (keys @open-blocks))]
    (stream-events/consume-event! consume-fn
                                  (stream-events/content-block-stop-event (get @open-blocks idx)
                                                                          idx)))
  (reset! open-blocks {}))

(defn- handle-400-response!
  [model options url request response consume-fn consume-stream-response!]
  (capture/handle-400-response!
   {:prompt-caching-beta anthropic-request/prompt-caching-beta
    :interleaved-thinking-beta anthropic-request/interleaved-thinking-beta
    ;; Review 55: oauth-auth-request? must close over THIS namespace —
    ;; build-request attaches ::oauth? as :psi.ai.providers.anthropic/oauth?,
    ;; and the capture ns's own ::oauth? would resolve to a different
    ;; keyword (a namespaced-keyword drift that stripped ALL betas on the
    ;; OAuth 400-retry — caught by the full suite).
    :oauth-auth-request? (fn [req] (boolean (::oauth? req)))}
   model options url request response consume-fn consume-stream-response!))
(defn stream-anthropic
  "Stream response from Anthropic API."
  [conversation model options consume-fn]
  (let [url                (str (:base-url model) "/v1/messages")
        structured-request (structured-output/structured-output-request options)
        strategy           (structured-output/select-strategy model structured-request)
        request            (anthropic-request/build-request conversation model options)
        request-body       (anthropic-request-support/parse-json-body-safe (:body request))
        structured-tool-name (anthropic-structured-output/structured-tool-name-from-request
                              strategy
                              request-body)
        block-types        (atom {})
        ;; Review 55: content-block indices that are OPEN — a start event was
        ;; consumed but no stop has arrived (a truncated/non-conforming
        ;; stream that EOFs mid-block). Mirrors codex's open-tool-indexes:
        ;; conj on content_block_start (only when the start event was
        ;; consumed), dissoc on content_block_stop, balanced with the
        ;; matching end event in emit-terminal-done! before the :done — so
        ;; the turn accumulator never finalizes with an OPEN block index
        ;; (no phantom-or-unbalanced-block invariant, reviews 43/48/50).
        ;; Map of idx -> block type (the type is needed to shape the end
        ;; event). Structured-output tool blocks and skipped
        ;; redacted_thinking blocks are never tracked (their start events
        ;; are never consumed, so an end event would be unbalanced).
        open-blocks        (atom {})
        structured-buffers (atom {})
        prompted-json-buffer (atom "")
        json-schema-output-buffer (atom "")
        structured-result-emitted? (atom false)
        usage-acc   (atom {:input-tokens       0
                           :output-tokens      0
                           :cache-read-tokens  0
                           :cache-write-tokens 0})
        done?       (atom false)
        ;; Review 50: started? tracks whether :start was emitted (message_start
        ;; received). stream-anthropic previously had no started? tracking —
        ;; :start was emitted only inside the message_start case branch, so
        ;; emit-terminal-done! (message_stop / EOF flush) and the "error"
        ;; branch emitted :done/:error with no preceding :start when the
        ;; stream never received message_start — the only three-transport
        ;; asymmetry left in the review-48 EOF-level flush (both sibling
        ;; transports emit :start first when not started:
        ;; emit-chat-completion-finish!'s stream-started? compare-and-set and
        ;; the codex EOF flush's emit-codex-start!).
        started?    (atom false)]
    (try
      (capture/capture-request! model options url request)
      (when strategy
        (consume-fn {:type :structured-output-strategy
                     :structured-output strategy}))
      (letfn [(emit-terminal-done! []
                ;; The terminal :done shared by the message_stop branch and
                ;; the review-48 EOF-level flush (below): structured-output
                ;; results for the completed buffers, then the :done with the
                ;; review-47 usage-with-cost shape. Review 49: done? is reset
                ;; FIRST — before the structured-output emissions and the
                ;; :done consume — mirroring the message_delta-with-stop_reason
                ;; branch and every OpenAI-transport terminal emitter
                ;; (emit-chat-completion-finish!/emit-chat-error!/
                ;; emit-codex-done!/emit-codex-error!): a downstream exception
                ;; during the terminal processing (a structured-output
                ;; emission or the :done consume-fn, e.g. a statechart
                ;; dispatch failure inside make-provider-event-consumer's
                ;; :done → :turn/done send) must NOT propagate to the outer
                ;; catch with done? still false and emit a SECOND :error
                ;; terminal — the double-terminal class reviews 43/44/46
                ;; eliminated on every other terminal path
                ;; (OnceDoneNoFurtherEvent).
                (reset! done? true)
                ;; Review 50: emit :start first (when the stream never
                ;; received message_start) — mirroring
                ;; emit-chat-completion-finish!'s ordering (done? reset, then
                ;; :start, then the terminal).
                (capture/emit-start! consume-fn started?)
                ;; Review 55: close any content blocks that were started but
                ;; never stopped (a truncated/non-conforming stream that
                ;; EOFs mid-block) so the accumulator receives no OPEN block
                ;; index at :done — the EOF path was the last
                ;; unbalanced-block class on this transport (codex balances
                ;; open tool indexes at its EOF flush; the message_stop /
                ;; message_delta-with-stop_reason in-band terminals only fire
                ;; after a well-formed stream has stopped every block). The
                ;; matching end event (:toolcall-end/:thinking-end/:text-end)
                ;; is shaped from the tracked block type via the same
                ;; content-block-stop-event helper the in-band stop branch
                ;; uses (consume-event! nil-guards a skipped type — no
                ;; tracked block is ever redacted_thinking, but defensive).
                ;; Review 56: extracted to the shared balance-open-blocks!
                ;; helper so every remaining terminal path (the mid-stream
                ;; "error" branch, the message_delta-with-stop_reason
                ;; terminal, and the catch block) balances open blocks the
                ;; same way — review 55 covered only the :done/EOF paths.
                (balance-open-blocks! consume-fn open-blocks)
                (anthropic-structured-output/maybe-emit-json-schema-output-result!
                 consume-fn
                 structured-result-emitted?
                 strategy
                 @json-schema-output-buffer)
                (anthropic-structured-output/maybe-emit-prompted-json-result!
                 consume-fn
                 structured-result-emitted?
                 strategy
                 @prompted-json-buffer)
                (consume-fn {:type   :done
                             :reason :stop
                             :usage  (usage/usage-with-cost model usage-acc)}))
              (consume-stream-response! [response]
                (with-open [reader (io/reader (:body response))]
                  (doseq [line (line-seq reader)]
                    (when-let [event-data (parse-sse-line line)]
                      (capture/capture-response! model options url event-data)
                      ;; Review 46: short-circuit the entire SSE dispatch once
                      ;; the stream has terminated (done?) — NOT just the
                      ;; terminal emissions. A post-error trailing event (a
                      ;; content_block_stop / content_block_delta /
                      ;; content_block_start, a trailing message_delta, a
                      ;; message_stop) must be a full no-op: previously only
                      ;; the terminal branches (:done/:error) were guarded, so
                      ;; e.g. error → trailing content_block_stop still
                      ;; emitted :text-end and could fire
                      ;; maybe-emit-structured-result!, mutating turn-data
                      ;; after handle-error! had finalized the result.
                      (when-not @done?
                        (case (:type event-data)
                          "message_start"
                          (do
                            (usage/update-start-usage! usage-acc (get-in event-data [:message :usage]))
                            (capture/emit-start! consume-fn started?))

                          "content_block_start"
                          (let [idx   (:index event-data)
                                block (:content_block event-data)]
                            ;; Review 54: emit :start once before the first
                            ;; content-block event when the stream never
                            ;; received message_start (a malformed/
                            ;; non-conforming stream whose first event is a
                            ;; content_block_*) — mirroring the openai/codex
                            ;; siblings' emit-started-event! /
                            ;; emit-codex-started-event! (both emit :start
                            ;; before the first content event). The started?
                            ;; compare-and-set (shared
                            ;; request-support/emit-start!) makes this a
                            ;; no-op when message_start already fired.
                            (capture/emit-start! consume-fn started?)
                            (swap! block-types assoc idx {:type (:type block)
                                                          :name (:name block)})
                            (when-not (anthropic-structured-output/structured-tool-block?
                                       structured-tool-name
                                       {:type (:type block) :name (:name block)})
                              (let [start-event (stream-events/content-block-start-event idx block)]
                                ;; consume-event! guards nil — review 48:
                                ;; content-block-start-event returns nil for
                                ;; skipped "redacted_thinking" blocks.
                                (stream-events/consume-event! consume-fn start-event)
                                ;; Review 55: track the block as OPEN
                                ;; (started, not yet stopped) so the terminal
                                ;; can balance it at EOF. Only blocks whose
                                ;; start event was CONSUMED are tracked —
                                ;; structured-output tool blocks and skipped
                                ;; redacted_thinking blocks never emitted a
                                ;; start and must never get an end (an
                                ;; unbalanced end would be the same
                                ;; phantom-block harm the tracking exists to
                                ;; prevent).
                                (when start-event
                                  (swap! open-blocks assoc idx (:type block))))))

                          "content_block_delta"
                          (let [idx (:index event-data)
                                block-info (get @block-types idx)
                                delta (:delta event-data)]
                            ;; Review 54: :start before the first content
                            ;; event (idempotent, see content_block_start).
                            (capture/emit-start! consume-fn started?)
                            ;; Review 54: an UNKNOWN index (no prior
                            ;; content_block_start — a stream that omits
                            ;; start events, reuses indices, or reorders
                            ;; deltas/stops ahead of starts) must not emit
                            ;; unbalanced :text-delta/:text-end for a block
                            ;; that never had a :text-start — mirroring the
                            ;; codex sibling's skip of an unresolved index
                            ;; (response.function_call_arguments.delta
                            ;; guards on (number? idx)) and the review-48
                            ;; redacted_thinking skip. The structured-output
                            ;; path is unreachable for a nil block-info
                            ;; (structured-tool-block? is false for nil), so
                            ;; the whole branch is a no-op for unknown
                            ;; indices — consume-event! already nil-guards.
                            (when block-info
                              (if (anthropic-structured-output/structured-tool-block?
                                   structured-tool-name
                                   block-info)
                                (when-let [json-delta (:partial_json delta)]
                                  (swap! structured-buffers update idx str json-delta))
                                (do
                                  (when (and (= "text" (:type block-info))
                                             (seq (:text delta)))
                                    (cond
                                      (= :prompted-json (:strategy strategy))
                                      (swap! prompted-json-buffer str (:text delta))

                                      (anthropic-structured-output/json-schema-output-mechanism? strategy)
                                      (swap! json-schema-output-buffer str (:text delta))))
                                  (stream-events/consume-event! consume-fn
                                                                (stream-events/content-block-delta-event (:type block-info)
                                                                                                         idx
                                                                                                         delta))))))

                          "content_block_stop"
                          (let [idx (:index event-data)
                                block-info (get @block-types idx)]
                            ;; Review 54: :start before the first content
                            ;; event (idempotent, see content_block_start).
                            (capture/emit-start! consume-fn started?)
                            ;; Review 54: unknown index (no prior
                            ;; content_block_start) — skip, no unbalanced
                            ;; :text-end/:thinking-end for a block whose
                            ;; start was never received (see
                            ;; content_block_delta).
                            (when block-info
                              (if (anthropic-structured-output/structured-tool-block?
                                   structured-tool-name
                                   block-info)
                                (anthropic-structured-output/maybe-emit-structured-result!
                                 consume-fn
                                 strategy
                                 (get @structured-buffers idx))
                                (do
                                  ;; consume-event! guards nil — review 48:
                                  ;; content-block-stop-event returns nil for
                                  ;; skipped "redacted_thinking" blocks.
                                  (stream-events/consume-event! consume-fn
                                                                (stream-events/content-block-stop-event (:type block-info)
                                                                                                        idx))
                                  ;; Review 55: the block is no longer open
                                  ;; (its stop was received) — dissoc is a
                                  ;; no-op for indices never tracked
                                  ;; (redacted_thinking / structured-output
                                  ;; tool blocks never entered open-blocks).
                                  (swap! open-blocks dissoc idx)))))

                          "error"
                          ;; Anthropic's documented mid-stream SSE error shape
                          ;; ({"type":"error","error":{...}} — e.g.
                          ;; overloaded_error / rate-limit during a stream).
                          ;; Review 43: the default case previously consumed
                          ;; these as no-ops, so a mid-stream provider error
                          ;; hung the turn until llm-stream-idle-timeout-ms
                          ;; with a misleading timeout. Surface the event's
                          ;; error body through anthropic-error and terminate
                          ;; the stream; the outer done? guard (review 46)
                          ;; makes every subsequent event a no-op.
                          ;; Review 47: http-status extraction mirrors the
                          ;; sibling transports' emit-chat-error! /
                          ;; codex-error-http-status — :status /
                          ;; [:error :status] / [:error :http_status],
                          ;; numeric >= 400 only — so a status-carrying error
                          ;; event (e.g. {"error":{"status":529,...}}) keeps
                          ;; its numeric :http-status and downstream
                          ;; retry-error?/provider-error-kind classify a
                          ;; transient mid-stream 5xx/overload as retryable
                          ;; instead of :unknown (the review-23 class the
                          ;; openai transports already handle).
                          (let [status (some (fn [s] (and (number? s) (>= s 400) s))
                                             [(get-in event-data [:error :http_status])
                                              (get-in event-data [:error :status])
                                              (:http_status event-data)
                                              (:status event-data)])
                                err    (anthropic-error/error-from-response-data
                                        {:status           status
                                         :body-text        (json/generate-string event-data)
                                         :fallback-message "Anthropic stream error"})]
                            (reset! done? true)
                            ;; Review 50: emit :start first when the stream
                            ;; never received message_start (a malformed
                            ;; stream whose first event is the error) —
                            ;; mirroring the terminal emitters' ordering.
                            (capture/emit-start! consume-fn started?)
                            ;; Review 56: balance any content blocks that
                            ;; were started but never stopped before the
                            ;; :error — a stream that started a
                            ;; thinking/tool_use/text block and then
                            ;; receives the mid-stream error (e.g.
                            ;; overloaded_error) previously finalized the
                            ;; turn accumulator with an OPEN block index
                            ;; ([:start :thinking-start :thinking-delta
                            ;; :error] with the block :status :open in
                            ;; turn-data's :content-blocks) — review 55's
                            ;; balancing covered only the :done/EOF paths.
                            ;; The matching :toolcall-end/:thinking-end/
                            ;; :text-end precedes the :error so the
                            ;; accumulator finalizes balanced via the error
                            ;; path too.
                            (balance-open-blocks! consume-fn open-blocks)
                            (consume-fn err))

                          "message_delta"
                          ;; Review 44: the terminal :done emission is guarded
                          ;; on done? like the message_stop branch — a trailing
                          ;; message_delta carrying delta.stop_reason after a
                          ;; mid-stream SSE error must NOT emit a second
                          ;; terminal :done (verified: events were
                          ;; [:start :error :done] for error → message_delta
                          ;; stop_reason end_turn). Usage accumulation and the
                          ;; structured-output-result emissions stay inside the
                          ;; guard with the :done so a post-error message_delta
                          ;; is a full no-op. (Redundant with the outer
                          ;; review-46 guard but kept for branch-local clarity.)
                          (when-not @done?
                            (usage/update-output-usage! usage-acc (:usage event-data))
                            (when-let [reason (get-in event-data [:delta :stop_reason])]
                              (reset! done? true)
                              ;; Review 52: emit :start first when the stream
                              ;; never received message_start (a malformed
                              ;; stream whose FIRST event is a message_delta
                              ;; carrying stop_reason) — mirroring
                              ;; emit-terminal-done!'s ordering (done? reset,
                              ;; then :start, then the terminal). Review 50
                              ;; tested message_stop-first and empty-body but
                              ;; not message_delta-first, so this branch
                              ;; emitted [:done] while message_stop-first
                              ;; emits [:start :done] — the last
                              ;; :start-before-terminal gap on the anthropic
                              ;; transport.
                              (capture/emit-start! consume-fn started?)
                              ;; Review 56: balance open blocks before this
                              ;; terminal too — the message_delta-with-
                              ;; stop_reason branch emits its INLINE :done
                              ;; (separate from emit-terminal-done! since it
                              ;; carries the actual stop_reason, not the
                              ;; hardcoded :stop), so a non-conforming stream
                              ;; that sends message_delta-with-stop_reason
                              ;; while blocks are open previously finalized
                              ;; with OPEN block indices — the two :done
                              ;; branches of the same transport disagreed
                              ;; (message_stop balanced via
                              ;; emit-terminal-done!, message_delta not).
                              ;; The shared balance-open-blocks! helper makes
                              ;; both branches balance identically.
                              (balance-open-blocks! consume-fn open-blocks)
                              (anthropic-structured-output/maybe-emit-json-schema-output-result!
                               consume-fn
                               structured-result-emitted?
                               strategy
                               @json-schema-output-buffer)
                              (anthropic-structured-output/maybe-emit-prompted-json-result!
                               consume-fn
                               structured-result-emitted?
                               strategy
                               @prompted-json-buffer)
                              (consume-fn {:type   :done
                                           :reason (keyword reason)
                                           :usage  (usage/usage-with-cost model usage-acc)})))

                          "message_stop"
                          ;; The terminal :done. done? is set here too
                          ;; (review 46) so a malformed event AFTER a normal
                          ;; message_stop — or a cleanup exception — is also a
                          ;; full no-op: the guarantee is "no further event at
                          ;; all once done", not just "no second terminal".
                          ;; Review 47: the :done now carries the accumulated
                          ;; usage (usage-with-cost on usage-acc) like the
                          ;; message_delta-with-stop_reason terminal — a
                          ;; stream terminating via message_stop WITHOUT a
                          ;; preceding message_delta carrying stop_reason
                          ;; previously emitted a bare {:type :done :reason
                          ;; :stop}, so handle-done! ((map? usage) false)
                          ;; recorded ZERO usage/cost even though usage-acc
                          ;; held the input + cache tokens accumulated from
                          ;; message_start. Reachable on any
                          ;; Anthropic-compatible endpoint that omits
                          ;; message_delta — including the newly shipped
                          ;; DeepSeek provider whose streaming path is
                          ;; unverified. Review 48: emits through the shared
                          ;; emit-terminal-done! (also used by the EOF-level
                          ;; flush after the doseq).
                          (emit-terminal-done!)

                          nil)))))
                  ;; Review 48: EOF-level terminal flush — mirror the codex
                  ;; transport's (when-not @(:done? ...) ...) after its SSE
                  ;; doseq. A stream that EOFs without an in-band terminal
                  ;; event (message_stop, message_delta-with-stop_reason, or
                  ;; "error") previously emitted NO :done/:error and hung the
                  ;; turn until llm-stream-idle-timeout-ms — the review-43
                  ;; hang class via the EOF path rather than a mid-stream
                  ;; error, directly task-relevant since review 47 established
                  ;; DeepSeek's streaming path is UNVERIFIED (the review-1
                  ;; smoke test exercised only the non-streaming execute
                  ;; path), so a DeepSeek stream that ends without
                  ;; message_stop would hang 20 minutes instead of
                  ;; terminating. The flush emits the same terminal as
                  ;; message_stop (:stop, review-47 usage-with-cost shape);
                  ;; when an in-band terminal already fired, done? makes it a
                  ;; no-op.
                (when-not @done?
                  (emit-terminal-done!)
                    ;; Preserve the pre-review-48 nil return of the stream fn
                    ;; (the flush's when-not would otherwise return the last
                    ;; consumed event via emit-terminal-done!'s reset!).
                  nil))]
        (let [response (capture/stream-response url request)
              status   (:status response)]
          (cond
            (= 400 status)
            (handle-400-response! model options
                                  url
                                  request
                                  response
                                  consume-fn
                                  consume-stream-response!)

            (capture/error-status? status)
            ;; Review 56: no open-block balancing is needed on the initial
            ;; HTTP-error path (a 400/5xx response to the stream request) —
            ;; it fires BEFORE any SSE line has been consumed, so
            ;; @open-blocks is always empty; the mid-stream "error" branch,
            ;; the message_delta terminal and the catch block (the paths
            ;; that can fire after content blocks were opened) balance via
            ;; balance-open-blocks!.
            (capture/emit-error! model options url consume-fn
                                 (anthropic-error/response->error response request))

            :else
            (consume-stream-response! response))))
      (catch Exception e
        ;; Review 44: guard the error emission on done? (mirroring the codex
        ;; transport's emit-codex-error!) — if a mid-stream SSE error already
        ;; terminated the stream, a stream-read exception thrown afterwards
        ;; must not emit a SECOND :error.
        (when-not @done?
          ;; Review 53: emit :start first — the catch block is the last
          ;; :start-before-terminal gap on this transport. A stream-read
          ;; exception before any output event (e.g. a connection reset on
          ;; the first read) previously emitted [:error] with no preceding
          ;; :start, while every in-band terminal/error emitter now emits
          ;; [:start ...] (review-50 "error" branch, review-52
          ;; message_delta branch, emit-terminal-done!). The catch now emits
          ;; :start once (compare-and-set on started?) before the :error,
          ;; mirroring the in-band error branch's ordering — so a
          ;; first-read exception yields [:start :error] like every other
          ;; error path on this transport.
          (capture/emit-start! consume-fn started?)
          ;; Review 56: balance any open content blocks before the :error —
          ;; a stream-read exception mid-block (e.g. a connection reset
          ;; after a content_block_start was consumed) previously finalized
          ;; the accumulator with an OPEN block index (the catch routed the
          ;; error straight to consume-fn, with no balancing — review 55
          ;; covered only the :done/EOF paths). balance-open-blocks! is a
          ;; no-op when no blocks are open (a first-read exception before
          ;; any SSE line).
          (balance-open-blocks! consume-fn open-blocks)
          (let [err (anthropic-error/exception->error e)]
            (capture/capture-response! model options url err)
            (consume-fn err)))))))

(defn- execute-response
  [url request]
  (http/post url (merge request
                        (proxy/request-proxy-options url)
                        {:as :text :throw-exceptions false})))

(defn- non-streaming-content-blocks
  "Map the non-streaming response's :content blocks to the canonical
   assistant-message content shape, preserving wire order.

   Review 57: previously only \"text\" blocks survived (the old
   text-content-blocks) — a non-streaming response containing a tool_use
   block (Anthropic's tool-call shape, fully supported by DeepSeek per its
   compat table) silently dropped the tool call while :stop-reason
   :tool_use was preserved, so classify-assistant-message /
   extract-tool-calls recorded :turn.outcome/stop and the tool call NEVER
   executed (silent functional loss, reachable on the newly shipped
   DeepSeek provider via response-mode :non-streaming sessions with tools).
   The mapping now mirrors the streaming accumulator (tool-content-blocks /
   thinking-blocks-in-order) and the :openai-completions sibling
   (completion-message->content's tool-call-block): tool_use →
   :tool-call (id/name/arguments — :input JSON-encoded so downstream
   tool-args/parse-args, which json/parse-strings :arguments, parses it),
   thinking → :thinking (text/signature), text → :text. Wire order is
   preserved."
  [content]
  (->> content
       (keep (fn [block]
               (case (:type block)
                 "text"
                 {:type :text :text (or (:text block) "")}

                 "tool_use"
                 {:type      :tool-call
                  :id        (:id block)
                  :name      (:name block)
                  :arguments (cond
                               (nil? (:input block)) nil
                               (string? (:input block)) (:input block)
                               :else (json/generate-string (:input block)))}

                 "thinking"
                 (cond-> {:type :thinking :text (or (:thinking block) "")}
                   (:signature block) (assoc :signature (:signature block)))

                 nil)))
       vec))

(defn- response->assistant-message
  [model body strategy]
  (let [text  (apply str (keep (fn [block]
                                 (when (= "text" (:type block))
                                   (:text block)))
                               (:content body)))
        usage (when-let [usage (:usage body)]
                {:input-tokens (or (:input_tokens usage) 0)
                 :output-tokens (or (:output_tokens usage) 0)
                 :cache-read-tokens (or (:cache_read_input_tokens usage) 0)
                 :cache-write-tokens (or (:cache_creation_input_tokens usage) 0)})]
    (cond-> {:assistant-message (cond-> {:role "assistant"
                                         :content (non-streaming-content-blocks (:content body))
                                         :stop-reason (keyword (or (:stop_reason body) "stop"))
                                         :timestamp (java.time.Instant/now)}
                                  (map? usage) (assoc :usage (assoc usage
                                                                    :total-tokens (+ (:input-tokens usage)
                                                                                     (:output-tokens usage)
                                                                                     (:cache-read-tokens usage)
                                                                                     (:cache-write-tokens usage))
                                                                    :cost (models/calculate-cost model usage))))}
      (anthropic-structured-output/json-schema-output-mechanism? strategy)
      (assoc :structured-output
             (anthropic-structured-output/structured-output-result
              strategy
              :anthropic/json-schema-output
              text)))))

(defn execute-anthropic
  "Execute a non-streaming Anthropic Messages request."
  [conversation model options]
  (let [url                (str (:base-url model) "/v1/messages")
        structured-request (structured-output/structured-output-request options)
        strategy           (structured-output/select-strategy model structured-request)
        request            (anthropic-request/build-request conversation model options false)]
    (try
      (capture/capture-request! model options url request)
      (let [response (execute-response url request)]
        (if (capture/error-status? (:status response))
          (anthropic-error/response->error response request)
          (let [body (json/parse-string (:body response) true)]
            (capture/capture-response! model options url body)
            (response->assistant-message model body strategy))))
      (catch Exception e
        (anthropic-error/exception->error e)))))

(def provider
  {:name    :anthropic
   :stream  stream-anthropic
   :execute execute-anthropic})
