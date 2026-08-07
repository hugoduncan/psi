(ns psi.ai.providers.anthropic
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clj-http.client :as http]
            [cheshire.core :as json]
            [psi.ai.models :as models]
            [psi.ai.proxy :as proxy]
            [psi.ai.providers.anthropic.error :as anthropic-error]
            [psi.ai.providers.anthropic.message-transform :as message-transform]
            [psi.ai.providers.anthropic.request-schema :as request-schema]
            [psi.ai.providers.anthropic.request-support :as anthropic-request-support]
            [psi.ai.providers.anthropic.structured-output :as anthropic-structured-output]
            [psi.ai.providers.request-support :as request-support]
            [psi.ai.structured-output :as structured-output]))

(def ^:private anthropic-version "2023-06-01")
(def ^:private claude-code-beta "claude-code-20250219")
;; OAuth requests present as the Claude Code CLI (see claude-code-system-prompt).
(def ^:private claude-code-version "2.1.75")
(def ^:private oauth-beta "oauth-2025-04-20")
(def ^:private context-management-beta "context-management-2025-06-27")
(def ^:private interleaved-thinking-beta "interleaved-thinking-2025-05-14")
(def ^:private prompt-caching-beta "prompt-caching-2024-07-31")
(def ^:private prompt-caching-scope-beta "prompt-caching-scope-2026-01-05")
;; Anthropic OAuth (subscription) tokens are authorized only for Claude Code.
;; The API rejects (HTTP 429 rate_limit_error) any OAuth request whose first
;; system block is not this exact identity string.
(def ^:private claude-code-system-prompt
  "You are Claude Code, Anthropic's official CLI for Claude.")
(defn- anthropic-cache-control
  [cache-control]
  (when (= :ephemeral (:type cache-control))
    {:type "ephemeral"}))

(defn- with-cache-control
  [payload cache-control]
  (if-let [cache-control* (anthropic-cache-control cache-control)]
    (assoc payload :cache_control cache-control*)
    payload))

(def transform-messages message-transform/transform-messages)

;; Extended thinking: budget_tokens per level. Adaptive thinking (Opus 4.7+): effort string.
(def ^:private thinking-level->budget
  {:off nil :minimal 1024 :low 2048 :medium 8000 :high 16000 :xhigh 32000})

(def ^:private thinking-level->effort
  {:off nil :minimal "low" :low "low" :medium "medium" :high "high" :xhigh "highest"})

(def ^:private effort-override->effort
  {:low "low" :medium "medium" :high "high" :xhigh "highest"})

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

(defn- builtin-anthropic?
  "True for built-in Anthropic catalog models: not tagged `:custom?` (custom
   models.edn models carry `:custom? true` from `expand-model`) and provider
   nil or `:anthropic`. OAuth content-sniffing (`sk-ant-oat` keys) and the
   `ANTHROPIC_API_KEY` env fallback apply only to these: a custom
   `:anthropic-messages` provider — even one literally named \"anthropic\"
   — whose configured key merely resembles an OAuth token must still use
   plain x-api-key auth, and must never receive the Claude Code OAuth
   headers/system prompt (reviews 11/14)."
  [model]
  (request-support/builtin? model :anthropic))

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
  [oauth? thinking adaptive? prompt-caching? structured-output? speed-mode]
  (let [extended-thinking? (and thinking (not adaptive?))
        betas (cond-> []
                oauth?               (into [claude-code-beta
                                            oauth-beta
                                            context-management-beta
                                            prompt-caching-scope-beta])
                extended-thinking?   (conj interleaved-thinking-beta)
                prompt-caching?      (conj prompt-caching-beta)
                (= :fast speed-mode) (conj "fast-mode-2026-02-01")
                structured-output?   (conj anthropic-structured-output/json-schema-output-beta))]
    (when (seq betas)
      (->> betas
           distinct
           (str/join ",")))))

(defn- request-headers
  [api-key oauth? thinking adaptive? prompt-caching? structured-output? speed-mode]
  (let [base-headers {"Content-Type"      "application/json"
                      "anthropic-version" anthropic-version}
        headers      (if oauth?
                       (assoc base-headers
                              "Authorization" (str "Bearer " api-key)
                              ;; Present as the Claude Code CLI, which OAuth tokens are scoped to.
                              "user-agent" (str "claude-cli/" claude-code-version)
                              "x-app" "cli")
                       (assoc base-headers "x-api-key" api-key))
        beta         (beta-header oauth? thinking adaptive? prompt-caching? structured-output? speed-mode)]
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

(defn- prepend-claude-code-system
  "Prepend the Claude Code identity as the first system block for OAuth
   requests. Anthropic OAuth tokens are authorized only for Claude Code;
   the API rejects requests whose first system block is not this identity.
   No-op for API-key auth."
  [oauth? system-body]
  (if-not oauth?
    system-body
    (let [head {:type "text" :text claude-code-system-prompt}]
      (cond
        (nil? system-body)        [head]
        (string? system-body)     [head {:type "text" :text system-body}]
        (sequential? system-body) (into [head] system-body)
        :else                     [head]))))

(def ^:private anthropic-api-key-config
  {:builtin-provider    :anthropic
   :env-var             "ANTHROPIC_API_KEY"
   :builtin-missing-msg "Missing Anthropic API key. Set ANTHROPIC_API_KEY or login via /login anthropic."})

(defn- request-body
  [conversation model options stream? oauth?]
  (let [structured-request (structured-output/structured-output-request options)
        strategy           (structured-output/select-strategy model structured-request)
        thinking           (thinking-param model options)
        adaptive?          (adaptive-thinking? model)
        effort             (when (and thinking adaptive?)
                             (or (get effort-override->effort (:effort-override options))
                                 (get thinking-level->effort (:thinking-level options))))
        fallback-request   (when (= :prompted-json (:strategy strategy))
                             structured-request)
        tool-defs          (tool-definitions conversation)
        structured-tool    (when (anthropic-structured-output/forced-tool-mechanism? strategy)
                             (anthropic-structured-output/structured-tool structured-request tool-defs))
        tool-defs          (cond-> (vec (or tool-defs []))
                             structured-tool (conj structured-tool))
        system-body        (prepend-claude-code-system
                            oauth? (system-prompt-body conversation))]
    (cond-> {:model      (:id model)
             :max_tokens (or (:max-tokens options) (:max-tokens model))
             :messages   (transform-messages conversation fallback-request)}
      stream? (assoc :stream true)
      (some? system-body) (assoc :system system-body)
      (= :fast (:speed-mode options)) (assoc :speed "fast")
      ;; temperature is incompatible with extended thinking, and is a 400
      ;; error on adaptive-thinking models (Opus 4.7+) even when thinking=off
      (and (not thinking)
           (not adaptive?)) (assoc :temperature (or (:temperature options) 0.7))
      thinking            (assoc :thinking thinking)
      ;; adaptive thinking uses output_config.effort instead of budget_tokens
      effort              (assoc :output_config {:effort effort})
      (anthropic-structured-output/json-schema-output-mechanism? strategy)
      (assoc :output_format (anthropic-structured-output/output-format structured-request))
      (seq tool-defs)     (assoc :tools tool-defs)
      structured-tool     (assoc :tool_choice {:type "tool"
                                               :name (:name structured-tool)}))))

(defn build-request
  "Build Anthropic API request map."
  ([conversation model options]
   (build-request conversation model options true))
  ([conversation model options stream?]
   (let [structured-request (structured-output/structured-output-request options)
         strategy           (structured-output/select-strategy model structured-request)
         thinking           (thinking-param model options)
         adaptive?          (adaptive-thinking? model)
         ;; Keyless logic is shared with the OpenAI transports via
         ;; request-support/no-auth?: keyless on explicit :no-auth-header
         ;; (e.g. :auth-header? false local servers) or a recognized auth
         ;; header (x-api-key / authorization) among custom :headers with no
         ;; configured :api-key. Incidental custom headers (e.g. X-Client) do
         ;; NOT imply keyless: with a blank configured key such a request
         ;; fast-fails with the clear "Missing API key" error instead of
         ;; silently sending a keyless request (provider-side 401).
         no-auth?           (request-support/no-auth? options)
         api-key            (when-not no-auth?
                              (request-support/resolve-api-key model options anthropic-api-key-config))
         oauth?             (and (builtin-anthropic? model)
                                 (oauth-api-key? api-key))
         prompt-caching?    (prompt-caching? conversation)
         json-schema-output? (anthropic-structured-output/json-schema-output-mechanism? strategy)
         body               (request-body conversation model options stream? oauth?)
         body*              (request-schema/validate-request-body! body)
         base-hdrs          (cond-> (request-headers api-key
                                                     oauth?
                                                     thinking
                                                     adaptive?
                                                     prompt-caching?
                                                     json-schema-output?
                                                     (:speed-mode options))
                              ;; Strip auth headers when no key is used
                              no-auth? (dissoc "Authorization" "x-api-key"))
         headers            (if-let [custom (:headers options)]
                              (merge base-hdrs custom)
                              base-hdrs)]
     {:headers headers
      :body    (json/generate-string body*)})))

(defn- safe-call!
  [f payload]
  (when (fn? f)
    (try
      (f payload)
      (catch Exception _
        nil))))

(defn- redact-request-headers
  [headers]
  (request-support/redact-headers
   headers
   [["Authorization" request-support/redact-authorization]
    ["x-api-key" request-support/redact-secret]]))

(defn- capture-provider-id
  [model]
  (or (:provider model) :anthropic))

(defn- capture-request!
  [model options url request]
  (safe-call! (:on-provider-request options)
              {:provider (capture-provider-id model)
               :api :anthropic-messages
               :url url
               :request {:headers (redact-request-headers (:headers request))
                         :body (anthropic-request-support/parse-json-body-safe (:body request))}}))

(defn- capture-response!
  [model options url event]
  (safe-call! (:on-provider-response options)
              {:provider (capture-provider-id model)
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

(defn- stream-response
  [url request]
  (http/post url (merge request
                        (proxy/request-proxy-options url)
                        {:as :stream :throw-exceptions false})))

(defn- error-status?
  [status]
  (and (number? status)
       (>= status 400)))

(defn- emit-error!
  [model options url consume-fn err]
  (capture-response! model options url err)
  (consume-fn err))

(defn- consume-retry-response!
  [model options url consume-fn consume-stream-response! retry-request]
  (capture-request! model options url retry-request)
  (let [retry-response (stream-response url retry-request)
        retry-status   (:status retry-response)]
    (if (error-status? retry-status)
      (emit-error! model options url consume-fn
                   (anthropic-error/response->error retry-response retry-request))
      (consume-stream-response! retry-response))))

(defn- handle-400-response!
  [model options url request response consume-fn consume-stream-response!]
  (if-let [fallback (anthropic-request-support/fallback-request-for-400
                     request
                     {:prompt-caching-beta prompt-caching-beta
                      :interleaved-thinking-beta interleaved-thinking-beta
                      :oauth-auth-request? anthropic-error/oauth-auth-request?})]
    (let [first-error (anthropic-error/response->error response request)]
      (capture-response! model options url (assoc first-error
                                                  :retrying-with-compatibility-fallback true
                                                  :retry-fallback-steps (:steps fallback)))
      (consume-retry-response! model options
                               url
                               consume-fn
                               consume-stream-response!
                               (:request fallback)))
    (emit-error! model options url consume-fn
                 (anthropic-error/response->error response request))))

(defn stream-anthropic
  "Stream response from Anthropic API."
  [conversation model options consume-fn]
  (let [url                (str (:base-url model) "/v1/messages")
        structured-request (structured-output/structured-output-request options)
        strategy           (structured-output/select-strategy model structured-request)
        request            (build-request conversation model options)
        request-body       (anthropic-request-support/parse-json-body-safe (:body request))
        structured-tool-name (anthropic-structured-output/structured-tool-name-from-request
                              strategy
                              request-body)
        block-types        (atom {})
        structured-buffers (atom {})
        prompted-json-buffer (atom "")
        json-schema-output-buffer (atom "")
        structured-result-emitted? (atom false)
        usage-acc   (atom {:input-tokens       0
                           :output-tokens      0
                           :cache-read-tokens  0
                           :cache-write-tokens 0})
        done?       (atom false)]
    (try
      (capture-request! model options url request)
      (when strategy
        (consume-fn {:type :structured-output-strategy
                     :structured-output strategy}))
      (letfn [(consume-stream-response! [response]
                (with-open [reader (io/reader (:body response))]
                  (doseq [line (line-seq reader)]
                    (when-let [event-data (parse-sse-line line)]
                      (capture-response! model options url event-data)
                      (case (:type event-data)
                        "message_start"
                        (do
                          (update-start-usage! usage-acc (get-in event-data [:message :usage]))
                          (consume-fn {:type :start}))

                        "content_block_start"
                        (let [idx   (:index event-data)
                              block (:content_block event-data)]
                          (swap! block-types assoc idx {:type (:type block)
                                                        :name (:name block)})
                          (when-not (anthropic-structured-output/structured-tool-block?
                                     structured-tool-name
                                     {:type (:type block) :name (:name block)})
                            (consume-fn (content-block-start-event idx block))))

                        "content_block_delta"
                        (let [idx (:index event-data)
                              block-info (get @block-types idx)
                              delta (:delta event-data)]
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
                              (consume-event! consume-fn
                                              (content-block-delta-event (:type block-info)
                                                                         idx
                                                                         delta)))))

                        "content_block_stop"
                        (let [idx (:index event-data)
                              block-info (get @block-types idx)]
                          (if (anthropic-structured-output/structured-tool-block?
                               structured-tool-name
                               block-info)
                            (anthropic-structured-output/maybe-emit-structured-result!
                             consume-fn
                             strategy
                             (get @structured-buffers idx))
                            (consume-fn (content-block-stop-event (:type block-info)
                                                                  idx))))

                        "message_delta"
                        (do
                          (update-output-usage! usage-acc (:usage event-data))
                          (when-let [reason (get-in event-data [:delta :stop_reason])]
                            (reset! done? true)
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
                                         :usage  (usage-with-cost model usage-acc)})))

                        "message_stop"
                        (when-not @done?
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
                          (consume-fn {:type :done :reason :stop}))

                        nil)))))]
        (let [response (stream-response url request)
              status   (:status response)]
          (cond
            (= 400 status)
            (handle-400-response! model options
                                  url
                                  request
                                  response
                                  consume-fn
                                  consume-stream-response!)

            (error-status? status)
            (emit-error! model options url consume-fn
                         (anthropic-error/response->error response request))

            :else
            (consume-stream-response! response))))
      (catch Exception e
        (let [err (anthropic-error/exception->error e)]
          (capture-response! model options url err)
          (consume-fn err))))))

(defn- execute-response
  [url request]
  (http/post url (merge request
                        (proxy/request-proxy-options url)
                        {:as :text :throw-exceptions false})))

(defn- text-content-blocks
  [content]
  (->> content
       (keep (fn [block]
               (when (= "text" (:type block))
                 {:type :text :text (or (:text block) "")})))
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
                                         :content (text-content-blocks (:content body))
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
        request            (build-request conversation model options false)]
    (try
      (capture-request! model options url request)
      (let [response (execute-response url request)]
        (if (error-status? (:status response))
          (anthropic-error/response->error response request)
          (let [body (json/parse-string (:body response) true)]
            (capture-response! model options url body)
            (response->assistant-message model body strategy))))
      (catch Exception e
        (anthropic-error/exception->error e)))))

(def provider
  {:name    :anthropic
   :stream  stream-anthropic
   :execute execute-anthropic})
