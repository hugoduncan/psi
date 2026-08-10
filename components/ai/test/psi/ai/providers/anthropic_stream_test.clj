(ns psi.ai.providers.anthropic-stream-test
  (:require
   [clj-http.client :as http]
   [psi.ai.providers.http-boundary :as http-boundary]
   [clojure.test :refer [deftest is testing]]
   [cheshire.core :as json]
   [psi.ai.conversation :as conv]
   [psi.ai.models :as models]
   [psi.ai.proxy :as proxy]
   [psi.ai.providers.anthropic :as anthropic])
  (:import [java.io ByteArrayInputStream InputStream]))

(defn- sse-line [event-type data-map]
  (str "event: " event-type "\ndata: " (json/generate-string data-map) "\n\n"))

(defn- stream-body [s]
  (ByteArrayInputStream. (.getBytes s "UTF-8")))

(defn- throwing-stream-after [s ex-data]
  (let [bytes (.getBytes s "UTF-8")
        index (atom 0)]
    (proxy [InputStream] []
      (read
        ([]
         (if (< @index (alength bytes))
           (let [value (bit-and 0xff (aget bytes @index))]
             (swap! index inc)
             value)
           (throw (ex-info "simulated stream read failure" ex-data))))
        ([buffer offset length]
         (if (< @index (alength bytes))
           (let [remaining (- (alength bytes) @index)
                 count (min length remaining)]
             (System/arraycopy bytes @index buffer offset count)
             (swap! index + count)
             count)
           (throw (ex-info "simulated stream read failure" ex-data))))))))

(deftest stream-anthropic-applies-proxy-request-options-test
  (testing "Anthropic stream request merges shared proxy options"
    (let [model      (models/get-model :sonnet-4.6)
          convo      (-> (conv/create "sys")
                         (conv/add-user-message "hello"))
          captured   (atom nil)
          sse        (str (sse-line "message_start" {:type "message_start"})
                          (sse-line "message_stop" {:type "message_stop"}))]
      (with-redefs [proxy/request-proxy-options (fn [url]
                                                  (is (= "https://api.anthropic.com/v1/messages" url))
                                                  {:proxy-host "proxy.example"
                                                   :proxy-port 8443
                                                   :proxy-scheme :http})
                    http/post (fn [_url req]
                                (reset! captured req)
                                {:body (stream-body sse)})]
        (anthropic/stream-anthropic convo model {:api-key "test-key"} (fn [_] nil)))
      (is (= "proxy.example" (:proxy-host @captured)))
      (is (= 8443 (:proxy-port @captured)))
      (is (= :http (:proxy-scheme @captured)))))

  (testing "Anthropic stream leaves request unchanged when no proxy applies"
    (let [model    (models/get-model :sonnet-4.6)
          convo    (-> (conv/create "sys")
                       (conv/add-user-message "hello"))
          captured (atom nil)
          sse      (str (sse-line "message_start" {:type "message_start"})
                        (sse-line "message_stop" {:type "message_stop"}))]
      (with-redefs [proxy/request-proxy-options (constantly nil)
                    http/post (fn [_url req]
                                (reset! captured req)
                                {:body (stream-body sse)})]
        (anthropic/stream-anthropic convo model {:api-key "test-key"} (fn [_] nil)))
      (is (nil? (:proxy-host @captured)))
      (is (nil? (:proxy-port @captured)))
      (is (nil? (:proxy-scheme @captured))))))
(deftest stream-anthropic-error-includes-status-and-request-id-test
  (testing "Anthropic HTTP errors preserve provider message, status, request id, and body"
    (let [model  (models/get-model :sonnet-4.6)
          convo  (-> (conv/create "sys")
                     (conv/add-user-message "hello"))
          events (atom [])
          http   (http-boundary/nullable
                  [(ex-info "Error"
                            {:status 400
                             :headers {"request-id" "req_ant_123"}
                             :body (stream-body
                                    (json/generate-string
                                     {:error {:message "cache_control requires prompt-caching beta"}}))})])]
      (anthropic/stream-anthropic convo model {:api-key "test-key"
                                               :http-boundary http}
                                  (fn [e] (swap! events conj e)))
      ;; Review 53: the catch block (a stream-read exception before any
      ;; output — the HTTP boundary throws, no response received) now emits :start
      ;; first, mirroring the in-band error branch — so the sequence is
      ;; [:start :error] with the :error as the second event.
      (is (= [:start :error] (mapv :type @events))
          "a first-read exception emits :start then the :error terminal")
      (is (= "cache_control requires prompt-caching beta (status 400) [request-id req_ant_123]"
             (:error-message (second @events))))
      (is (= 400 (:http-status (second @events))))
      (is (= "req_ant_123" (get-in (second @events) [:headers "request-id"])))
      (is (= {:error {:message "cache_control requires prompt-caching beta"}}
             (:body (second @events))))
      (is (string? (:body-text (second @events)))))))

(deftest stream-anthropic-non-2xx-response-map-surfaces-body-message-test
  (testing "non-2xx response map emits parsed provider error message"
    (let [model  (models/get-model :sonnet-4.6)
          convo  (-> (conv/create "sys")
                     (conv/add-user-message "hello"))
          events (atom [])]
      (with-redefs [http/post (fn [_url _req]
                                {:status 400
                                 :headers {"request-id" "req_ant_400"}
                                 :body (stream-body
                                        (json/generate-string
                                         {:error {:message "invalid messages payload"}}))})]
        (anthropic/stream-anthropic convo model {:api-key "test-key"}
                                    (fn [e] (swap! events conj e))))
      (is (= 1 (count @events)))
      (is (= :error (:type (first @events))))
      (is (= "invalid messages payload (status 400) [request-id req_ant_400]"
             (:error-message (first @events))))
      (is (= 400 (:http-status (first @events))))))

  (testing "missing 400 body uses actionable fallback text"
    (let [model  (models/get-model :sonnet-4.6)
          convo  (-> (conv/create "sys")
                     (conv/add-user-message "hello"))
          events (atom [])]
      (with-redefs [http/post (fn [_url _req]
                                {:status 400
                                 :headers {"request-id" "req_ant_nobody"}
                                 :body nil})]
        (anthropic/stream-anthropic convo model {:api-key "test-key"}
                                    (fn [e] (swap! events conj e))))
      (is (= 1 (count @events)))
      (is (re-find #"Anthropic rejected the request"
                   (:error-message (first @events))))
      (is (re-find #"no error body returned"
                   (:error-message (first @events))))
      (is (re-find #"possible causes"
                   (:error-message (first @events))))
      (is (re-find #"request\{model=claude-sonnet-4-6"
                   (:error-message (first @events))))
      (is (re-find #"request-id req_ant_nobody"
                   (:error-message (first @events)))))))

;; ── SSE parser — thinking block routing ─────────────────────────────────────

(defn- run-stream [sse-str model options]
  (let [events (atom [])
        convo  (-> (conv/create "sys") (conv/add-user-message "hi"))]
    (with-redefs [http/post (fn [_url _req]
                              {:body (stream-body sse-str)})]
      (anthropic/stream-anthropic convo model options
                                  (fn [e] (swap! events conj e))))
    @events))

(deftest thinking-block-emits-thinking-delta-test
  (testing "thinking content block deltas are routed as :thinking-delta events"
    (let [model (models/get-model :sonnet-4.6)
          sse   (str (sse-line "message_start" {:type "message_start"})
                     (sse-line "content_block_start"
                               {:type "content_block_start" :index 0
                                :content_block {:type "thinking" :thinking "" :signature ""}})
                     (sse-line "content_block_delta"
                               {:type "content_block_delta" :index 0
                                :delta {:type "thinking_delta" :thinking "I think"}})
                     (sse-line "content_block_delta"
                               {:type "content_block_delta" :index 0
                                :delta {:type "thinking_delta" :thinking " therefore"}})
                     (sse-line "content_block_delta"
                               {:type "content_block_delta" :index 0
                                :delta {:type "signature_delta" :signature "sig-1"}})
                     (sse-line "content_block_stop"
                               {:type "content_block_stop" :index 0})
                     (sse-line "content_block_start"
                               {:type "content_block_start" :index 1
                                :content_block {:type "text"}})
                     (sse-line "content_block_delta"
                               {:type "content_block_delta" :index 1
                                :delta {:type "text_delta" :text "Hello"}})
                     (sse-line "content_block_stop"
                               {:type "content_block_stop" :index 1})
                     (sse-line "message_stop" {:type "message_stop"}))
          events (run-stream sse model {:api-key "test-key"})]
      (is (some #(= :thinking-start (:type %)) events)
          "should emit a thinking-start event")
      (is (some #(= :thinking-delta (:type %)) events)
          "should emit at least one :thinking-delta")
      (is (= ["I think" " therefore"]
             (->> events
                  (filter #(= :thinking-delta (:type %)))
                  (mapv :delta)))
          "thinking deltas carry incremental text")
      (is (= "sig-1"
             (some #(when (= :thinking-signature-delta (:type %))
                      (:signature %))
                   events))
          "signature deltas are surfaced separately")
      (is (some #(= :text-delta (:type %)) events)
          "text block after thinking block still emits :text-delta")
      (is (not-any? #(and (= :text-delta (:type %))
                          (some-> (:delta %) (.contains "I think")))
                    events)
          "thinking text must not bleed into :text-delta events"))))

(deftest text-block-not-misrouted-as-thinking-test
  (testing "plain text blocks are not emitted as :thinking-delta"
    (let [model (models/get-model :sonnet-4.6)
          sse   (str (sse-line "message_start" {:type "message_start"})
                     (sse-line "content_block_start"
                               {:type "content_block_start" :index 0
                                :content_block {:type "text"}})
                     (sse-line "content_block_delta"
                               {:type "content_block_delta" :index 0
                                :delta {:type "text_delta" :text "Hello"}})
                     (sse-line "content_block_stop"
                               {:type "content_block_stop" :index 0})
                     (sse-line "message_stop" {:type "message_stop"}))
          events (run-stream sse model {:api-key "test-key"})]
      (is (not-any? #(= :thinking-delta (:type %)) events))
      (is (some #(= :text-delta (:type %)) events)))))

(deftest thinking-block-stop-emits-thinking-end-test
  (testing "thinking content block stops emit :thinking-end, not :text-end"
    ;; Review 43: content-block-stop-event mapped every non-tool block stop
    ;; to :text-end, so a thinking block's stop mislabeled the
    ;; last-provider-event diagnostic marker as text and left the
    ;; accumulator's dedicated :on-thinking-end handler
    ;; (note-last-provider-event! :thinking-end + end-content-block!) dead
    ;; code for the anthropic path.
    (let [model  (models/get-model :sonnet-4.6)
          convo  (-> (conv/create "sys") (conv/add-user-message "hi"))
          events (atom [])
          sse    (str (sse-line "message_start" {:type "message_start"})
                      (sse-line "content_block_start"
                                {:type "content_block_start" :index 0
                                 :content_block {:type "thinking" :thinking "" :signature ""}})
                      (sse-line "content_block_delta"
                                {:type "content_block_delta" :index 0
                                 :delta {:type "thinking_delta" :thinking "I think"}})
                      (sse-line "content_block_stop"
                                {:type "content_block_stop" :index 0})
                      (sse-line "content_block_start"
                                {:type "content_block_start" :index 1
                                 :content_block {:type "text"}})
                      (sse-line "content_block_delta"
                                {:type "content_block_delta" :index 1
                                 :delta {:type "text_delta" :text "Hello"}})
                      (sse-line "content_block_stop"
                                {:type "content_block_stop" :index 1})
                      (sse-line "message_stop" {:type "message_stop"}))
          http   (http-boundary/nullable [{:body (stream-body sse)}])]
      (anthropic/stream-anthropic convo model {:api-key "test-key"
                                               :http-boundary http}
                                  (fn [event] (swap! events conj event)))
      (is (some #(and (= :thinking-end (:type %))
                      (= 0 (:content-index %)))
                @events)
          "thinking block stop must emit :thinking-end")
      (is (not-any? #(and (= :text-end (:type %))
                          (= 0 (:content-index %)))
                    @events)
          "thinking block stop must not be mislabeled :text-end")
      (is (some #(and (= :text-end (:type %))
                      (= 1 (:content-index %)))
                @events)
          "text block stop must still emit :text-end"))))

(deftest stream-anthropic-surfaces-sse-error-event-test
  (testing "a mid-stream Anthropic SSE error event emits :error and terminates"
    ;; Review 43: the stream loop's case handled only message_start/
    ;; content_block_*/message_delta/message_stop, so an Anthropic "error"
    ;; SSE event ({"type":"error","error":{...}} — the documented mid-stream
    ;; overloaded_error/rate-limit shape) was consumed as a no-op: no :error
    ;; event, no terminal :done, hanging the turn until the idle timeout.
    (let [model  (models/get-model :sonnet-4.6)
          convo  (-> (conv/create "sys") (conv/add-user-message "hi"))
          events (atom [])
          sse    (str (sse-line "message_start" {:type "message_start"})
                      (sse-line "error"
                                {:type "error"
                                 :error {:type "overloaded_error"
                                         :message "Overloaded"
                                         :http_status 529}})
                      (sse-line "message_stop" {:type "message_stop"}))]
      (let [response-fn (fn [_]
                          {:body (stream-body sse)})
            http-client (http-boundary/nullable [response-fn response-fn])]
        (anthropic/stream-anthropic convo model {:http-boundary http-client
                                                 :api-key "test-key"}
                                    (fn [e] (swap! events conj e))))
      (let [err (first (filter #(= :error (:type %)) @events))]
        (is (= [:start :error] (mapv :type @events)))
        (is (= "Overloaded (status 529)" (:error-message err))
            "error message extracted from the event's error body, http-status appended")
        (is (= 529 (:http-status err))
            "http-status present in the event's error body is carried through")
        (is (= {:type "overloaded_error" :message "Overloaded" :http_status 529}
               (get-in err [:body :error]))
            "raw event body preserved"))))

  (testing "a mid-stream SSE error event without http-status still surfaces the message"
    (let [model  (models/get-model :sonnet-4.6)
          convo  (-> (conv/create "sys") (conv/add-user-message "hi"))
          events (atom [])
          sse    (str (sse-line "message_start" {:type "message_start"})
                      (sse-line "error"
                                {:type "error"
                                 :error {:type "invalid_request_error"
                                         :message "bad request"}}))]
      (let [response-fn (fn [_]
                          {:body (stream-body sse)})
            http-client (http-boundary/nullable [response-fn response-fn])]
        (anthropic/stream-anthropic convo model {:http-boundary http-client
                                                 :api-key "test-key"}
                                    (fn [e] (swap! events conj e))))
      (let [err (first (filter #(= :error (:type %)) @events))]
        (is (= [:start :error] (mapv :type @events)))
        (is (= "bad request" (:error-message err))
            "no http-status in the body → message without a status suffix")
        (is (nil? (:http-status err)))))))

(deftest stream-anthropic-error-then-message-delta-single-terminal-event-test
  (testing "a trailing message_delta after a mid-stream SSE error does not emit a second terminal :done"
    ;; Review 44: the message_delta branch's terminal :done emission was
    ;; unguarded — a mid-stream SSE error event followed by a trailing
    ;; message_delta carrying delta.stop_reason emitted a SECOND terminal
    ;; :done after the :error (verified: events = [:start :error :done]).
    ;; The branch is now guarded on done? like message_stop, and the usage
    ;; accumulation + structured-output-result emissions stay inside the
    ;; guard so a post-error message_delta is a full no-op.
    (let [model  (models/get-model :sonnet-4.6)
          convo  (-> (conv/create "sys") (conv/add-user-message "hi"))
          events (atom [])
          sse    (str (sse-line "message_start" {:type "message_start"})
                      (sse-line "error"
                                {:type "error"
                                 :error {:type "overloaded_error"
                                         :message "Overloaded"
                                         :http_status 529}})
                      (sse-line "message_delta"
                                {:type "message_delta"
                                 :delta {:stop_reason "end_turn"}}))]
      (let [response-fn (fn [_]
                          {:body (stream-body sse)})
            http-client (http-boundary/nullable [response-fn response-fn])]
        (anthropic/stream-anthropic convo model {:http-boundary http-client
                                                 :api-key "test-key"}
                                    (fn [e] (swap! events conj e))))
      (is (= [:start :error] (mapv :type @events))
          "exactly one terminal event — the :error; the trailing message_delta must not emit a second :done"))))

(deftest stream-anthropic-error-then-read-exception-no-second-error-test
  (testing "a stream-read exception after a mid-stream SSE error does not emit a second :error"
    ;; Review 44: the stream catch block emitted a second :error with no
    ;; done? check if the stream read threw after a mid-stream error had
    ;; already terminated the stream. Exercise the real parser through the
    ;; nullable HTTP boundary; the response body disconnects after the error.
    (let [model  (models/get-model :sonnet-4.6)
          convo  (-> (conv/create "sys") (conv/add-user-message "hi"))
          events (atom [])
          sse    (str (sse-line "message_start" {:type "message_start"})
                      (sse-line "error"
                                {:type "error"
                                 :error {:type "overloaded_error"
                                         :message "Overloaded"
                                         :http_status 529}}))
          http-client (http-boundary/nullable
                       [{:body (throwing-stream-after sse {})}])]
      (anthropic/stream-anthropic convo model {:http-boundary http-client
                                               :api-key "test-key"}
                                  (fn [e] (swap! events conj e)))
      (is (= [:start :error] (mapv :type @events))
          "the post-error stream-read exception emits nothing after the terminal :error"))))

(deftest stream-anthropic-error-then-content-block-stop-no-text-end-test
  (testing "a trailing content_block_stop after a mid-stream SSE error does not emit a second :text-end"
    ;; Review 46: the review-43/44 done? guard covered only the TERMINAL
    ;; branches (:done/:error emissions). The NON-terminal branches still
    ;; fired after the stream had terminated with an :error — a trailing
    ;; content_block_stop after the SSE error event emitted :text-end
    ;; (verified: events = [:start :text-start :text-delta :error :text-end]),
    ;; and a trailing structured-tool content_block_stop could fire
    ;; maybe-emit-structured-result! post-error. The whole SSE dispatch is
    ;; now short-circuited on done?, so a post-error trailing event is a
    ;; full no-op.
    ;; Review 56: the ONE :text-end now comes from the "error" branch's
    ;; open-block balancing (balance-open-blocks! — the block was started
    ;; and never stopped, so it is closed BEFORE the :error), NOT from the
    ;; trailing content_block_stop. The sequence is
    ;; [:start :text-start :text-delta :text-end :error] — the accumulator
    ;; finalizes balanced via the error path (review 55 balanced only the
    ;; :done/EOF paths), and the post-error trailing stop is still a full
    ;; no-op (exactly one :text-end, not two).
    (let [model  (models/get-model :sonnet-4.6)
          convo  (-> (conv/create "sys") (conv/add-user-message "hi"))
          events (atom [])
          sse    (str (sse-line "message_start" {:type "message_start"})
                      (sse-line "content_block_start"
                                {:type "content_block_start" :index 0
                                 :content_block {:type "text"}})
                      (sse-line "content_block_delta"
                                {:type "content_block_delta" :index 0
                                 :delta {:type "text_delta" :text "Hello"}})
                      (sse-line "error"
                                {:type "error"
                                 :error {:type "overloaded_error"
                                         :message "Overloaded"
                                         :http_status 529}})
                      (sse-line "content_block_stop"
                                {:type "content_block_stop" :index 0}))]
      (let [response-fn (fn [_]
                          {:body (stream-body sse)})
            http-client (http-boundary/nullable [response-fn response-fn])]
        (anthropic/stream-anthropic convo model {:http-boundary http-client
                                                 :api-key "test-key"}
                                    (fn [e] (swap! events conj e))))
      (is (= [:start :text-start :text-delta :text-end :error] (mapv :type @events))
          "the error branch balances the open text block (:text-end BEFORE the :error); the trailing content_block_stop is a full no-op once done"))))

(deftest usage-captured-from-sse-events-test
  (testing "usage tokens are read from message_start and message_delta SSE events"
    (let [model (models/get-model :sonnet-4.6)
          sse   (str (sse-line "message_start"
                               {:type    "message_start"
                                :message {:usage {:input_tokens                  100
                                                  :cache_read_input_tokens        20
                                                  :cache_creation_input_tokens    10}}})
                     (sse-line "content_block_start"
                               {:type "content_block_start" :index 0
                                :content_block {:type "text"}})
                     (sse-line "content_block_delta"
                               {:type "content_block_delta" :index 0
                                :delta {:type "text_delta" :text "Hi"}})
                     (sse-line "content_block_stop"
                               {:type "content_block_stop" :index 0})
                     (sse-line "message_delta"
                               {:type  "message_delta"
                                :delta {:stop_reason "end_turn"}
                                :usage {:output_tokens 50}})
                     (sse-line "message_stop" {:type "message_stop"}))
          events (run-stream sse model {:api-key "test-key"})
          done   (first (filter #(= :done (:type %)) events))
          usage  (:usage done)]
      (is (some? done) "should emit a :done event")
      (is (= 100 (:input-tokens usage))  "input-tokens from message_start")
      (is (= 50  (:output-tokens usage)) "output-tokens from message_delta")
      (is (= 20  (:cache-read-tokens usage))  "cache-read-tokens from message_start")
      (is (= 10  (:cache-write-tokens usage)) "cache-write-tokens from message_start")
      (is (= 180 (:total-tokens usage)) "total = input + output + cache-read + cache-write")
      (is (map? (:cost usage)) "cost map present"))))

(deftest stream-anthropic-message-stop-done-carries-usage-test
  (testing "a stream ending via message_stop without message_delta records the accumulated usage on the :done"
    ;; Review 47: the message_stop terminal :done carried no :usage — the
    ;; message_delta-with-stop_reason branch emits :done WITH
    ;; (usage-with-cost model usage-acc), but a stream terminating via
    ;; message_stop WITHOUT a preceding message_delta carrying stop_reason
    ;; emitted a bare {:type :done :reason :stop}, so handle-done!
    ;; ((map? usage) false) recorded ZERO usage/cost even though usage-acc
    ;; held the input + cache tokens accumulated from message_start.
    ;; Reachable on any Anthropic-compatible endpoint that omits
    ;; message_delta — including the newly shipped DeepSeek provider whose
    ;; streaming path is unverified.
    (let [model  (models/get-model :sonnet-4.6)
          convo  (-> (conv/create "sys") (conv/add-user-message "hi"))
          events (atom [])
          sse    (str (sse-line "message_start"
                                {:type    "message_start"
                                 :message {:usage {:input_tokens                  100
                                                   :cache_read_input_tokens        20
                                                   :cache_creation_input_tokens    10}}})
                      (sse-line "content_block_start"
                                {:type "content_block_start" :index 0
                                 :content_block {:type "text"}})
                      (sse-line "content_block_delta"
                                {:type "content_block_delta" :index 0
                                 :delta {:type "text_delta" :text "Hi"}})
                      (sse-line "content_block_stop"
                                {:type "content_block_stop" :index 0})
                      (sse-line "message_stop" {:type "message_stop"}))
          http   (http-boundary/nullable [{:body (stream-body sse)}])]
      (anthropic/stream-anthropic convo model {:api-key "test-key"
                                               :http-boundary http}
                                  (fn [event] (swap! events conj event)))
      (let [done  (first (filter #(= :done (:type %)) @events))
            usage (:usage done)]
        (is (= [:start :text-start :text-delta :text-end :done]
               (mapv :type @events))
            "message_stop terminates the complete stream with exactly one :done")
        (is (map? usage) ":done must carry the accumulated usage map (usage-with-cost)")
        (is (= 100 (:input-tokens usage))  "input-tokens accumulated from message_start")
        (is (= 20  (:cache-read-tokens usage))  "cache-read-tokens from message_start")
        (is (= 10  (:cache-write-tokens usage)) "cache-write-tokens from message_start")
        (is (= 0   (:output-tokens usage)) "output-tokens stays 0 — no message_delta in this flow")
        (is (= 130 (:total-tokens usage)) "total = input + cache-read + cache-write")
        (is (map? (:cost usage)) "cost map present")))))

(deftest stream-anthropic-sse-error-status-key-test
  (testing "a mid-stream SSE error carrying :status (not :http_status) surfaces a numeric http-status"
    ;; Review 47: the "error" branch read http-status from [:error :http_status]/
    ;; :http_status only, so an Anthropic-compatible endpoint emitting
    ;; {"type":"error","error":{"status":529,...}} — or a generic message
    ;; plus a status key — lost its status: the :error event carried no
    ;; numeric :http-status, so downstream retry-error?/provider-error-kind
    ;; fell to :unknown and a transient mid-stream 5xx/overload was NOT
    ;; auto-retried (the review-23 class the openai emit-chat-error! and
    ;; codex codex-error-http-status already handle). Now mirrors
    ;; emit-chat-error!'s extraction: :status / [:error :status] /
    ;; [:error :http_status], numeric >= 400 only.
    (let [model  (models/get-model :sonnet-4.6)
          convo  (-> (conv/create "sys") (conv/add-user-message "hi"))
          events (atom [])
          sse    (str (sse-line "message_start" {:type "message_start"})
                      (sse-line "error"
                                {:type "error"
                                 :error {:type "overloaded_error"
                                         :message "Overloaded"
                                         :status 529}}))]
      (let [response-fn (fn [_]
                          {:body (stream-body sse)})
            http-client (http-boundary/nullable [response-fn response-fn])]
        (anthropic/stream-anthropic convo model {:http-boundary http-client
                                                 :api-key "test-key"}
                                    (fn [e] (swap! events conj e))))
      (let [err (first (filter #(= :error (:type %)) @events))]
        (is (= [:start :error] (mapv :type @events)))
        (is (= "Overloaded (status 529)" (:error-message err))
            "status from [:error :status] is appended to the message")
        (is (= 529 (:http-status err))
            "[:error :status] is carried through as the numeric :http-status"))))

  (testing "a non-numeric status is dropped — numeric >= 400 only"
    (let [model  (models/get-model :sonnet-4.6)
          convo  (-> (conv/create "sys") (conv/add-user-message "hi"))
          events (atom [])
          sse    (str (sse-line "message_start" {:type "message_start"})
                      (sse-line "error"
                                {:type "error"
                                 :error {:type "overloaded_error"
                                         :message "Overloaded"
                                         :status "529"}}))]
      (let [response-fn (fn [_]
                          {:body (stream-body sse)})
            http-client (http-boundary/nullable [response-fn response-fn])]
        (anthropic/stream-anthropic convo model {:http-boundary http-client
                                                 :api-key "test-key"}
                                    (fn [e] (swap! events conj e))))
      (let [err (first (filter #(= :error (:type %)) @events))]
        (is (= [:start :error] (mapv :type @events)))
        (is (= "Overloaded" (:error-message err))
            "string status is not appended to the message")
        (is (nil? (:http-status err))
            "string status must not become a numeric :http-status — only numeric >= 400 is kept")))))
