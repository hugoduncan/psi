(ns psi.ai.providers.openai.codex-responses
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [cheshire.core :as json]
            [psi.ai.models :as models]
            [psi.ai.providers.openai.content :as content]
            [psi.ai.providers.openai.reasoning :as reasoning]
            [psi.ai.providers.openai.codex-structured-output :as codex-structured-output]
            [psi.ai.providers.openai.transport :as transport]
            [psi.ai.structured-output :as structured-output]))

(defn- resolve-codex-url
  [base-url]
  (let [raw        (if (str/blank? base-url) "https://chatgpt.com/backend-api" base-url)
        normalized (str/replace raw #"/+$" "")]
    (cond
      (str/ends-with? normalized "/codex/responses") normalized
      (str/ends-with? normalized "/codex")           (str normalized "/responses")
      :else                                           (str normalized "/codex/responses"))))

(defn- getenv
  [k]
  (System/getenv k))

(defn- auth-header?
  "True when a header name is a recognized auth header (case-insensitive)."
  [header]
  (contains? #{"x-api-key" "authorization"}
             (str/lower-case (name header))))

(defn- resolve-api-key
  "Resolve the API key for a Codex request, scoped to the request's provider.

   Built-in OpenAI models (`:provider` nil or `:openai`) fall back to the
   `OPENAI_API_KEY` env var. Custom `:openai-codex-responses` providers never
   fall back to that env var: a nil/blank configured key is an error, so a
   custom provider's request can never silently send the user's OpenAI key to
   a third-party endpoint (the same provider-scoped resolution the
   `:anthropic-messages` and `:openai-completions` transports apply — see
   anthropic/resolve-api-key and chat_completions/resolve-api-key; review 13
   closed the codex gap left open by reviews 3/10).

   When `:no-auth-header` is set (e.g. `:auth-header? false` local servers),
   no key is required: the caller omits the Authorization header anyway, so
   this returns nil instead of failing. (Headers-only auth without
   `:no-auth-header` is handled by `build-codex-request` — it skips this
   function entirely when a recognized auth header is present among custom
   `:headers`.)"
  [model options]
  (when-not (:no-auth-header options)
    (let [provider (:provider model)
          openai?  (or (nil? provider) (= :openai provider))
          api-key  (:api-key options)
          api-key  (if (and openai? (str/blank? api-key))
                     (getenv "OPENAI_API_KEY")
                     api-key)]
      (when (str/blank? api-key)
        (if openai?
          (throw (ex-info "Missing OpenAI API key. Set OPENAI_API_KEY or login via /login openai."
                          {:error-code "auth/missing-api-key"
                           :provider :openai}))
          ;; OAuth /login only exists for built-in providers, so custom
          ;; providers must not hint at it — the remedy is models.edn :auth.
          ;; The suggested env var name normalizes kebab-case provider keys
          ;; (- → _) so the suggestion is a usable shell env var name.
          (throw (ex-info (str "Missing API key for provider " (name provider)
                               ". Configure the provider's :auth {:api-key ...} in models.edn"
                               " (e.g. \"env:" (-> (name provider)
                                                   (str/replace "-" "_")
                                                   str/upper-case)
                               "_API_KEY\").")
                          {:error-code "auth/missing-api-key"
                           :provider provider}))))
      api-key)))

(defn- assistant-content->codex-items
  [msg]
  (if (= :structured (get-in msg [:content :kind]))
    (let [{:keys [text tool-calls]} (content/assistant-structured-content msg)
          text-item                 (when (seq text)
                                      (content/codex-message-item text))
          tool-items                (map content/codex-tool-call-item tool-calls)]
      (vec (concat (when text-item [text-item]) tool-items)))
    (let [text (get-in msg [:content :text] "")]
      (if (seq text)
        [(content/codex-message-item text)]
        []))))

(defn- tool-result->codex-item
  [msg]
  (let [raw-id  (or (:tool-call-id msg) "")
        call-id (or (first (str/split raw-id #"\|" 2))
                    (content/new-call-id))]
    {"type"    "function_call_output"
     "call_id" call-id
     "output"  (content/tool-result-text msg)}))

(defn codex-input-messages
  ([conversation]
   (codex-input-messages conversation nil))
  ([conversation fallback-request]
   (let [last-user-index (when fallback-request
                           (last (keep-indexed (fn [idx msg]
                                                 (when (= :user (:role msg)) idx))
                                               (:messages conversation))))]
     (->> (:messages conversation)
          (map-indexed vector)
          (mapcat (fn [[idx msg]]
                    (case (:role msg)
                      :user
                      (let [text (content/user-message-text msg)]
                        [{"role"    "user"
                          "content" [{"type" "input_text"
                                      "text" (if (= idx last-user-index)
                                               (structured-output/append-fallback-instructions-to-text
                                                text fallback-request)
                                               text)}]}])
                      :assistant
                      (assistant-content->codex-items msg)
                      :tool-result
                      [(tool-result->codex-item msg)]
                      [])))
          vec))))

(defn- codex-tools
  [conversation]
  (when (seq (:tools conversation))
    (mapv (fn [t]
            {"type"        "function"
             "name"        (:name t)
             "description" (:description t)
             "parameters"  (:parameters t)
             "strict"      nil})
          (:tools conversation))))

(defn codex-reasoning
  [model options]
  (when-let [effort (reasoning/reasoning-effort model options)]
    {"effort" effort
     "summary" "auto"}))

(defn- codex-usage->usage-map
  [usage]
  (let [input-tokens  (or (:input_tokens usage) 0)
        output-tokens (or (:output_tokens usage) 0)
        cached        (or (get-in usage [:input_tokens_details :cached_tokens]) 0)
        total         (or (:total_tokens usage) (+ input-tokens output-tokens))]
    {:input-tokens       (max 0 (- input-tokens cached))
     :output-tokens      output-tokens
     :cache-read-tokens  cached
     :cache-write-tokens 0
     :total-tokens       total}))

(defn- codex-status->reason
  [status]
  (case status
    "incomplete" :length
    "failed"     :error
    "cancelled"  :error
    :stop))

(defn build-codex-request
  [conversation model options]
  (let [;; No auth key is required when the request is explicitly keyless
        ;; (:no-auth-header, e.g. :auth-header? false local servers) or when
        ;; custom :headers supply a recognized auth header (x-api-key /
        ;; authorization) without a configured :api-key. Incidental custom
        ;; headers (e.g. X-Client) do NOT imply keyless: with a blank
        ;; configured key such a request fast-fails with the clear
        ;; "Missing API key" error instead of silently sending the global
        ;; OPENAI_API_KEY to a custom endpoint — consistent with the
        ;; :anthropic-messages and :openai-completions transports (review 13).
        no-auth?   (or (:no-auth-header options)
                       (and (seq (:headers options))
                            (str/blank? (:api-key options))
                            (some auth-header? (keys (:headers options)))))
        api-key    (when-not no-auth?
                     (resolve-api-key model options))
        ;; Codex's ChatGPT/Codex backend requires an OAuth access token to
        ;; derive chatgpt_account_id. A keyless custom codex-compatible
        ;; endpoint (local proxy, custom-header auth) legitimately has no key
        ;; and therefore no account id — omit the header instead of failing.
        account-id (when api-key
                     (content/extract-chatgpt-account-id api-key))]
    (when (and api-key (not (seq account-id)))
      (throw (ex-info "OpenAI Codex requires ChatGPT OAuth access token (missing chatgpt_account_id)"
                      {:provider (:provider model) :api :openai-codex-responses})))
    (let [structured-request (structured-output/structured-output-request options)
          strategy           (structured-output/select-strategy model structured-request)
          fallback-request   (when (= :prompted-json (:strategy strategy))
                               structured-request)
          tools     (codex-tools conversation)
          reasoning (codex-reasoning model options)
          base-hdrs (cond-> {"Content-Type"       "application/json"
                             "accept"             "text/event-stream"
                             "OpenAI-Beta"        "responses=experimental"
                             "originator"         "psi"}
                      account-id (assoc "chatgpt-account-id" account-id)
                      api-key    (assoc "Authorization" (str "Bearer " api-key))
                      (:session-id options)
                      (assoc "session_id"      (:session-id options)
                             "conversation_id" (:session-id options)))
          headers   (if-let [custom (:headers options)]
                      (merge base-hdrs custom)
                      base-hdrs)
          body      (cond-> {"model"               (:id model)
                             "store"               false
                             "stream"              true
                             "instructions"        (:system-prompt conversation)
                             "input"               (codex-input-messages conversation fallback-request)
                             "text"                {"verbosity" "medium"}
                             "tool_choice"         "auto"
                             "parallel_tool_calls" true}
                      (:session-id options) (assoc "prompt_cache_key" (:session-id options))
                      (seq tools)           (assoc "tools" tools)
                      reasoning             (assoc "reasoning" reasoning)
                      (codex-structured-output/native-mechanism? strategy)
                      (assoc-in ["text" "format"]
                                (codex-structured-output/text-format structured-request)))]
      {:headers headers
       :body    (json/generate-string body)})))

(defn- make-codex-stream-state
  []
  {:started?                  (atom false)
   :done?                     (atom false)
   :structured-result-emitted? (atom false)
   :text-buffer               (atom "")
   :next-tool-index           (atom 0)
   :tool-by-item-id           (atom {})
   :tool-by-output-index      (atom {})
   :tool-args-by-index        (atom {})
   :open-tool-indexes         (atom #{})})

(defn- emit-codex-start!
  [consume-fn started?]
  (when (compare-and-set! started? false true)
    (consume-fn {:type :start})))

(defn- emit-codex-started-event!
  [consume-fn started? event]
  (emit-codex-start! consume-fn started?)
  (consume-fn event))

(defn- register-codex-tool-index!
  [{:keys [next-tool-index tool-by-item-id tool-by-output-index]} event item]
  (let [output-idx (:output_index event)
        item-id    (:id item)
        idx        (cond
                     (and (number? output-idx)
                          (contains? @tool-by-output-index output-idx))
                     (get @tool-by-output-index output-idx)

                     (and (string? item-id)
                          (contains? @tool-by-item-id item-id))
                     (get @tool-by-item-id item-id)

                     (number? output-idx)
                     output-idx

                     :else
                     (let [i @next-tool-index]
                       (swap! next-tool-index inc)
                       i))]
    (when (number? output-idx)
      (swap! tool-by-output-index assoc output-idx idx))
    (when (string? item-id)
      (swap! tool-by-item-id assoc item-id idx))
    idx))

(defn- resolve-codex-tool-index
  [{:keys [tool-by-output-index tool-by-item-id]} event]
  (or
   (let [output-idx (:output_index event)]
     (when (number? output-idx)
       (or (get @tool-by-output-index output-idx)
           output-idx)))
   (let [item-id (or (:item_id event)
                     (get-in event [:item :id]))]
     (when (string? item-id)
       (get @tool-by-item-id item-id)))))

(defn- emit-codex-tool-delta!
  [{:keys [tool-args-by-index]} consume-fn idx args]
  (when (and (number? idx) (seq args))
    (swap! tool-args-by-index update idx (fnil str "") args)
    (consume-fn {:type          :toolcall-delta
                 :content-index idx
                 :delta         args})))

(defn- emit-codex-structured-output-result!
  [{:keys [structured-result-emitted? text-buffer]} consume-fn strategy]
  (let [raw-text @text-buffer]
    (codex-structured-output/maybe-emit-native-result!
     consume-fn structured-result-emitted? strategy raw-text)
    (codex-structured-output/maybe-emit-prompted-json-result!
     consume-fn structured-result-emitted? strategy raw-text)))

(defn- emit-codex-done!
  [{:keys [done? open-tool-indexes tool-args-by-index] :as stream-state} consume-fn model event strategy]
  (when-not @done?
    (reset! done? true)
    (doseq [idx @open-tool-indexes]
      (consume-fn {:type :toolcall-end :content-index idx}))
    (reset! open-tool-indexes #{})
    (reset! tool-args-by-index {})
    (emit-codex-structured-output-result! stream-state consume-fn strategy)
    (let [resp      (:response event)
          status    (:status resp)
          usage     (:usage resp)
          usage-map (when usage
                      (let [u (codex-usage->usage-map usage)]
                        (assoc u :cost (models/calculate-cost model u))))]
      (consume-fn (cond-> {:type :done
                           :reason (codex-status->reason status)}
                    usage-map (assoc :usage usage-map))))))

(defn- emit-codex-error!
  ([model stream-state consume-fn options url msg http-status]
   (emit-codex-error! model stream-state consume-fn options url msg http-status nil))
  ([model {:keys [done?]} consume-fn options url msg http-status headers]
   (when-not @done?
     (reset! done? true)
     (let [err (cond-> {:type :error :error-message msg}
                 http-status (assoc :http-status http-status)
                 headers (assoc :headers headers))]
       (transport/capture-response! model options :openai-codex-responses url err)
       (consume-fn err)))))

(defn- emit-codex-thinking-boundary!
  [stream-state consume-fn]
  (emit-codex-started-event! consume-fn (:started? stream-state)
                             {:type :thinking-start :content-index 0})
  (consume-fn {:type :thinking-end :content-index 0}))

(defn- emit-codex-thinking-delta!
  [stream-state consume-fn event]
  (when-let [delta (content/string-fragment (:delta event))]
    (emit-codex-started-event! consume-fn (:started? stream-state)
                               {:type :thinking-delta
                                :content-index 0
                                :delta delta})))

(def ^:private codex-thinking-delta-event-types
  #{"response.reasoning_summary_text.delta"
    "response.reasoning_text.delta"
    "response.reasoning_summary.delta"
    "response.reasoning.delta"})

(def ^:private codex-done-event-types
  #{"response.completed"
    "response.done"})

(defn- finish-codex-tool-call!
  [stream-state consume-fn event item]
  (let [{:keys [tool-args-by-index open-tool-indexes]} stream-state
        idx (or (resolve-codex-tool-index stream-state event)
                (register-codex-tool-index! stream-state event item))]
    (when (number? idx)
      (let [final-args (:arguments item)
            seen       (get @tool-args-by-index idx "")]
        (when (seq final-args)
          (cond
            (and (seq seen)
                 (str/starts-with? final-args seen))
            (let [remaining (subs final-args (count seen))]
              (when (seq remaining)
                (emit-codex-tool-delta! stream-state consume-fn idx remaining)))

            (not= final-args seen)
            (emit-codex-tool-delta! stream-state consume-fn idx final-args)))
        (swap! tool-args-by-index dissoc idx))
      (when (contains? @open-tool-indexes idx)
        (swap! open-tool-indexes disj idx)
        (consume-fn {:type :toolcall-end
                     :content-index idx})))))

(defn- handle-codex-output-item-added!
  [stream-state consume-fn event]
  (let [{:keys [started? open-tool-indexes]} stream-state
        item      (:item event)
        item-type (:type item)]
    (case item-type
      "message"
      (emit-codex-start! consume-fn started?)

      "reasoning"
      (emit-codex-start! consume-fn started?)

      "function_call"
      (let [idx       (register-codex-tool-index! stream-state event item)
            call-id   (or (:call_id item) (content/new-call-id))
            item-id   (:id item)
            tool-id   (if (seq item-id) (str call-id "|" item-id) call-id)
            tool-name (or (:name item) "tool")]
        (emit-codex-started-event! consume-fn started?
                                   {:type          :toolcall-start
                                    :content-index idx
                                    :id            tool-id
                                    :name          tool-name})
        (swap! open-tool-indexes conj idx)
        (when-let [args (:arguments item)]
          (emit-codex-tool-delta! stream-state consume-fn idx args)))

      nil)))

(defn- handle-codex-output-item-done!
  [stream-state consume-fn event]
  (let [item      (:item event)
        item-type (:type item)]
    (case item-type
      "function_call" (finish-codex-tool-call! stream-state consume-fn event item)
      "reasoning" (emit-codex-thinking-boundary! stream-state consume-fn)
      nil)))

(defn- numeric-http-status
  [value]
  (cond
    (number? value)
    (let [status (long value)]
      (when (<= 400 status 599)
        status))

    (string? value)
    (try
      (let [parsed (Long/parseLong value)]
        (when (<= 400 parsed 599)
          parsed))
      (catch Exception _
        nil))

    :else nil))

(defn- codex-error-http-status
  [event]
  (some numeric-http-status
        [(:http-status event)
         (:http_status event)
         (:status event)
         (:status-code event)
         (:status_code event)
         (get-in event [:response :http-status])
         (get-in event [:response :http_status])
         (get-in event [:response :status-code])
         (get-in event [:response :status_code])
         (get-in event [:response :error :http-status])
         (get-in event [:response :error :http_status])
         (get-in event [:response :error :status])
         (get-in event [:response :error :status-code])
         (get-in event [:response :error :status_code])
         (get-in event [:error :http-status])
         (get-in event [:error :http_status])
         (get-in event [:error :status])
         (get-in event [:error :status-code])
         (get-in event [:error :status_code])]))

(defn- stringify-header-keys
  [headers]
  (when (map? headers)
    (reduce-kv (fn [acc k v]
                 (assoc acc (if (keyword? k) (name k) (str k)) v))
               {}
               headers)))

(defn- codex-error-headers
  [event]
  (some-> (or (:provider-error/headers event)
              (:headers event)
              (get-in event [:response :headers])
              (get-in event [:response :error :headers])
              (get-in event [:error :headers]))
          stringify-header-keys))

(defn- codex-error-message
  [event fallback]
  (or (get-in event [:response :error :message])
      (get-in event [:error :message])
      (when (string? (:message event))
        (:message event))
      (when (string? (:error event))
        (:error event))
      fallback))

(defn- handle-codex-event!
  [stream-state consume-fn model options url strategy event]
  (transport/capture-response! model options :openai-codex-responses url event)
  (let [event-type (:type event)]
    (cond
      (= "response.output_item.added" event-type)
      (handle-codex-output-item-added! stream-state consume-fn event)

      (= "response.function_call_arguments.delta" event-type)
      (let [idx   (resolve-codex-tool-index stream-state event)
            delta (:delta event)]
        (when (and (number? idx) (seq delta))
          (emit-codex-start! consume-fn (:started? stream-state))
          (emit-codex-tool-delta! stream-state consume-fn idx delta)))

      (= "response.output_item.done" event-type)
      (handle-codex-output-item-done! stream-state consume-fn event)

      (= "response.output_text.delta" event-type)
      (when-let [delta (content/string-fragment (:delta event))]
        (swap! (:text-buffer stream-state) str delta)
        (emit-codex-started-event! consume-fn (:started? stream-state)
                                   {:type :text-delta
                                    :content-index 0
                                    :delta delta}))

      (contains? codex-thinking-delta-event-types event-type)
      (emit-codex-thinking-delta! stream-state consume-fn event)

      (contains? codex-done-event-types event-type)
      (do
        (emit-codex-start! consume-fn (:started? stream-state))
        (emit-codex-done! stream-state consume-fn model event strategy))

      (= "response.failed" event-type)
      (emit-codex-error! model stream-state consume-fn options url
                         (codex-error-message event "Codex response failed")
                         (codex-error-http-status event)
                         (codex-error-headers event))

      (= "error" event-type)
      (emit-codex-error! model stream-state consume-fn options url
                         (codex-error-message event "Codex stream error")
                         (codex-error-http-status event)
                         (codex-error-headers event))

      :else nil)))

(defn stream-openai-codex
  [conversation model options consume-fn]
  (let [url                (resolve-codex-url (:base-url model))
        structured-request (structured-output/structured-output-request options)
        strategy           (structured-output/select-strategy model structured-request)
        stream-state       (make-codex-stream-state)]
    (try
      (let [request  (build-codex-request conversation model options)
            _        (transport/capture-request! model options :openai-codex-responses url request)
            _        (when strategy
                       (consume-fn {:type :structured-output-strategy
                                    :structured-output strategy}))
            response (transport/stream-response url request)]
        (if (transport/error-status? (:status response))
          (let [{:keys [error-message http-status]} (transport/response->error response)]
            (emit-codex-error! model stream-state consume-fn options url error-message http-status))
          (do
            (with-open [reader (io/reader (:body response))]
              (doseq [line (line-seq reader)]
                (when-let [event (transport/parse-sse-line line)]
                  (handle-codex-event! stream-state consume-fn model options url strategy event))))
            (when-not @(-> stream-state :done?)
              (emit-codex-start! consume-fn (-> stream-state :started?))
              (emit-codex-done! stream-state consume-fn model {:response {:status "completed"}} strategy)))))
      (catch Exception e
        (let [{:keys [error-message http-status]} (transport/exception->error e)]
          (emit-codex-error! model stream-state consume-fn options url error-message http-status))))))
