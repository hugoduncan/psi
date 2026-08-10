(ns psi.ai.providers.anthropic
  "Anthropic Messages transport: streaming (stream-anthropic) and
   non-streaming (execute-anthropic) execution for the :anthropic-messages
   provider, plus the SSE event normalization that drives the turn
   accumulator (open-block balancing, :start emission, usage accumulation,
   error surfacing).

   Request construction (build-request, headers/body shaping) lives in
   psi.ai.providers.anthropic.request. This namespace re-exports
   build-request and transform-messages as the public transport API."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
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
            [psi.ai.providers.http-boundary :as http-boundary]
            [psi.ai.structured-output :as structured-output]))

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
  "Close started content blocks before any terminal event.

   Matching end events use the normal stop-event mapping and sorted indexes,
   so truncation and errors finalize the accumulator deterministically without
   phantom or open blocks. Skipped blocks are never tracked."
  [consume-fn open-blocks]
  (doseq [idx (sort (keys @open-blocks))]
    (stream-events/consume-event! consume-fn
                                  (stream-events/content-block-stop-event (get @open-blocks idx)
                                                                          idx)))
  (reset! open-blocks {}))

(defn- handle-400-response!
  [model options url request response emit-terminal-error! consume-stream-response!]
  (capture/handle-400-response!
   {:prompt-caching-beta anthropic-request/prompt-caching-beta
    :interleaved-thinking-beta anthropic-request/interleaved-thinking-beta
    :oauth-auth-request? (fn [req] (boolean (::oauth? req)))}
   model options url request response emit-terminal-error! consume-stream-response!))
(defn- make-stream-state
  [model options url consume-fn strategy structured-tool-name]
  {:model                      model
   :options                    options
   :url                        url
   :consume-fn                 consume-fn
   :strategy                   strategy
   :structured-tool-name       structured-tool-name
   :block-types                (atom {})
   :open-blocks                (atom {})
   :structured-buffers         (atom {})
   :prompted-json-buffer       (atom "")
   :json-schema-output-buffer  (atom "")
   :structured-result-emitted? (atom false)
   :usage-acc                  (atom {:input-tokens       0
                                      :output-tokens      0
                                      :cache-read-tokens  0
                                      :cache-write-tokens 0})
   :done?                      (atom false)
   :started?                   (atom false)})

(defn- emit-terminal-done!
  [{:keys [model consume-fn strategy open-blocks prompted-json-buffer
           json-schema-output-buffer structured-result-emitted? usage-acc
           done? started?]} reason]
  (reset! done? true)
  (capture/emit-start! consume-fn started?)
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
               :reason reason
               :usage  (usage/usage-with-cost model usage-acc)}))

(defn- emit-terminal-error!
  ([stream-state error-event]
   (emit-terminal-error! stream-state error-event true))
  ([{:keys [model options url consume-fn open-blocks done? started?]}
    error-event
    capture?]
   (when-not @done?
     (reset! done? true)
     (capture/emit-start! consume-fn started?)
     (balance-open-blocks! consume-fn open-blocks)
     (if capture?
       (capture/emit-error! model options url consume-fn error-event)
       (consume-fn error-event)))))

(defn- process-stream-event!
  "Process one parsed Anthropic SSE event against the explicit stream state."
  [{:keys [model options url consume-fn strategy structured-tool-name
           block-types open-blocks structured-buffers prompted-json-buffer
           json-schema-output-buffer usage-acc done? started?]
    :as stream-state}
   event-data]
  (when-not @done?
    (capture/capture-response! model options url event-data)
    (case (:type event-data)
      "message_start"
      (do
        (usage/update-start-usage! usage-acc (get-in event-data [:message :usage]))
        (capture/emit-start! consume-fn started?))

      "content_block_start"
      (let [idx   (:index event-data)
            block (:content_block event-data)]
        (capture/emit-start! consume-fn started?)
        (swap! block-types assoc idx {:type (:type block)
                                      :name (:name block)})
        (when-not (anthropic-structured-output/structured-tool-block?
                   structured-tool-name
                   {:type (:type block) :name (:name block)})
          (let [start-event (stream-events/content-block-start-event idx block)]
            (stream-events/consume-event! consume-fn start-event)
            (when start-event
              (swap! open-blocks assoc idx (:type block))))))

      "content_block_delta"
      (let [idx        (:index event-data)
            block-info (get @block-types idx)
            delta      (:delta event-data)]
        (capture/emit-start! consume-fn started?)
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
              (stream-events/consume-event!
               consume-fn
               (stream-events/content-block-delta-event (:type block-info)
                                                        idx
                                                        delta))))))

      "content_block_stop"
      (let [idx        (:index event-data)
            block-info (get @block-types idx)]
        (capture/emit-start! consume-fn started?)
        (when block-info
          (if (anthropic-structured-output/structured-tool-block?
               structured-tool-name
               block-info)
            (anthropic-structured-output/maybe-emit-structured-result!
             consume-fn
             strategy
             (get @structured-buffers idx))
            (do
              (stream-events/consume-event!
               consume-fn
               (stream-events/content-block-stop-event (:type block-info) idx))
              (swap! open-blocks dissoc idx)))))

      "error"
      (let [status (some (fn [candidate]
                           (and (number? candidate) (>= candidate 400) candidate))
                         [(get-in event-data [:error :http_status])
                          (get-in event-data [:error :status])
                          (:http_status event-data)
                          (:status event-data)])
            error-event (anthropic-error/error-from-response-data
                         {:status           status
                          :body-text        (json/generate-string event-data)
                          :fallback-message "Anthropic stream error"})]
        (emit-terminal-error! stream-state error-event false))

      "message_delta"
      (do
        (usage/update-output-usage! usage-acc (:usage event-data))
        (when-let [reason (get-in event-data [:delta :stop_reason])]
          (emit-terminal-done! stream-state (keyword reason))))

      "message_stop"
      (emit-terminal-done! stream-state :stop)

      nil)))

(defn- consume-stream-response!
  [stream-state response]
  (with-open [reader (io/reader (:body response))]
    (doseq [line (line-seq reader)]
      (when-let [event-data (parse-sse-line line)]
        (process-stream-event! stream-state event-data))))
  (when-not @(get stream-state :done?)
    (emit-terminal-done! stream-state :stop)
    nil))

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
        stream-state       (make-stream-state model
                                              options
                                              url
                                              consume-fn
                                              strategy
                                              structured-tool-name)]
    (try
      (capture/capture-request! model options url request)
      (when strategy
        (consume-fn {:type :structured-output-strategy
                     :structured-output strategy}))
      (let [consume-response!    (partial consume-stream-response! stream-state)
            emit-terminal-error! (partial emit-terminal-error! stream-state)
            response             (capture/stream-response options url request)
            status               (:status response)]
        (cond
          (= 400 status)
          (handle-400-response! model options
                                url
                                request
                                response
                                emit-terminal-error!
                                consume-response!)

          (capture/error-status? status)
          (emit-terminal-error!
           (anthropic-error/response->error response request))

          :else
          (consume-response! response)))
      (catch Exception e
        (emit-terminal-error! stream-state
                              (anthropic-error/exception->error e))))))

(defn- execute-response
  [options url request]
  (http-boundary/post! (http-boundary/boundary options)
                       url
                       (merge request
                              (proxy/request-proxy-options url)
                              {:as :text :throw-exceptions false})))

(defn- non-streaming-content-blocks
  "Map non-streaming response content to canonical assistant blocks in wire
   order. Tool input is JSON-encoded when necessary because downstream tool
   argument parsing consumes the canonical :arguments string."
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
      (let [response (execute-response options url request)]
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
