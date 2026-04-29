(ns psi.ai.providers.anthropic
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clj-http.client :as http]
            [cheshire.core :as json]
            [psi.ai.content :as ai-content]
            [psi.ai.models :as models]
            [psi.ai.providers.anthropic.request-schema :as request-schema]
            [psi.ai.providers.anthropic.request-support :as request-support])
  (:import [java.io InputStream]
           [java.util UUID]))

(def ^:private anthropic-tool-id-pattern
  "Anthropic requires tool_use.id to match ^[a-zA-Z0-9_-]+$."
  #"^[a-zA-Z0-9_-]+$")

(def ^:private anthropic-version "2023-06-01")
(def ^:private claude-code-beta "claude-code-20250219")
(def ^:private oauth-beta "oauth-2025-04-20")
(def ^:private context-management-beta "context-management-2025-06-27")
(def ^:private interleaved-thinking-beta "interleaved-thinking-2025-05-14")
(def ^:private prompt-caching-beta "prompt-caching-2024-07-31")
(def ^:private prompt-caching-scope-beta "prompt-caching-scope-2026-01-05")

(defn- coerce-str
  "Coerce any value to a string; nil and false become \"\"."
  [x]
  (str (or x "")))

(defn- valid-anthropic-tool-id?
  [id]
  (and (string? id)
       (boolean (re-matches anthropic-tool-id-pattern id))))

(defn- fallback-anthropic-tool-id
  []
  (str "tool_" (UUID/randomUUID)))

(defn- ensure-anthropic-tool-id
  "Return an Anthropic-safe tool id (alnum, underscore, hyphen only).
   Generates a fallback when id is nil/blank/invalid."
  [id]
  (let [s (coerce-str id)
        sanitized (-> s
                      (str/replace #"[^a-zA-Z0-9_-]" "_")
                      (str/replace #"_+" "_")
                      (str/replace #"-+" "-")
                      (str/replace #"^[_-]+|[_-]+$" ""))]
    (or (when (valid-anthropic-tool-id? s)
          s)
        (when (valid-anthropic-tool-id? sanitized)
          sanitized)
        (fallback-anthropic-tool-id))))

(defn- canonical-tool-id-fn
  []
  (let [tool-id-map (atom {})]
    (fn [raw-id]
      (let [key (coerce-str raw-id)]
        (or (get @tool-id-map key)
            (let [canonical-id (ensure-anthropic-tool-id raw-id)]
              (swap! tool-id-map assoc key canonical-id)
              canonical-id))))))

(defn- anthropic-cache-control
  [cache-control]
  (when (= :ephemeral (:type cache-control))
    {:type "ephemeral"}))

(defn- with-cache-control
  [payload cache-control]
  (if-let [cache-control* (anthropic-cache-control cache-control)]
    (assoc payload :cache_control cache-control*)
    payload))

(defn- text-block
  ([text]
   {:type "text"
    :text (or text "")})
  ([text cache-control]
   (with-cache-control (text-block text)
     cache-control)))

(defn- user-text-blocks
  [content]
  (->> (ai-content/text-blocks content)
       (map (fn [block]
              (text-block (:text block)
                          (:cache-control block))))
       vec))

(defn- provider-text-blocks
  [content]
  (->> content
       (keep (fn [block]
               (when (= :text (:type block))
                 (text-block (:text block)
                             (:cache-control block)))))
       vec))

(defn- user-content
  [msg]
  (let [content (:content msg)]
    (cond
      (and (map? content)
           (= :text (:kind content)))
      [(text-block (:text content)
                   (:cache-control content))]

      (and (map? content)
           (= :structured (:kind content)))
      (user-text-blocks (:blocks content))

      (and (sequential? content)
           (seq content))
      (provider-text-blocks content)

      :else
      ;; Last-resort coercion: wrap whatever arrived as a plain text block so
      ;; the message list is never empty and the API call can still proceed.
      [(text-block (ai-content/content-text content))])))

(defn- assistant-thinking-block
  [block]
  (cond-> {:type     "thinking"
           :thinking (or (:text block) "")}
    (some? (:signature block)) (assoc :signature (:signature block))))

(defn- assistant-tool-use-block
  [canonical-id block]
  (with-cache-control {:type  "tool_use"
                       :id    (canonical-id (:id block))
                       :name  (:name block)
                       :input (if (map? (:input block))
                                (:input block)
                                {})}
    (:cache-control block)))

(defn- assistant-block
  [canonical-id block]
  (case (:kind block)
    :thinking
    (assistant-thinking-block block)

    :text
    (text-block (:text block)
                (:cache-control block))

    :tool-call
    (assistant-tool-use-block canonical-id block)

    ;; Intentional fallback: unknown block kinds are stringified as plain text
    ;; rather than dropped, so future block types degrade gracefully.
    (text-block (str block))))

(defn- assistant-content
  [msg canonical-id]
  (if (= :structured (get-in msg [:content :kind]))
    (let [{:keys [blocks]} (ai-content/assistant-content-parts msg)]
      (mapv (partial assistant-block canonical-id)
            blocks))
    [(text-block (or (ai-content/content-text msg) ""))]))

(defn- tool-result-block
  [msg canonical-id]
  (cond-> {:type        "tool_result"
           :tool_use_id (canonical-id (:tool-call-id msg))
           :content     (or (ai-content/content-text (:content msg)) "")}
    (:is-error msg) (assoc :is_error true)))

(defn- append-tool-result
  [acc block]
  (let [last-msg (peek acc)]
    (if (and (= "user" (:role last-msg))
             (every? #(= "tool_result" (:type %))
                     (:content last-msg)))
      (conj (pop acc) (update last-msg :content conj block))
      (conj acc {:role "user" :content [block]}))))

(defn- transform-message
  [canonical-id acc msg]
  (case (:role msg)
    :user
    (conj acc {:role "user"
               :content (user-content msg)})

    :assistant
    (conj acc {:role "assistant"
               :content (assistant-content msg canonical-id)})

    :tool-result
    (append-tool-result acc (tool-result-block msg canonical-id))

    acc))

(defn transform-messages
  "Transform conversation messages to Anthropic API format."
  [conversation]
  (let [canonical-id (canonical-tool-id-fn)]
    (reduce (partial transform-message canonical-id)
            []
            (:messages conversation))))

;; Extended thinking: budget_tokens per level. Adaptive thinking (Opus 4.7+): effort string.
(def ^:private thinking-level->budget
  {:off nil :minimal 1024 :low 2048 :medium 8000 :high 16000 :xhigh 32000})

(def ^:private thinking-level->effort
  {:off nil :minimal "low" :low "low" :medium "medium" :high "high" :xhigh "high"})

(defn- adaptive-thinking?
  [model]
  (boolean (:adaptive-thinking model)))

(defn- thinking-param
  "Extended thinking → {:type \"enabled\" :budget_tokens N}.
   Adaptive thinking (Opus 4.7+) → {:type \"adaptive\" :display \"summarized\"}."
  [model options]
  (when (:supports-reasoning model)
    (let [level (:thinking-level options)]
      (if (adaptive-thinking? model)
        (when (get thinking-level->effort level)
          {:type "adaptive" :display "summarized"})
        (when-let [budget (get thinking-level->budget level)]
          {:type "enabled" :budget_tokens budget})))))

(defn- tool-definitions
  [conversation]
  (when (seq (:tools conversation))
    (mapv (fn [tool]
            (with-cache-control
              {:name         (:name tool)
               :description  (:description tool)
               :input_schema (:parameters tool)}
              (:cache-control tool)))
          (:tools conversation))))

(defn- oauth-api-key?
  [api-key]
  (and api-key (str/includes? api-key "sk-ant-oat")))

(defn- cache-control-present?
  [x]
  (cond
    (map? x)
    (or (contains? x :cache-control)
        (some cache-control-present? (vals x)))

    (sequential? x)
    (boolean (some cache-control-present? x))

    :else
    false))

(defn- prompt-caching?
  [conversation]
  (or (cache-control-present? (:system-prompt-blocks conversation))
      (cache-control-present? (:tools conversation))
      (cache-control-present? (:messages conversation))))

(defn- beta-header
  ;; Adaptive thinking (Opus 4.7+) must NOT include interleaved-thinking-beta.
  [oauth? thinking adaptive? prompt-caching?]
  (let [extended-thinking? (and thinking (not adaptive?))
        betas (cond-> []
                oauth?               (into [claude-code-beta
                                            oauth-beta
                                            context-management-beta
                                            prompt-caching-scope-beta])
                extended-thinking?   (conj interleaved-thinking-beta)
                prompt-caching?      (conj prompt-caching-beta))]
    (when (seq betas)
      (->> betas
           distinct
           (str/join ",")))))

(defn- request-headers
  [api-key thinking adaptive? prompt-caching?]
  (let [oauth?       (oauth-api-key? api-key)
        base-headers {"Content-Type"      "application/json"
                      "anthropic-version" anthropic-version}
        headers      (if oauth?
                       (assoc base-headers "Authorization" (str "Bearer " api-key))
                       (assoc base-headers "x-api-key" api-key))
        beta         (beta-header oauth? thinking adaptive? prompt-caching?)]
    (cond-> headers
      beta (assoc "anthropic-beta" beta))))

(defn- text-system-blocks?
  [blocks]
  (and (sequential? blocks)
       (every? (fn [block]
                 (and (map? block)
                      (string? (:text block))))
               blocks)))

(defn- system-blocks->text
  [blocks]
  (apply str (map #(or (:text %) "") blocks)))

(defn- system-prompt-body
  [conversation]
  (let [blocks (:system-prompt-blocks conversation)]
    (cond
      ;; Use block form only when cache controls are present.
      ;; For plain text blocks, send a single string for broad compatibility.
      (and (seq blocks)
           (some :cache-control blocks))
      (mapv (fn [block]
              (with-cache-control {:type "text"
                                   :text (:text block)}
                (:cache-control block)))
            blocks)

      (and (seq blocks)
           (text-system-blocks? blocks))
      (system-blocks->text blocks)

      (some? (:system-prompt conversation))
      (:system-prompt conversation)

      :else
      nil)))

(defn- resolve-api-key
  [options]
  (let [api-key (or (:api-key options) (System/getenv "ANTHROPIC_API_KEY"))]
    (when (str/blank? api-key)
      (throw (ex-info "Missing Anthropic API key. Set ANTHROPIC_API_KEY or login via /login anthropic."
                      {:error-code "auth/missing-api-key"
                       :provider :anthropic})))
    api-key))

(defn build-request
  "Build Anthropic API request map."
  [conversation model options]
  (let [thinking        (thinking-param model options)
        adaptive?       (adaptive-thinking? model)
        effort          (when (and thinking adaptive?)
                          (get thinking-level->effort (:thinking-level options)))
        api-key         (resolve-api-key options)
        tool-defs       (tool-definitions conversation)
        system-body     (system-prompt-body conversation)
        prompt-caching? (prompt-caching? conversation)
        body            (cond-> {:model      (:id model)
                                 :max_tokens (or (:max-tokens options) (:max-tokens model))
                                 :messages   (transform-messages conversation)
                                 :stream     true}
                          (some? system-body) (assoc :system system-body)
                          ;; temperature is incompatible with extended thinking, and is a 400
                          ;; error on adaptive-thinking models (Opus 4.7+) even when thinking=off
                          (and (not thinking)
                               (not adaptive?)) (assoc :temperature (or (:temperature options) 0.7))
                          thinking            (assoc :thinking thinking)
                          ;; adaptive thinking uses output_config.effort instead of budget_tokens
                          effort              (assoc :output_config {:effort effort})
                          (seq tool-defs)     (assoc :tools tool-defs))
        body*           (request-schema/validate-request-body! body)
        base-hdrs       (if (:no-auth-header options)
                          ;; Strip auth headers when explicitly disabled
                          (dissoc (request-headers api-key thinking adaptive? prompt-caching?)
                                  "Authorization" "x-api-key")
                          (request-headers api-key thinking adaptive? prompt-caching?))
        headers         (if-let [custom (:headers options)]
                          (merge base-hdrs custom)
                          base-hdrs)]
    {:headers headers
     :body    (json/generate-string body*)}))

(defn- safe-call!
  [f payload]
  (when (fn? f)
    (try
      (f payload)
      (catch Exception _
        nil))))

(defn- redact-secret
  [value]
  (when (string? value)
    (str "***REDACTED***"
         (when (> (count value) 20)
           (str " (len=" (count value) ")")))))

(defn- redact-authorization
  [value]
  (when (string? value)
    (str "Bearer "
         (redact-secret (str/replace value #"^Bearer\s+" "")))))

(defn- redact-request-headers
  [headers]
  (cond-> headers
    (contains? headers "Authorization")
    (assoc "Authorization"
           (redact-authorization (get headers "Authorization")))

    (contains? headers "x-api-key")
    (assoc "x-api-key"
           (redact-secret (get headers "x-api-key")))))

(defn- capture-request!
  [options url request]
  (safe-call! (:on-provider-request options)
              {:provider :anthropic
               :api :anthropic-messages
               :url url
               :request {:headers (redact-request-headers (:headers request))
                         :body (request-support/parse-json-body-safe (:body request))}}))

(defn- capture-response!
  [options url event]
  (safe-call! (:on-provider-response options)
              {:provider :anthropic
               :api :anthropic-messages
               :url url
               :event event}))

(defn parse-sse-line
  [line]
  (when (str/starts-with? (or line "") "data: ")
    (let [data (subs line 6)]
      (when (not= data "[DONE]")
        (try
          (json/parse-string data true)
          (catch Exception _
            nil))))))

(defn- update-usage!
  [usage-acc usage usage-map]
  (when usage
    (swap! usage-acc
           (fn [acc]
             (reduce-kv (fn [m k usage-key]
                          (assoc m k (or (get usage usage-key) 0)))
                        acc
                        usage-map)))))

(defn- update-start-usage!
  [usage-acc usage]
  (update-usage! usage-acc
                 usage
                 {:input-tokens       :input_tokens
                  :cache-read-tokens  :cache_read_input_tokens
                  :cache-write-tokens :cache_creation_input_tokens}))

(defn- update-output-usage!
  [usage-acc usage]
  (update-usage! usage-acc
                 usage
                 {:output-tokens :output_tokens}))

(defn- usage-with-cost
  [model usage-acc]
  (let [usage @usage-acc
        usage (assoc usage :total-tokens (+ (:input-tokens usage)
                                            (:output-tokens usage)
                                            (:cache-read-tokens usage)
                                            (:cache-write-tokens usage)))]
    (assoc usage :cost (models/calculate-cost model usage))))

(defn- content-block-start-event
  [idx block]
  (case (:type block)
    "tool_use"
    {:type          :toolcall-start
     :content-index idx
     :id            (:id block)
     :name          (:name block)}

    "thinking"
    {:type          :thinking-start
     :content-index idx
     :thinking      (:thinking block)
     :signature     (:signature block)}

    {:type          :text-start
     :content-index idx}))

(defn- content-block-delta-event
  [btype idx delta]
  (case btype
    "tool_use"
    (when-let [json-delta (:partial_json delta)]
      {:type          :toolcall-delta
       :content-index idx
       :delta         json-delta})

    "thinking"
    (cond
      (some? (:signature delta))
      {:type          :thinking-signature-delta
       :content-index idx
       :signature     (:signature delta)}

      :else
      (when-let [text (or (:thinking delta) (:text delta))]
        {:type          :thinking-delta
         :content-index idx
         :delta         text}))

    (when-let [text (:text delta)]
      {:type          :text-delta
       :content-index idx
       :delta         text})))

(defn- content-block-stop-event
  [btype idx]
  {:type          (if (= "tool_use" btype)
                    :toolcall-end
                    :text-end)
   :content-index idx})

(defn- consume-event!
  [consume-fn event]
  (when event
    (consume-fn event)))

(defn- body->text
  [body]
  (try
    (cond
      ;; Explicit nil → nil (not "") so callers can use (seq body-text) to
      ;; distinguish "no body" from "empty body string".
      (nil? body) nil
      (string? body) body
      (instance? InputStream body) (slurp (io/reader body))
      :else (str body))
    (catch Exception _ nil)))

(defn- parse-json-text-safe
  [text]
  (when (seq text)
    (try
      (json/parse-string text true)
      (catch Exception _
        nil))))

(defn- parsed-error-message
  [parsed-body]
  (or (get-in parsed-body [:error :message])
      (get-in parsed-body [:message])))

(defn- request-id-from-headers
  [headers]
  (or (get headers "request-id")
      (get headers "Request-Id")
      (get headers "x-request-id")
      (get headers "X-Request-Id")
      (get headers "X-Request-ID")))

(defn- meaningful-error-message?
  [s]
  (and (string? s)
       (not (str/blank? s))
       (not (contains? #{"Error" "error" "Exception"} s))))

(defn- fallback-status-message
  [status]
  (case status
    400 "Anthropic rejected the request"
    401 "Anthropic authentication failed"
    403 "Anthropic authorization failed"
    404 "Anthropic endpoint not found"
    429 "Anthropic rate limit exceeded"
    500 "Anthropic server error"
    502 "Anthropic gateway error"
    503 "Anthropic service unavailable"
    "Anthropic request failed"))

(defn- oauth-auth-request?
  [request]
  (let [headers (or (:headers request) {})
        auth    (or (get headers "Authorization")
                    (get headers "authorization"))]
    (and (string? auth)
         (str/starts-with? auth "Bearer "))))

(defn- request-diagnostic-hint
  [request]
  (when (map? request)
    (let [parsed         (request-support/parse-json-body-safe (:body request))
          model-id       (when (map? parsed) (:model parsed))
          beta           (get-in request [:headers "anthropic-beta"])
          message-count  (when (map? parsed) (count (or (:messages parsed) [])))
          tool-count     (when (map? parsed) (count (or (:tools parsed) [])))
          parts          (cond-> []
                           (string? model-id)      (conj (str "model=" model-id))
                           (string? beta)          (conj (str "anthropic-beta=" beta))
                           (number? message-count) (conj (str "messages=" message-count))
                           (number? tool-count)    (conj (str "tools=" tool-count))
                           (oauth-auth-request? request) (conj "auth=oauth"))]
      (when (seq parts)
        (str " request{" (str/join ", " parts) "}")))))

(defn- augment-400-message
  "Append diagnostic context to a generic 400 base-msg when Anthropic returns
   no actionable error detail. Only applied when base-msg is the fallback
   'Anthropic rejected the request' string."
  [base-msg body-text oauth? request]
  (if (= base-msg "Anthropic rejected the request")
    (str base-msg
         " ("
         (if (str/blank? body-text)
           "no error body returned"
           "provider response omitted actionable details")
         "; possible causes: model access, unsupported beta header, or invalid request payload"
         (when oauth?
           "; oauth token in use")
         ")"
         (or (request-diagnostic-hint request) ""))
    base-msg))

(defn- base-error-message
  [{:keys [status fallback-message parsed-body]}]
  (or (when-let [parsed-msg (some-> parsed-body parsed-error-message)]
        (when (meaningful-error-message? parsed-msg)
          parsed-msg))
      (when (meaningful-error-message? fallback-message)
        fallback-message)
      (fallback-status-message status)))

(defn- error-message
  [{:keys [status headers body-text fallback-message request parsed-body]}]
  (let [base-msg (base-error-message {:status status
                                      :body-text body-text
                                      :fallback-message fallback-message
                                      :parsed-body parsed-body})
        base-msg (cond-> base-msg
                   (= 400 status) (augment-400-message body-text
                                                       (oauth-auth-request? request)
                                                       request))]
    (str base-msg
         (when status
           (str " (status " status ")"))
         (when-let [req-id (request-id-from-headers headers)]
           (str " [request-id " req-id "]")))))

(defn- error-from-response-data
  [{:keys [status headers body-text fallback-message request]}]
  (let [parsed-body (parse-json-text-safe body-text)]
    (cond-> {:type :error
             :error-message (error-message {:status status
                                            :headers headers
                                            :body-text body-text
                                            :fallback-message fallback-message
                                            :request request
                                            :parsed-body parsed-body})
             :headers headers}
      status          (assoc :http-status status)
      (seq body-text) (assoc :body-text body-text)
      parsed-body     (assoc :body parsed-body))))

(defn- error-context
  ([body headers]
   {:headers headers
    :body-text (body->text body)})
  ([body headers request]
   (assoc (error-context body headers)
          :request request)))

(defn- exception->error
  [e]
  (let [data (ex-data e)]
    (error-from-response-data
     (merge (error-context (:body data) (:headers data))
            {:status (:status data)
             :fallback-message (or (ex-message e) (str e))}))))

(defn- response->error
  [response request]
  (error-from-response-data
   (merge (error-context (:body response) (:headers response) request)
          {:status (:status response)})))

(defn- stream-response
  [url request]
  (http/post url (merge request {:as :stream :throw-exceptions false})))

(defn- error-status?
  [status]
  (and (number? status)
       (>= status 400)))

(defn- emit-error!
  [options url consume-fn err]
  (capture-response! options url err)
  (consume-fn err))

(defn- consume-retry-response!
  [options url consume-fn consume-stream-response! retry-request]
  (capture-request! options url retry-request)
  (let [retry-response (stream-response url retry-request)
        retry-status   (:status retry-response)]
    (if (error-status? retry-status)
      (emit-error! options url consume-fn
                   (response->error retry-response retry-request))
      (consume-stream-response! retry-response))))

(defn- handle-400-response!
  [options url request response consume-fn consume-stream-response!]
  (if-let [fallback (request-support/fallback-request-for-400
                     request
                     {:prompt-caching-beta prompt-caching-beta
                      :interleaved-thinking-beta interleaved-thinking-beta
                      :oauth-auth-request? oauth-auth-request?})]
    (let [first-error (response->error response request)]
      (capture-response! options url (assoc first-error
                                            :retrying-with-compatibility-fallback true
                                            :retry-fallback-steps (:steps fallback)))
      (consume-retry-response! options
                               url
                               consume-fn
                               consume-stream-response!
                               (:request fallback)))
    (emit-error! options url consume-fn
                 (response->error response request))))

(defn stream-anthropic
  "Stream response from Anthropic API."
  [conversation model options consume-fn]
  (let [url         (str (:base-url model) "/v1/messages")
        request     (build-request conversation model options)
        block-types (atom {})
        usage-acc   (atom {:input-tokens       0
                           :output-tokens      0
                           :cache-read-tokens  0
                           :cache-write-tokens 0})
        done?       (atom false)]
    (try
      (capture-request! options url request)
      (letfn [(consume-stream-response! [response]
                (with-open [reader (io/reader (:body response))]
                  (doseq [line (line-seq reader)]
                    (when-let [event-data (parse-sse-line line)]
                      (capture-response! options url event-data)
                      (case (:type event-data)
                        "message_start"
                        (do
                          (update-start-usage! usage-acc (get-in event-data [:message :usage]))
                          (consume-fn {:type :start}))

                        "content_block_start"
                        (let [idx   (:index event-data)
                              block (:content_block event-data)]
                          (swap! block-types assoc idx (:type block))
                          (consume-fn (content-block-start-event idx block)))

                        "content_block_delta"
                        (consume-event! consume-fn
                                        (content-block-delta-event (get @block-types (:index event-data))
                                                                   (:index event-data)
                                                                   (:delta event-data)))

                        "content_block_stop"
                        (consume-fn (content-block-stop-event (get @block-types (:index event-data))
                                                              (:index event-data)))

                        "message_delta"
                        (do
                          (update-output-usage! usage-acc (:usage event-data))
                          (when-let [reason (get-in event-data [:delta :stop_reason])]
                            (reset! done? true)
                            (consume-fn {:type   :done
                                         :reason (keyword reason)
                                         :usage  (usage-with-cost model usage-acc)})))

                        "message_stop"
                        (when-not @done?
                          (consume-fn {:type :done :reason :stop}))

                        nil)))))]
        (let [response (stream-response url request)
              status   (:status response)]
          (cond
            (= 400 status)
            (handle-400-response! options
                                  url
                                  request
                                  response
                                  consume-fn
                                  consume-stream-response!)

            (error-status? status)
            (emit-error! options url consume-fn
                         (response->error response request))

            :else
            (consume-stream-response! response))))
      (catch Exception e
        (let [err (exception->error e)]
          (capture-response! options url err)
          (consume-fn err))))))

(def provider
  {:name   :anthropic
   :stream stream-anthropic})
