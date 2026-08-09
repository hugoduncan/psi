(ns psi.ai.providers.openai.codex-responses
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [cheshire.core :as json]
            [psi.ai.models :as models]
            [psi.ai.providers.openai.content :as content]
            [psi.ai.providers.openai.reasoning :as reasoning]
            [psi.ai.providers.openai.codex-structured-output :as codex-structured-output]
            [psi.ai.providers.openai.transport :as transport]
            [psi.ai.providers.request-support :as request-support]
            [psi.ai.structured-output :as structured-output]))

(defn- resolve-codex-url
  [base-url]
  (let [raw        (if (str/blank? base-url) "https://chatgpt.com/backend-api" base-url)
        normalized (str/replace raw #"/+$" "")]
    (cond
      (str/ends-with? normalized "/codex/responses") normalized
      (str/ends-with? normalized "/codex")           (str normalized "/responses")
      :else                                           (str normalized "/codex/responses"))))

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
        no-auth?   (request-support/no-auth? options)
        api-key    (when-not no-auth?
                     (request-support/resolve-api-key
                      model options request-support/openai-api-key-config))
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
  "Emit :start exactly once, before the first output/terminal/error event
   when the stream never emitted it. The once-semantics live in the shared
   `request-support/emit-start!` (review 54 extracted the three
   byte-identical per-transport copies — this was the codex copy from
   review 52); this private wrapper keeps the transport-local name at the
   call sites."
  [consume-fn started?]
  (request-support/emit-start! consume-fn started?))

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
  ([model {:keys [done? started?]} consume-fn options url msg http-status headers]
   (when-not @done?
     (reset! done? true)
     ;; Review 52: emit :start first when the stream never emitted it (an
     ;; error-FIRST stream — response.failed/error before any output event,
     ;; or an HTTP-error response to the stream request) — mirroring
     ;; emit-codex-start!'s role in the codex EOF flush and the
     ;; review-50-fixed anthropic "error" branch's [:start :error].
     ;; Previously an error-first codex stream emitted [:error] with no
     ;; :start — the last three-transport asymmetry in the review-50
     ;; :start-before-terminal class (the existing codex error tests never
     ;; caught it because they start with an output event that triggers
     ;; :start via the non-error path).
     (emit-codex-start! consume-fn started?)
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
  ;; Review 46: short-circuit the whole dispatch once the stream has
  ;; terminated (done? — set by emit-codex-error! and emit-codex-done!). A
  ;; trailing SSE event after response.failed/error previously still emitted
  ;; non-terminal events: a trailing response.output_text.delta fired
  ;; :text-delta (handle-codex-event! had no done? check at its top — only
  ;; emit-codex-error!/emit-codex-done! self-guarded). Now every post-done
  ;; event is a full no-op.
  (when-not @(:done? stream-state)
    (let [event-type (:type event)]
      ;; Review 52: skip the raw capture for the mid-stream error event types
      ;; (response.failed / error) — emit-codex-error! captures the
      ;; CONSTRUCTED :error event (with normalized :http-status/:headers).
      ;; Previously the raw event was captured here AND the constructed error
      ;; was captured in emit-codex-error! — two :on-provider-response
      ;; callbacks per codex mid-stream error, while the anthropic "error"
      ;; branch and openai emit-chat-error! capture the raw SSE line only
      ;; (their constructed :error is never in the capture payload). The
      ;; capture payloads are now consistent for the same error class: codex
      ;; captures the constructed error (like its own HTTP-error path),
      ;; anthropic/openai capture the raw line — exactly one
      ;; :on-provider-response callback per mid-stream error on every
      ;; transport.
      (when-not (or (= "response.failed" event-type)
                    (= "error" event-type))
        (transport/capture-response! model options :openai-codex-responses url event))
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

        :else nil))))

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
          ;; Review 51: surface the FULL error map (headers/body-text), not
          ;; just error-message + http-status. The previous destructure
          ;; dropped :headers/:body-text/:body even though
          ;; emit-codex-error!'s 4-arity accepts headers (used by the SSE
          ;; response.failed / error branches) — the only transport whose
          ;; HTTP-error path lost request-id-style headers for diagnostics
          ;; (the anthropic and openai chat-completions HTTP-error paths
          ;; surface the full error map via response->error). A codex HTTP
          ;; error (401/429/500 from the ChatGPT backend or a custom codex
          ;; endpoint) now keeps its headers on the :error event, mirroring
          ;; the sibling transports.
          (let [{:keys [error-message http-status headers]} (transport/response->error response)]
            (emit-codex-error! model stream-state consume-fn options url error-message http-status headers))
          (do
            (with-open [reader (io/reader (:body response))]
              (doseq [line (line-seq reader)]
                (when-let [event (transport/parse-sse-line line)]
                  (handle-codex-event! stream-state consume-fn model options url strategy event))))
            (when-not @(-> stream-state :done?)
              (emit-codex-start! consume-fn (-> stream-state :started?))
              (emit-codex-done! stream-state consume-fn model {:response {:status "completed"}} strategy)))))
      (catch Exception e
        ;; Review 52: pass headers through from exception->error like the
        ;; review-51-fixed HTTP-error branch — the catch previously
        ;; destructured away :headers/:body-text/:body and called the 3-arity
        ;; (headers nil), so an exception carrying response headers in its
        ;; ex-data (rare for non-HTTP stream exceptions, but the same one-line
        ;; class review 51 just fixed) lost them on the :error event. The
        ;; 4-arity's error map carries headers for diagnostics, consistent
        ;; with the HTTP-error branch and the sibling transports.
        (let [{:keys [error-message http-status headers]} (transport/exception->error e)]
          (emit-codex-error! model stream-state consume-fn options url error-message http-status headers))))))
