(ns psi.ai.providers.openai-completions-stream-test
  "Review-48/51 stream follow-ups for the :openai-completions transport: the
  EOF-level terminal flush (finish_reason-chunk-then-EOF and
  [DONE]-without-finish_reason both terminate with exactly one :done instead
  of hanging), the zero-usage :done semantics for endpoints that omit the
  usage chunk, and the review-51 top-level http_status extraction on
  mid-stream error chunks. Split out of openai_test.clj /
  openai_completions_test.clj to stay under the 800-line file-length gate."
  (:require
   [clojure.test :refer [deftest is testing]]
   [cheshire.core :as json]
   [psi.ai.providers.http-boundary :as http-boundary]
   [psi.ai.conversation :as conv]
   [psi.ai.models :as models]
   [psi.ai.providers.openai :as openai])
  (:import [java.io ByteArrayInputStream InputStream]))

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

(defn- run-stream [sse]
  (let [model  (models/get-model :gpt-5)
        convo  (-> (conv/create "sys") (conv/add-user-message "hi"))
        events (atom [])
        http   (http-boundary/nullable [{:body (stream-body sse)}])]
    ((:stream openai/provider)
     convo model {:api-key "sk-test" :http-boundary http}
     (fn [ev] (swap! events conj ev)))
    @events))

(deftest completions-finish-reason-then-eof-emits-done-test
  (testing "a stream ending with a finish_reason chunk but no trailing [DONE] emits exactly one terminal :done"
    ;; Review 48: stream-openai ended with NOTHING after the SSE doseq, so a
    ;; final chunk carrying finish_reason but no trailing [DONE] set
    ;; pending-finish-reason and never flushed it — flush-pending-chat-finish!
    ;; runs ONLY on a [DONE] line — leaving the turn with no :done/:error
    ;; until llm-stream-idle-timeout-ms (the codex transport already flushed
    ;; at EOF; the anthropic and openai streams did not). The EOF flush now
    ;; emits the pending finish reason.
    (let [events (run-stream (str
                              "data: " (json/generate-string
                                        {:choices [{:delta {:role "assistant" :content "Hello"}}]}) "\n\n"
                              "data: " (json/generate-string
                                        {:choices [{:delta {:role "assistant" :content ""}
                                                    :finish_reason "stop"}]}) "\n\n"))
          dones  (filterv #(= :done (:type %)) events)
          done   (first dones)]
      (is (= 1 (count dones))
          "exactly one :done — the EOF flush terminates the stream")
      (is (= :stop (:reason done))
          "the pending finish_reason is flushed to the :done")
      (is (not-any? #(= :error (:type %)) events)
          "no :error — the EOF flush is a clean terminal"))))

(deftest completions-done-sentinel-without-finish-reason-emits-done-test
  (testing "a [DONE] sentinel without a prior finish_reason chunk emits exactly one terminal :done"
    ;; Review 48: flush-pending-chat-finish! guards on the pending finish
    ;; reason, so a [DONE] line with no prior finish_reason chunk no-oped —
    ;; no :done, no :error, turn hangs until the idle timeout. The EOF flush
    ;; (which fires after the [DONE] no-op) now emits :stop.
    (let [events (run-stream (str
                              "data: " (json/generate-string
                                        {:choices [{:delta {:role "assistant" :content "Hello"}}]}) "\n\n"
                              "data: [DONE]\n\n"))
          dones  (filterv #(= :done (:type %)) events)
          done   (first dones)]
      (is (= 1 (count dones))
          "exactly one :done — the EOF flush terminates the stream")
      (is (= :stop (:reason done))
          "no pending finish_reason → the EOF flush emits :stop")
      (is (not-any? #(= :error (:type %)) events)
          "no :error — the EOF flush is a clean terminal"))))

(deftest completions-done-without-usage-chunk-carries-no-usage-test
  (testing "a terminal :done for a stream with no usage chunk carries no :usage"
    ;; Review 48: the openai terminal :done carries usage only when a usage
    ;; chunk was seen. An OpenAI-compatible endpoint that ignores
    ;; stream_options.include_usage (the body always sets it, but local
    ;; proxies / third-party endpoints commonly omit the usage chunk) sends
    ;; no usage chunk, so the flushed :done carries no :usage and handle-done!
    ;; ((map? usage) false) records ZERO usage/cost — the documented
    ;; consequence for usage-omitting endpoints (the review-47 zero-usage
    ;; class on the :openai-completions sibling; the anthropic fix attached
    ;; accumulated usage to the message_stop terminal, but here there IS no
    ;; usage to attach). The existing
    ;; completions-trailing-usage-after-finish-reason-is-preserved-test
    ;; covers the usage-chunk-arrives case; this locks the
    ;; usage-chunk-omitted case end to end.
    (let [events (run-stream (str
                              "data: " (json/generate-string
                                        {:choices [{:delta {:role "assistant" :content "Hello"}}]}) "\n\n"
                              "data: " (json/generate-string
                                        {:choices [{:delta {:role "assistant" :content ""}
                                                    :finish_reason "stop"}]}) "\n\n"
                              "data: [DONE]\n\n"))
          dones  (filterv #(= :done (:type %)) events)
          done   (first dones)]
      (is (= 1 (count dones))
          "exactly one :done — the stream terminates normally")
      (is (nil? (:usage done))
          "no :usage key on the :done — zero usage/cost recorded for a usage-omitting endpoint")
      (is (= :stop (:reason done))
          "the pending finish_reason is flushed"))))

(deftest completions-sse-error-top-level-http-status-kept-test
  (testing "a mid-stream error chunk with a TOP-LEVEL http_status keeps its numeric :http-status"
    ;; Review 51: emit-chat-error! checked (:status chunk) /
    ;; [:error :status] / [:error :http_status] only — the review-47-aligned
    ;; anthropic "error" branch reads those three PLUS top-level
    ;; (:http_status event-data), so an OpenAI-compatible endpoint emitting
    ;; the status under a top-level http_status key
    ;; ({"http_status": 529, "error": {...}}) lost its status on
    ;; :openai-completions: the :error event carried no numeric
    ;; :http-status, downstream retry-error? / provider-error-kind classified
    ;; a transient 5xx/overload as :unknown and it was not auto-retried.
    ;; The extraction now includes the top-level location, mirroring the
    ;; anthropic branch. (Moved here from openai_completions_test.clj for
    ;; the 800-line file-length gate.)
    (let [events (run-stream (str
                              "data: " (json/generate-string
                                        {:http_status 529
                                         :error {:message "The server had an error while processing your request."
                                                 :type "server_error"}}) "\n\n"))
          err    (first (filter #(= :error (:type %)) events))]
      (is (some? err) "SSE error chunk must surface as an :error event")
      (is (= 529 (:http-status err))
          "top-level http_status 529 is kept on the :error event (retryable class)")
      (is (= "The server had an error while processing your request. (status 529)"
             (:error-message err))
          "error message extracted from the chunk's error body (with status suffix)")
      (is (= [:start :error] (mapv :type events))
          "an error-FIRST stream emits :start then the :error terminal (review 52)")
      (is (not-any? #(= :done (:type %)) events)
          "no :done after a mid-stream error — the :error event terminates the turn"))))

(deftest completions-sse-error-first-stream-emits-start-then-error-test
  (testing "an error-FIRST stream (error chunk before any role/content chunk) emits :start then :error"
    ;; Review 52: emit-chat-error! emitted [:error] with no preceding :start
    ;; when the stream errored before producing any output event — the
    ;; review-50 :start-before-terminal fix covered the anthropic "error"
    ;; branch and the terminal :done emitters but not the openai error
    ;; emitter, and the existing error tests never caught it because they
    ;; start with a role/content chunk that triggers :start via the
    ;; non-error path (completions-sse-error-event-emits-error-and-
    ;; terminates-test starts with {:choices [{:delta {:role
    ;; "assistant"}}]}). The error emitter now emits :start first (mirroring
    ;; emit-chat-completion-finish!'s ordering and the anthropic error
    ;; branch), so an error-first stream yields [:start :error] like the
    ;; anthropic transport — the last three-transport asymmetry in the
    ;; review-50 class.
    (let [events (run-stream (str
                              "data: " (json/generate-string
                                        {:error {:message "The server had an error while processing your request."
                                                 :type "server_error"}}) "\n\n"
                              "data: [DONE]\n\n"))
          err    (first (filter #(= :error (:type %)) events))]
      (is (= [:start :error] (mapv :type events))
          "error-first stream emits :start then the :error terminal")
      (is (some? err) "SSE error chunk must surface as an :error event")
      (is (= "The server had an error while processing your request."
             (:error-message err))
          "error message extracted from the chunk's error body")
      (is (not-any? #(= :done (:type %)) events)
          "no :done — the :error event is terminal"))))

(deftest completions-first-read-exception-emits-start-then-error-test
  (testing "a stream-read exception before any output event emits :start then the :error terminal"
    ;; Review 53: the catch block emitted [:error] with no preceding :start
    ;; when the exception fired before any output event (e.g. a connection
    ;; reset on the first read) — the last gap in the review-50/52
    ;; :start-before-terminal class on this transport (emit-chat-error! now
    ;; emits :start first per review 52, but the catch block routes through
    ;; transport/emit-error!, which has no start logic). The catch now emits
    ;; :start once (compare-and-set on stream-started?) before the :error, so
    ;; a first-read exception yields [:start :error] like the in-band error
    ;; chunk path.
    (let [model  (models/get-model :gpt-5)
          convo  (-> (conv/create "sys") (conv/add-user-message "hi"))
          events (atom [])]
      (let [response-fn (fn [_]
                          (throw (ex-info "simulated connection reset"
                                          {:status 503})))
            http-client (http-boundary/nullable [response-fn response-fn])]
        ((:stream openai/provider)
         convo model {:http-boundary http-client
                      :api-key "sk-test"}
         (fn [ev] (swap! events conj ev))))
      (is (= [:start :error] (mapv :type @events))
          "a first-read exception emits :start then the :error terminal")
      (is (= 1 (count (filterv #(= :error (:type %)) @events)))
          "exactly one :error terminal")
      (is (some? (:error-message (first (filterv #(= :error (:type %)) @events))))
          "the exception surfaces as an :error with a message"))))

(deftest completions-eof-balances-open-tool-call-test
  (testing "a tool_calls delta chunk followed by EOF closes the open tool call before :done"
    ;; Review 55: the EOF-level terminal flush emitted :done with an OPEN
    ;; tool index — a tool_calls delta chunk with no finish_reason, no
    ;; [DONE] and no usage chunk (a truncated / non-conforming stream)
    ;; left the turn accumulator with an unclosed tool index when
    ;; handle-done! finalized (probe-verified pre-fix:
    ;; [:start :toolcall-start :toolcall-delta :done] — no :toolcall-end).
    ;; The EOF flush now calls force-start-pending-chat-tools! +
    ;; emit-chat-tool-ends! before the terminal :done, reusing the exact
    ;; helpers the finish_chunk branches call — a truncated stream degrades
    ;; the same way a finish_reason-terminated stream does.
    (let [events (run-stream (str
                              "data: " (json/generate-string
                                        {:choices [{:delta {:role "assistant"
                                                            :tool_calls [{:index 0
                                                                          :id "call_1"
                                                                          :function {:name "get_weather"
                                                                                     :arguments ""}}]}}]}) "\n\n"
                              "data: " (json/generate-string
                                        {:choices [{:delta {:role "assistant"
                                                            :tool_calls [{:index 0
                                                                          :function {:arguments "{\"city\":\"Paris\"}"}}]}}]}) "\n\n"))
          dones  (filterv #(= :done (:type %)) events)]
      (is (= [:start :toolcall-start :toolcall-delta :toolcall-end :done]
             (mapv :type events))
          "the open tool call is balanced with :toolcall-end before the EOF :done")
      (is (= 1 (count dones))
          "exactly one :done — the EOF flush terminates the stream")
      (is (= :stop (:reason (first dones)))
          "no pending finish reason → the EOF flush emits :stop")
      (is (not-any? #(= :error (:type %)) events)
          "no :error — the EOF flush is a clean terminal"))))

(deftest completions-eof-balances-not-yet-started-tool-call-test
  (testing "a tool_calls fragment with a name but no id (never started) is force-started then closed at EOF"
    ;; Review 55: force-start-pending-chat-tools! emits :toolcall-start for
    ;; a pending (not-yet-started) tool entry so the subsequent
    ;; emit-chat-tool-ends! balances it — a tool_calls delta carrying only
    ;; a name (no id, so start-chat-tool-if-ready! could not fire during the
    ;; stream) still gets a balanced :toolcall-start/:toolcall-end pair at
    ;; the EOF terminal instead of an open index.
    (let [events (run-stream (str
                              "data: " (json/generate-string
                                        {:choices [{:delta {:role "assistant"
                                                            :tool_calls [{:index 0
                                                                          :function {:name "get_weather"
                                                                                     :arguments ""}}]}}]}) "\n\n"))
          dones  (filterv #(= :done (:type %)) events)]
      (is (= [:start :toolcall-start :toolcall-end :done]
             (mapv :type events))
          "the not-yet-started tool call is force-started then closed before the EOF :done")
      (is (= 1 (count dones))
          "exactly one :done — the EOF flush terminates the stream"))))

;; ── Open-tool balancing on the error/catch terminals (review 56) ────────────

(deftest completions-error-after-tool-start-balances-open-tool-call-test
  (testing "a mid-stream error chunk after a tool_calls delta closes the open tool call before :error"
    ;; Review 56: review-55's open-tool balancing covered only the :done
    ;; paths (finish_chunk/usage branches + the EOF flush) — emit-chat-error!
    ;; emitted :error with no balancing, so a tool_calls-delta-then-error
    ;; stream yielded [:start :toolcall-start :toolcall-delta :error] with
    ;; the tool call still open at handle-error!. The error emitter now
    ;; calls force-start-pending-chat-tools! + emit-chat-tool-ends! (the
    ;; exact helpers the finish_chunk branches use) before the :error, so
    ;; the accumulator finalizes balanced via the error path too.
    (let [events (run-stream (str
                              "data: " (json/generate-string
                                        {:choices [{:delta {:role "assistant"
                                                            :content ""}}]}) "\n\n"
                              "data: " (json/generate-string
                                        {:choices [{:delta {:role "assistant"
                                                            :tool_calls [{:index 0
                                                                          :id "call_1"
                                                                          :function {:name "get_weather"
                                                                                     :arguments ""}}]}}]}) "\n\n"
                              "data: " (json/generate-string
                                        {:choices [{:delta {:role "assistant"
                                                            :tool_calls [{:index 0
                                                                          :function {:arguments "{\"city\":\"Paris\"}"}}]}}]}) "\n\n"
                              "data: " (json/generate-string
                                        {:error {:message "The server had an error while processing your request."
                                                 :type "server_error"}}) "\n\n"))]
      (is (= [:start :toolcall-start :toolcall-delta :toolcall-end :error]
             (mapv :type events))
          "the open tool call is balanced with :toolcall-end before the :error")
      (is (= 1 (count (filterv #(= :error (:type %)) events)))
          "exactly one :error terminal")
      (is (not-any? #(= :done (:type %)) events)
          "no :done after the mid-stream error — the :error event terminates the turn"))))

(deftest completions-catch-balances-open-tool-call-before-error-test
  (testing "a stream-read exception after a tool_calls delta closes the open tool call before :error"
    ;; Exercise the real SSE parser over a response stream that disconnects
    ;; after the open tool-call bytes have been consumed.
    (let [events (atom [])
          sse (str
               "data: " (json/generate-string
                         {:choices [{:delta {:role "assistant" :content ""}}]}) "\n\n"
               "data: " (json/generate-string
                         {:choices [{:delta {:role "assistant"
                                             :tool_calls [{:index 0
                                                           :id "call_1"
                                                           :function {:name "get_weather"
                                                                      :arguments ""}}]}}]}) "\n\n")
          http (http-boundary/nullable [{:body (throwing-stream-after sse)}])]
      ((:stream openai/provider)
       (-> (conv/create "sys") (conv/add-user-message "hi"))
       (models/get-model :gpt-5) {:api-key "sk-test" :http-boundary http}
       (fn [ev] (swap! events conj ev)))
      (is (= [:start :toolcall-start :toolcall-end :error]
             (mapv :type @events))
          "the open tool call is balanced with :toolcall-end before the catch's :error")
      (is (= 1 (count (filterv #(= :error (:type %)) @events)))
          "exactly one :error terminal"))))
