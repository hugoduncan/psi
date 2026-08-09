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
   [clj-http.client :as http]
   [psi.ai.conversation :as conv]
   [psi.ai.models :as models]
   [psi.ai.providers.openai :as openai])
  (:import [java.io ByteArrayInputStream]))

(defn- stream-body [s]
  (ByteArrayInputStream. (.getBytes s "UTF-8")))

(defn- run-stream [sse]
  (let [model  (models/get-model :gpt-5)
        convo  (-> (conv/create "sys") (conv/add-user-message "hi"))
        events (atom [])]
    (with-redefs [http/post (fn [_url _req]
                              {:body (stream-body sse)})]
      ((:stream openai/provider)
       convo model {:api-key "sk-test"}
       (fn [ev] (swap! events conj ev))))
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
      (with-redefs [http/post (fn [_url _req]
                                (throw (ex-info "simulated connection reset"
                                                {:status 503})))]
        ((:stream openai/provider)
         convo model {:api-key "sk-test"}
         (fn [ev] (swap! events conj ev))))
      (is (= [:start :error] (mapv :type @events))
          "a first-read exception emits :start then the :error terminal")
      (is (= 1 (count (filterv #(= :error (:type %)) @events)))
          "exactly one :error terminal")
      (is (some? (:error-message (first (filterv #(= :error (:type %)) @events))))
          "the exception surfaces as an :error with a message"))))
