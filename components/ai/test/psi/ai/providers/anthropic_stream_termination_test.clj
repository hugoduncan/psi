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
   [clj-http.client :as http]
   [psi.ai.conversation :as conv]
   [psi.ai.models :as models]
   [psi.ai.providers.anthropic :as anthropic])
  (:import [java.io ByteArrayInputStream]))

(defn- sse-line [event-type data-map]
  (str "event: " event-type "\ndata: " (json/generate-string data-map) "\n\n"))

(defn- stream-body [s]
  (ByteArrayInputStream. (.getBytes s "UTF-8")))

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
      (with-redefs [http/post (fn [_url _req]
                                {:body (stream-body sse)})]
        (anthropic/stream-anthropic convo model {:api-key "test-key"}
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
      (with-redefs [http/post (fn [_url _req]
                                {:body (stream-body sse)})]
        (try
          (anthropic/stream-anthropic
           convo model {:api-key "test-key"}
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
                                         :data "cmVkYWN0ZWQ="}})
                      (sse-line "content_block_stop"
                                {:type "content_block_stop" :index 0})
                      (sse-line "message_stop" {:type "message_stop"}))]
      (with-redefs [http/post (fn [_url _req]
                                {:body (stream-body sse)})]
        (anthropic/stream-anthropic convo model {:api-key "test-key"}
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
