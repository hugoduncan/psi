(ns psi.ai.providers.openai-completions-stream-test
  "Review-48 stream follow-ups for the :openai-completions transport: the
  EOF-level terminal flush (finish_reason-chunk-then-EOF and
  [DONE]-without-finish_reason both terminate with exactly one :done instead
  of hanging) and the zero-usage :done semantics for endpoints that omit the
  usage chunk. Split out of openai_test.clj / openai_completions_test.clj to
  stay under the 800-line file-length gate."
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
