(ns psi.ai.providers.anthropic-stream-termination-test
  "Review-48 stream follow-ups for the :anthropic-messages transport:
  the EOF-level terminal flush (a stream that EOFs without an in-band
  terminal event terminates with :done instead of hanging) and the
  redacted_thinking content-block typing fix (skipped, not mislabeled as
  text). Split out of anthropic_stream_test.clj to stay under the 800-line
  file-length gate."
  (:require
   [clojure.test :refer [deftest is testing]]
   [cheshire.core :as json]
   [psi.ai.providers.http-boundary :as http-boundary]
   [psi.ai.conversation :as conv]
   [psi.ai.models :as models]
   [psi.ai.providers.anthropic :as anthropic])
  (:import [java.io ByteArrayInputStream InputStream]))

(defn- sse-line [event-type data-map]
  (str "event: " event-type "\ndata: " (json/generate-string data-map) "\n\n"))

(defn- stream-body [s]
  (ByteArrayInputStream. (.getBytes s "UTF-8")))

(defn- throwing-stream-after [s]
  (let [bytes (.getBytes s "UTF-8")
        index (atom 0)]
    (proxy [InputStream] []
      (read
        ([]
         (if (< @index (alength bytes))
           (let [value (bit-and 0xff (aget bytes @index))]
             (swap! index inc)
             value)
           (throw (ex-info "simulated stream read failure" {:status 503}))))
        ([buffer offset length]
         (if (< @index (alength bytes))
           (let [remaining (- (alength bytes) @index)
                 count (min length remaining)]
             (System/arraycopy bytes @index buffer offset count)
             (swap! index + count)
             count)
           (throw (ex-info "simulated stream read failure" {:status 503}))))))))

(deftest stream-anthropic-eof-flush-emits-done-test
  (testing "a stream that EOFs without an in-band terminal event emits exactly one terminal :done"
    ;; Review 48: consume-stream-response! ended with NOTHING after the SSE
    ;; doseq, so a stream that EOFs without message_stop /
    ;; message_delta-with-stop_reason / "error" emitted no terminal event and
    ;; hung the turn until llm-stream-idle-timeout-ms — the review-43 hang
    ;; class via the EOF path rather than a mid-stream error. Directly
    ;; task-relevant: review 47 established DeepSeek's streaming path is
    ;; UNVERIFIED (the review-1 smoke test exercised only the non-streaming
    ;; execute path), so a DeepSeek stream ending without message_stop would
    ;; hang 20 minutes instead of terminating. The EOF flush now emits the
    ;; same terminal as message_stop (:stop, review-47 usage-with-cost
    ;; shape); when an in-band terminal already fired, done? makes it a
    ;; no-op (verified by every existing message_stop/message_delta/error
    ;; stream test still passing with exactly one terminal).
    (let [model  (models/get-model :sonnet-4.6)
          convo  (-> (conv/create "sys") (conv/add-user-message "hi"))
          events (atom [])
          sse    (str (sse-line "message_start"
                                {:type    "message_start"
                                 :message {:usage {:input_tokens           100
                                                   :cache_read_input_tokens 20}}})
                      (sse-line "content_block_start"
                                {:type "content_block_start" :index 0
                                 :content_block {:type "text"}})
                      (sse-line "content_block_delta"
                                {:type "content_block_delta" :index 0
                                 :delta {:type "text_delta" :text "Hi"}})
                      (sse-line "content_block_stop"
                                {:type "content_block_stop" :index 0})
                      ;; NOTE: NO message_stop, NO message_delta-with-
                      ;; stop_reason, NO "error" — the stream just EOFs.
                      )]
      (let [response-fn (fn [_]
                          {:body (stream-body sse)})
            http-client (http-boundary/nullable [response-fn response-fn])]
        (anthropic/stream-anthropic convo model {:http-boundary http-client
                                                 :api-key "test-key"}
                                    (fn [e] (swap! events conj e))))
      (let [dones (filterv #(= :done (:type %)) @events)
            done  (first dones)]
        (is (= 1 (count dones))
            "exactly one :done — the EOF flush terminates the stream")
        (is (= :stop (:reason done))
            "the EOF flush emits the same terminal reason as message_stop")
        (is (map? (:usage done))
            "the EOF-flush :done carries the accumulated usage (review-47 usage-with-cost shape)")
        (is (= 100 (get-in done [:usage :input-tokens]))
            "input tokens accumulated from message_start")
        (is (= 20 (get-in done [:usage :cache-read-tokens]))
            "cache-read tokens accumulated from message_start")
        (is (not-any? #(= :error (:type %)) @events)
            "no :error — the EOF flush is a clean terminal, not an error")))))

(deftest stream-anthropic-eof-flush-no-message-start-emits-start-then-done-test
  (testing "a stream that EOFs before message_start emits :start then the terminal :done"
    ;; Review 50: stream-anthropic's terminal emitters never emitted :start
    ;; when the stream never received message_start — the only three-transport
    ;; asymmetry left in the review-48 EOF-level flush. :start was emitted
    ;; only inside the message_start case branch and stream-anthropic had no
    ;; started? tracking, so emit-terminal-done! (message_stop + the EOF
    ;; flush) emitted :done with no preceding :start; the sibling transports
    ;; both emit :start first when not started (emit-chat-completion-finish!'s
    ;; stream-started? compare-and-set and the codex EOF flush's
    ;; emit-codex-start!). Reachable on any 200 whose body EOFs before
    ;; message_start (empty/truncated body, or a malformed stream starting
    ;; with message_stop): the anthropic path emitted [:done] while
    ;; openai/codex emit [:start :done]. Benign today (the consumer's :start
    ;; handler is a no-op and the turn statechart is already past :idle via
    ;; the turn-level :turn/start) but a genuine cross-transport
    ;; inconsistency. The transport now tracks started? and emits :start once
    ;; before the terminal."
    (let [model  (models/get-model :sonnet-4.6)
          convo  (-> (conv/create "sys") (conv/add-user-message "hi"))
          events (atom [])]
      (testing "empty body — EOF with no SSE events at all"
        (let [response-fn (fn [_]
                            {:body (stream-body "")})
              http-client (http-boundary/nullable [response-fn response-fn])]
          (anthropic/stream-anthropic convo model {:http-boundary http-client
                                                   :api-key "test-key"}
                                      (fn [e] (swap! events conj e))))
        (is (= [:start :done] (mapv :type @events))
            "empty body (EOF before message_start) emits :start then the EOF-flush :done")
        (is (= 1 (count (filterv #(= :done (:type %)) @events)))
            "exactly one terminal :done"))
      (testing "malformed stream starting with message_stop (no message_start)"
        (reset! events [])
        (let [sse (sse-line "message_stop" {:type "message_stop"})
              response-fn (fn [_]
                            {:body (stream-body sse)})
              http-client (http-boundary/nullable [response-fn response-fn])]
          (anthropic/stream-anthropic convo model {:http-boundary http-client
                                                   :api-key "test-key"}
                                      (fn [e] (swap! events conj e))))
        (is (= [:start :done] (mapv :type @events))
            "message_stop-without-message_start emits :start then the terminal :done")
        (is (= 1 (count (filterv #(= :done (:type %)) @events)))
            "exactly one terminal :done")))))

(deftest stream-anthropic-error-without-message-start-emits-start-then-error-test
  (testing "a mid-stream error with no message_start emits :start then :error"
    ;; Review 50: the "error" SSE branch emitted :error with no preceding
    ;; :start when the stream never received message_start (a malformed
    ;; stream whose first event is the error) — the same review-50 asymmetry
    ;; as the terminal :done, on the error path. The branch now emits :start
    ;; first (when not started), mirroring the terminal emitters and the
    ;; sibling transports' error paths.
    (let [model  (models/get-model :sonnet-4.6)
          convo  (-> (conv/create "sys") (conv/add-user-message "hi"))
          events (atom [])
          sse    (sse-line "error"
                           {:type "error"
                            :error {:type "overloaded_error"
                                    :message "Overloaded"
                                    :http_status 529}})]
      (let [response-fn (fn [_]
                          {:body (stream-body sse)})
            http-client (http-boundary/nullable [response-fn response-fn])]
        (anthropic/stream-anthropic convo model {:http-boundary http-client
                                                 :api-key "test-key"}
                                    (fn [e] (swap! events conj e))))
      (is (= [:start :error] (mapv :type @events))
          "error-without-message_start emits :start then the :error terminal")
      (is (not-any? #(= :done (:type %)) @events)
          "no :done — the :error is the terminal event"))))

(deftest stream-anthropic-message-delta-first-emits-start-then-done-test
  (testing "a stream whose first event is message_delta-with-stop_reason emits :start then the terminal :done"
    ;; Review 52: the message_delta-with-stop_reason terminal branch emitted
    ;; :done with no preceding :start when the stream never received
    ;; message_start — review 50 tested message_stop-first and empty-body but
    ;; NOT message_delta-first, so a malformed stream starting with a
    ;; message_delta carrying stop_reason yielded [:done] while
    ;; message_stop-first yields [:start :done]. The branch now emits :start
    ;; first (mirroring emit-terminal-done!'s ordering: done? reset, then
    ;; :start, then the terminal), closing the last :start-before-terminal
    ;; gap on the anthropic transport.
    (let [model  (models/get-model :sonnet-4.6)
          convo  (-> (conv/create "sys") (conv/add-user-message "hi"))
          events (atom [])
          sse    (sse-line "message_delta"
                           {:type "message_delta"
                            :delta {:stop_reason "end_turn"}})]
      (let [response-fn (fn [_]
                          {:body (stream-body sse)})
            http-client (http-boundary/nullable [response-fn response-fn])]
        (anthropic/stream-anthropic convo model {:http-boundary http-client
                                                 :api-key "test-key"}
                                    (fn [e] (swap! events conj e))))
      (is (= [:start :done] (mapv :type @events))
          "message_delta-first emits :start then the terminal :done")
      (is (= 1 (count (filterv #(= :done (:type %)) @events)))
          "exactly one terminal :done")
      (is (= :end_turn (:reason (first (filterv #(= :done (:type %)) @events))))
          "the stop_reason keyword is carried through"))))

(deftest stream-anthropic-first-read-exception-emits-start-then-error-test
  (testing "a stream-read exception before any output event emits :start then the :error terminal"
    ;; Review 53: the outer catch block emitted [:error] with no preceding
    ;; :start when the exception fired before any output event (e.g. a
    ;; connection reset on the first read) — the last gap in the
    ;; review-50/52 :start-before-terminal class on this transport (every
    ;; in-band terminal/error emitter now emits :start first: the review-50
    ;; "error" branch, the review-52 message_delta branch, and
    ;; emit-terminal-done!). The catch now emits :start once (compare-and-set
    ;; on started? — the top-level emit-start! helper) before the :error, so
    ;; a first-read exception yields [:start :error] like the in-band error
    ;; branch.
    (let [model  (models/get-model :sonnet-4.6)
          convo  (-> (conv/create "sys") (conv/add-user-message "hi"))
          events (atom [])]
      (let [response-fn (fn [_]
                          (throw (ex-info "simulated connection reset"
                                          {:status 503})))
            http-client (http-boundary/nullable [response-fn response-fn])]
        (anthropic/stream-anthropic convo model {:http-boundary http-client
                                                 :api-key "test-key"}
                                    (fn [e] (swap! events conj e))))
      (is (= [:start :error] (mapv :type @events))
          "a first-read exception emits :start then the :error terminal")
      (is (= 1 (count (filterv #(= :error (:type %)) @events)))
          "exactly one :error terminal")
      (is (some? (:error-message (first (filterv #(= :error (:type %)) @events))))
          "the exception surfaces as an :error with a message"))))

(deftest stream-anthropic-message-stop-done-consumer-exception-no-second-error-test
  (testing "a consume-fn exception on the message_stop :done does not emit a second :error terminal"
    ;; Review 49: the message_stop terminal :done reset done? AFTER the
    ;; structured-output emissions and the :done consume — the ONLY terminal
    ;; path across the three transports that did this (message_delta-with-
    ;; stop_reason resets first; every OpenAI-transport terminal emitter
    ;; resets first). A downstream exception during the terminal processing
    ;; (here: the :done consume-fn throws, e.g. a statechart dispatch
    ;; failure inside make-provider-event-consumer's :done → :turn/done
    ;; send) propagated to the outer catch with done? still false and
    ;; emitted a SECOND :error terminal — the double-terminal class reviews
    ;; 43/44/46 eliminated on every other terminal path. emit-terminal-done!
    ;; (shared by the message_stop branch and the review-48 EOF flush) now
    ;; resets done? FIRST, so the exception is swallowed by the catch's
    ;; done? guard: exactly one terminal event, no second :error.
    (let [model  (models/get-model :sonnet-4.6)
          convo  (-> (conv/create "sys") (conv/add-user-message "hi"))
          events (atom [])
          sse    (str (sse-line "message_start" {:type "message_start"})
                      (sse-line "content_block_start"
                                {:type "content_block_start" :index 0
                                 :content_block {:type "text"}})
                      (sse-line "content_block_delta"
                                {:type "content_block_delta" :index 0
                                 :delta {:type "text_delta" :text "Hi"}})
                      (sse-line "content_block_stop"
                                {:type "content_block_stop" :index 0})
                      (sse-line "message_stop" {:type "message_stop"}))
          threw  (atom false)]
      (let [response-fn (fn [_]
                          {:body (stream-body sse)})
            http-client (http-boundary/nullable [response-fn response-fn])]
        (try
          (anthropic/stream-anthropic
           convo model {:http-boundary http-client
                        :api-key "test-key"}
           (fn [e]
             (swap! events conj e)
             (when (= :done (:type e))
               (reset! threw true)
               (throw (ex-info "simulated :done consume failure" {})))))
          (catch Exception _ nil)))
      (is @threw "the :done consume-fn did throw (the scenario is exercised)")
      (is (= 1 (count (filterv #(= :done (:type %)) @events)))
          "exactly one terminal :done reached the consumer")
      (is (not-any? #(= :error (:type %)) @events)
          "no second :error — the post-:done exception is swallowed by the catch's done? guard")
      (is (= [:start :text-start :text-delta :text-end :done] (mapv :type @events))
          "the normal text-block events and exactly one terminal :done reach the consumer — nothing after :done"))))

(deftest redacted-thinking-block-not-mislabeled-as-text-test
  (testing "a redacted_thinking content block's start/stop emit no :text-start/:text-end"
    ;; Review 48: content-block-start-event/content-block-stop-event fell to
    ;; the default :text-start/:text-end for "redacted_thinking" blocks
    ;; (Anthropic's first thinking block in extended-thinking streams,
    ;; carrying opaque base64 :data), so the accumulator created a phantom
    ;; empty text block and the last-provider-event marker mislabeled a
    ;; thinking-family block stop as text — the same mislabel class review 43
    ;; fixed for "thinking". The block is now SKIPPED (no start event, no
    ;; stop event, no delta): no phantom text block, no mislabeled marker,
    ;; and no unbalanced :thinking-end for a block whose start was skipped
    ;; (which would create a phantom CLOSED block downstream). Not reachable
    ;; on the newly shipped DeepSeek provider (its compat table explicitly
    ;; does not support redacted-thinking) — this completes the review-43
    ;; typing change for the built-in Anthropic path.
    (let [model  (models/get-model :sonnet-4.6)
          convo  (-> (conv/create "sys") (conv/add-user-message "hi"))
          events (atom [])
          sse    (str (sse-line "message_start" {:type "message_start"})
                      (sse-line "content_block_start"
                                {:type "content_block_start" :index 0
                                 :content_block {:type "redacted_thinking"
                                                 :data "cmVkYWN0ZWQ="}})
                      (sse-line "content_block_delta"
                                {:type "content_block_delta" :index 0
                                 :delta {:type "redacted_thinking_delta"
                                         :data "cmVkYWN0ZWQ="
                                         ;; Review 50: a :text key on a
                                         ;; redacted_thinking_delta proves the
                                         ;; explicit skip branch — before the
                                         ;; explicit "redacted_thinking"
                                         ;; branch, the delta fell through to
                                         ;; the default text branch and
                                         ;; returned nil only because the
                                         ;; delta carried no :text; a future
                                         ;; delta with :text would have
                                         ;; emitted a phantom :text-delta for
                                         ;; a block whose start/stop are
                                         ;; skipped.
                                         :text "should-not-leak"}})
                      (sse-line "content_block_stop"
                                {:type "content_block_stop" :index 0})
                      (sse-line "message_stop" {:type "message_stop"}))]
      (let [response-fn (fn [_]
                          {:body (stream-body sse)})
            http-client (http-boundary/nullable [response-fn response-fn])]
        (anthropic/stream-anthropic convo model {:http-boundary http-client
                                                 :api-key "test-key"}
                                    (fn [e] (swap! events conj e))))
      (is (not-any? #(= :text-start (:type %)) @events)
          "no :text-start — the redacted_thinking start is skipped, not mislabeled as text")
      (is (not-any? #(= :text-end (:type %)) @events)
          "no :text-end — the redacted_thinking stop is skipped, not mislabeled as text")
      (is (not-any? #(= :thinking-start (:type %)) @events)
          "no :thinking-start — the block is skipped entirely, no empty thinking block")
      (is (not-any? #(= :thinking-end (:type %)) @events)
          "no :thinking-end — the skip is balanced (no phantom closed block)")
      (is (not-any? #(= :text-delta (:type %)) @events)
          "no :text-delta — the redacted_thinking_delta is not misrouted as text")
      (is (= 1 (count (filterv #(= :done (:type %)) @events)))
          "exactly one :done — the stream terminates normally via message_stop"))))

(defn- run-stream [sse-str model options]
  (let [events (atom [])
        http   (http-boundary/nullable [{:body (stream-body sse-str)}])]
    (anthropic/stream-anthropic (-> (conv/create "sys") (conv/add-user-message "hi"))
                                model (assoc options :http-boundary http)
                                (fn [e] (swap! events conj e)))
    @events))

;; ── Malformed-stream :start + unknown-index block handling (review 54) ──────

(deftest stream-anthropic-content-block-start-first-emits-start-test
  (testing "a content_block_start-first stream (no message_start) emits :start before the first content event"
    ;; Review 54: the content-block branches never emitted :start — the
    ;; non-terminal half of the review-50 :start-before-first-event class
    ;; (reviews 50/52/53 fixed the terminal/error/catch emitters only). A
    ;; malformed/non-conforming stream whose FIRST event is
    ;; content_block_start emitted [:text-start :text-delta :text-end
    ;; :start :done] — the first content event had no preceding :start, and
    ;; :start appeared only at the terminal, AFTER the content events. Both
    ;; sibling transports emit :start before the first content event
    ;; (:openai-completions emit-started-event!, :openai-codex-responses
    ;; emit-codex-started-event!). The content-block branches now emit
    ;; :start once (shared request-support/emit-start! compare-and-set — a
    ;; no-op when message_start already fired).
    (let [model (models/get-model :sonnet-4.6)
          sse   (str (sse-line "content_block_start"
                               {:type "content_block_start" :index 0
                                :content_block {:type "text" :text ""}})
                     (sse-line "content_block_delta"
                               {:type "content_block_delta" :index 0
                                :delta {:type "text_delta" :text "Hello"}})
                     (sse-line "content_block_stop" {:type "content_block_stop" :index 0})
                     (sse-line "message_stop" {:type "message_stop"}))]
      (is (= [:start :text-start :text-delta :text-end :done]
             (mapv :type (run-stream sse model {:api-key "test-key"})))))))

(deftest stream-anthropic-unknown-index-content-block-skipped-test
  (testing "content_block_delta/stop at an index whose start was never received are skipped — no unbalanced text events"
    ;; Review 54: content_block_delta/content_block_stop for an UNKNOWN
    ;; index (no prior content_block_start — a stream that omits start
    ;; events, reuses indices, or reorders deltas/stops ahead of starts)
    ;; previously emitted unbalanced :text-delta/:text-end: (:type
    ;; block-info) is nil for a missing index, which fell through the
    ;; default TEXT branch — a phantom :text-delta for a block that never
    ;; had a :text-start (the turn accumulator's note-content-delta! opened
    ;; a block at an unbegun index). The delta/stop branches are now
    ;; nil-guarded on block-info (skip unknown indices, mirroring the codex
    ;; sibling's skip of an unresolved index). :start is still emitted —
    ;; the stream IS producing content-block events.
    (let [model (models/get-model :sonnet-4.6)
          sse   (str (sse-line "content_block_delta"
                               {:type "content_block_delta" :index 5
                                :delta {:type "text_delta" :text "phantom"}})
                     (sse-line "content_block_stop" {:type "content_block_stop" :index 5})
                     (sse-line "message_stop" {:type "message_stop"}))]
      (is (= [:start :done]
             (mapv :type (run-stream sse model {:api-key "test-key"})))
          "delta/stop-first stream: unknown-index events skipped, :start fires, message_stop terminates")))

  (testing "unknown-index delta/stop after a normal message_start are skipped too"
    (let [model (models/get-model :sonnet-4.6)
          sse   (str (sse-line "message_start" {:type "message_start"})
                     (sse-line "content_block_delta"
                               {:type "content_block_delta" :index 5
                                :delta {:type "text_delta" :text "phantom"}})
                     (sse-line "content_block_stop" {:type "content_block_stop" :index 5})
                     (sse-line "message_stop" {:type "message_stop"}))]
      (is (= [:start :done]
             (mapv :type (run-stream sse model {:api-key "test-key"})))
          "well-formed stream with a bad index: no phantom :text-delta/:text-end, exactly one terminal"))))

;; ── EOF open-block balancing (review 55) ─────────────────────────────────────

(deftest stream-anthropic-eof-balances-open-tool-block-test
  (testing "a stream that EOFs mid-tool_use (no stop, no message_stop) closes the open block before :done"
    ;; Review 55: the EOF-level terminal flush emitted :done with an OPEN
    ;; block index — a tool_use block whose content_block_stop never arrived
    ;; left the turn accumulator with an unclosed index when handle-done!
    ;; finalized (no :toolcall-end precedes the :done), the
    ;; no-phantom-or-unbalanced-block invariant via the EOF path (codex
    ;; balances open tool indexes at its EOF flush; the anthropic EOF flush
    ;; did not). Probe-verified pre-fix: message_start +
    ;; content_block_start (tool_use) + EOF → [:start :toolcall-start :done].
    ;; The terminal now emits :toolcall-end for every open index before the
    ;; :done, mirroring codex's emit-codex-done! open-tool-indexes doseq.
    (let [model (models/get-model :sonnet-4.6)
          sse   (str (sse-line "message_start" {:type "message_start"})
                     (sse-line "content_block_start"
                               {:type "content_block_start" :index 0
                                :content_block {:type "tool_use"
                                                :id "toolu_01"
                                                :name "get_weather"}}))]
      (is (= [:start :toolcall-start :toolcall-end :done]
             (mapv :type (run-stream sse model {:api-key "test-key"})))
          "the open tool_use block is balanced with :toolcall-end before the EOF :done"))))

(deftest stream-anthropic-eof-balances-open-thinking-block-test
  (testing "a stream that EOFs mid-thinking (no stop, no message_stop) closes the open block before :done"
    ;; Review 55: same class as the tool_use case — a thinking block started
    ;; but never stopped left the accumulator with an OPEN index at :done
    ;; (probe-verified pre-fix: message_start + content_block_start
    ;; (thinking) + EOF → [:start :thinking-start :done]). The terminal now
    ;; balances it with :thinking-end (the review-43 typed-block event for
    ;; the thinking type) before the :done.
    (let [model (models/get-model :sonnet-4.6)
          sse   (str (sse-line "message_start" {:type "message_start"})
                     (sse-line "content_block_start"
                               {:type "content_block_start" :index 0
                                :content_block {:type "thinking"
                                                :thinking "Let me think"
                                                :signature "sig-1"}}))]
      (is (= [:start :thinking-start :thinking-end :done]
             (mapv :type (run-stream sse model {:api-key "test-key"})))
          "the open thinking block is balanced with :thinking-end before the EOF :done"))))

(deftest stream-anthropic-eof-balances-open-text-block-test
  (testing "a stream that EOFs mid-text closes the open block before :done"
    ;; Review 55: text blocks have the same EOF gap — a text block started
    ;; but never stopped (stream truncated mid-reply) left an OPEN index at
    ;; :done. The terminal now balances it with :text-end. A well-formed
    ;; stream (stop received) is unaffected: the stop branch dissocs the
    ;; index, so a message_stop-terminated stream emits no synthetic end.
    (let [model (models/get-model :sonnet-4.6)
          sse   (str (sse-line "message_start" {:type "message_start"})
                     (sse-line "content_block_start"
                               {:type "content_block_start" :index 0
                                :content_block {:type "text" :text ""}})
                     (sse-line "content_block_delta"
                               {:type "content_block_delta" :index 0
                                :delta {:type "text_delta" :text "Hi"}}))]
      (is (= [:start :text-start :text-delta :text-end :done]
             (mapv :type (run-stream sse model {:api-key "test-key"})))
          "the open text block is balanced with :text-end before the EOF :done")))

  (testing "a well-formed stream (every block stopped before message_stop) emits no synthetic ends"
    (let [model (models/get-model :sonnet-4.6)
          sse   (str (sse-line "message_start" {:type "message_start"})
                     (sse-line "content_block_start"
                               {:type "content_block_start" :index 0
                                :content_block {:type "text" :text ""}})
                     (sse-line "content_block_delta"
                               {:type "content_block_delta" :index 0
                                :delta {:type "text_delta" :text "Hi"}})
                     (sse-line "content_block_stop" {:type "content_block_stop" :index 0})
                     (sse-line "message_stop" {:type "message_stop"}))]
      (is (= [:start :text-start :text-delta :text-end :done]
             (mapv :type (run-stream sse model {:api-key "test-key"})))
          "no duplicate :text-end — the stop branch dissoc'd the index before message_stop"))))

(deftest stream-anthropic-eof-balances-multiple-open-blocks-in-index-order-test
  (testing "multiple open blocks at EOF are balanced in index order before the :done"
    ;; Review 55: the open-block doseq sorts by index so the balancing end
    ;; events are deterministic — a truncated stream with a text block (0)
    ;; and an unstarted/stopped tool block (1) still open closes both.
    (let [model (models/get-model :sonnet-4.6)
          sse   (str (sse-line "message_start" {:type "message_start"})
                     (sse-line "content_block_start"
                               {:type "content_block_start" :index 1
                                :content_block {:type "tool_use"
                                                :id "toolu_02"
                                                :name "get_weather"}})
                     (sse-line "content_block_start"
                               {:type "content_block_start" :index 0
                                :content_block {:type "text" :text ""}}))]
      (is (= [:start :toolcall-start :text-start :text-end :toolcall-end :done]
             (mapv :type (run-stream sse model {:api-key "test-key"})))
          "ends emitted sorted by index (text 0 before tool 1) — deterministic balancing"))))

(deftest stream-anthropic-ignores-deepseek-ping-events-test
  (testing "a mid-stream ping SSE event (DeepSeek's extra event type) is ignored — no error, no hang, no unbalanced events"
    ;; Review 55 (live verification, 2026-08-09): DeepSeek's streaming path
    ;; was exercised live for the first time — the stream CONFORMED to the
    ;; Anthropic shape (message_start / message_delta / message_stop,
    ;; balanced content blocks, adaptive thinking accepted, Anthropic-shaped
    ;; cache usage fields), with one observed deviation: an extra mid-stream
    ;; `ping` SSE event (`data: {"type":"ping"}`) between content deltas,
    ;; not part of Anthropic's event set. parse-sse-line parses it to
    ;; `{:type "ping"}`, which matches no case branch → nil → no-op (no
    ;; hang, no :error, no unbalanced event). Lock the tolerance so a
    ;; future transport change that starts treating unknown event types as
    ;; errors does not regress DeepSeek.
    (let [model (models/get-model :sonnet-4.6)
          sse   (str (sse-line "message_start" {:type "message_start"})
                     (sse-line "content_block_start"
                               {:type "content_block_start" :index 0
                                :content_block {:type "text" :text ""}})
                     (sse-line "ping" {:type "ping"})
                     (sse-line "content_block_delta"
                               {:type "content_block_delta" :index 0
                                :delta {:type "text_delta" :text "Hi"}})
                     (sse-line "ping" {:type "ping"})
                     (sse-line "content_block_stop" {:type "content_block_stop" :index 0})
                     (sse-line "message_stop" {:type "message_stop"}))
          events (run-stream sse model {:api-key "test-key"})]
      (is (= [:start :text-start :text-delta :text-end :done]
             (mapv :type events))
          "the ping events are ignored — exactly the well-formed sequence")
      (is (= 1 (count (filterv #(= :done (:type %)) events)))
          "exactly one :done — the stream terminates normally via message_stop")
      (is (not-any? #(= :error (:type %)) events)
          "no :error — an unknown event type is a no-op, not an error"))))

;; ── Open-block balancing on the error/message_delta terminals (review 56) ──

(deftest stream-anthropic-error-after-thinking-start-balances-open-block-test
  (testing "a mid-stream SSE error after a thinking block started closes the block before :error"
    ;; Review 56: review-55's open-block balancing covered only the :done
    ;; terminals (message_stop + the EOF flush) — the mid-stream "error"
    ;; SSE branch emitted :error with no balancing, so a stream that started
    ;; a thinking block and then received overloaded_error yielded
    ;; [:start :thinking-start :thinking-delta :error] with the block
    ;; :status :open in turn-data's :content-blocks (exposed via the
    ;; :psi.turn/content-blocks telemetry resolver) — the exact
    ;; no-phantom-or-unbalanced-block invariant review 55 asserted "via the
    ;; EOF path", still open via the error path. The "error" branch now
    ;; balances open blocks (shared balance-open-blocks!) before the :error.
    (let [model (models/get-model :sonnet-4.6)
          sse   (str (sse-line "message_start" {:type "message_start"})
                     (sse-line "content_block_start"
                               {:type "content_block_start" :index 0
                                :content_block {:type "thinking"
                                                :thinking "Let me think"
                                                :signature "sig-1"}})
                     (sse-line "content_block_delta"
                               {:type "content_block_delta" :index 0
                                :delta {:type "thinking_delta"
                                        :thinking "Let me think further"}})
                     (sse-line "error"
                               {:type "error"
                                :error {:type "overloaded_error"
                                        :message "Overloaded"
                                        :http_status 529}}))
          events (run-stream sse model {:api-key "test-key"})]
      (is (= [:start :thinking-start :thinking-delta :thinking-end :error]
             (mapv :type events))
          "the open thinking block is balanced with :thinking-end before the :error"))))

(deftest stream-anthropic-error-after-tool-start-balances-open-block-test
  (testing "a mid-stream SSE error after a tool_use block started closes the block before :error"
    ;; Review 56: same class as the thinking case — a tool_use block started
    ;; but never stopped, then a mid-stream error, previously finalized with
    ;; the tool call OPEN ([:start :toolcall-start :error]). The "error"
    ;; branch now emits :toolcall-end for the open index before the :error.
    (let [model (models/get-model :sonnet-4.6)
          sse   (str (sse-line "message_start" {:type "message_start"})
                     (sse-line "content_block_start"
                               {:type "content_block_start" :index 0
                                :content_block {:type "tool_use"
                                                :id "toolu_01"
                                                :name "get_weather"}})
                     (sse-line "error"
                               {:type "error"
                                :error {:type "overloaded_error"
                                        :message "Overloaded"
                                        :http_status 529}}))]
      (is (= [:start :toolcall-start :toolcall-end :error]
             (mapv :type (run-stream sse model {:api-key "test-key"})))
          "the open tool_use block is balanced with :toolcall-end before the :error"))))

(deftest stream-anthropic-message-delta-stop-reason-with-open-blocks-balances-test
  (testing "a message_delta-with-stop_reason terminal with open blocks closes them before :done"
    ;; Review 56: the message_delta-with-stop_reason branch emits its INLINE
    ;; :done (separate from emit-terminal-done! since it carries the actual
    ;; stop_reason) WITHOUT the review-55 open-block balancing — the two
    ;; :done branches of the same transport disagreed (message_stop
    ;; balanced, message_delta-with-stop_reason not), so a non-conforming
    ;; stream that sends message_delta-with-stop_reason while a block is
    ;; open finalized with an OPEN index. The branch now balances via the
    ;; shared balance-open-blocks! before the :done, keeping the real
    ;; stop_reason (the reason is NOT forced to :stop — the branch stays
    ;; inline rather than routing through emit-terminal-done!).
    (let [model (models/get-model :sonnet-4.6)
          sse   (str (sse-line "message_start" {:type "message_start"})
                     (sse-line "content_block_start"
                               {:type "content_block_start" :index 0
                                :content_block {:type "tool_use"
                                                :id "toolu_02"
                                                :name "get_weather"}})
                     (sse-line "message_delta"
                               {:type "message_delta"
                                :delta {:stop_reason "end_turn"}}))
          events (run-stream sse model {:api-key "test-key"})]
      (is (= [:start :toolcall-start :toolcall-end :done]
             (mapv :type events))
          "the open tool_use block is balanced with :toolcall-end before the message_delta :done")
      (is (= :end_turn (:reason (first (filterv #(= :done (:type %)) events))))
          "the real stop_reason is preserved on the :done (not forced to :stop)"))))

(deftest stream-anthropic-catch-balances-open-block-before-error-test
  (testing "a stream-read exception after a block started closes the block before :error"
    ;; Exercise the real SSE parser over a response stream that disconnects
    ;; after the open block's bytes have been consumed.
    (let [model (models/get-model :sonnet-4.6)
          convo (-> (conv/create "sys") (conv/add-user-message "hi"))
          events (atom [])
          sse (str (sse-line "message_start" {:type "message_start"})
                   (sse-line "content_block_start"
                             {:type "content_block_start" :index 0
                              :content_block {:type "thinking"
                                              :thinking "Let me think"
                                              :signature "sig-1"}}))
          http (http-boundary/nullable [{:body (throwing-stream-after sse)}])]
      (anthropic/stream-anthropic convo model {:api-key "test-key" :http-boundary http}
                                  (fn [e] (swap! events conj e)))
      (is (= [:start :thinking-start :thinking-end :error]
             (mapv :type @events))
          "the open thinking block is balanced with :thinking-end before the catch's :error")
      (is (= 1 (count (filterv #(= :error (:type %)) @events)))
          "exactly one :error terminal"))))
