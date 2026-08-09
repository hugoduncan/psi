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
            [psi.ai.providers.anthropic.stream-events :as stream-events]
            [psi.ai.providers.anthropic.structured-output :as anthropic-structured-output]
            [psi.ai.providers.anthropic.usage :as usage]
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
     ;; ::oauth? carries the transport's COMPUTED OAuth decision (built-in
     ;; Anthropic model + OAuth-shaped key, review 11) for the HTTP-400
     ;; compatibility retry: handle-400-response! uses it instead of
     ;; content-sniffing the merged headers, so a keyless custom provider
     ;; whose custom :headers reproduce the Claude Code CLI marker set is
     ;; still NOT treated as OAuth (review 22).
     {:headers headers
      :body    (json/generate-string body*)
      ::oauth? oauth?})))

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

(defn- emit-start!
  "Emit :start exactly once, before the terminal, when the stream never
   emitted it (the stream's first event is a terminal/error rather than
   message_start — a malformed/truncated stream, an error-first stream, or a
   stream-read exception before any output). Review 50: stream-anthropic had
   no started? tracking — :start was emitted only inside the message_start
   case branch, so the terminal emitters emitted :done/:error with no
   preceding :start when the stream never received message_start — the only
   three-transport asymmetry left in the review-48 EOF-level flush (both
   sibling transports emit :start first when not started:
   emit-chat-completion-finish!'s stream-started? compare-and-set and the
   codex EOF flush's emit-codex-start!). Review 53: the outer catch block (a
   stream-read exception before any output) also emits :start first — the
   last :start-before-terminal gap on this transport. Benign for the consumer
   (:start is a no-op handler; the turn statechart is already past :idle via
   the turn-level :turn/start) but removes the last cross-transport
   asymmetry in the terminal-emission class this task has repeatedly treated
   as actionable."
  [consume-fn started?]
  (when (compare-and-set! started? false true)
    (consume-fn {:type :start})))

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
                      ;; Review 22: the :without-all-betas decision uses the
                      ;; transport's COMPUTED oauth? boolean (threaded from
                      ;; build-request via ::oauth?), NOT the header
                      ;; content-sniff — a keyless custom provider whose
                      ;; custom :headers reproduce the Claude Code CLI marker
                      ;; set (Authorization Bearer + user-agent: claude-cli/…
                      ;; + x-app: cli) is still not OAuth and must get
                      ;; :without-all-betas on a beta-related 400. The
                      ;; content-sniffing oauth-auth-request? predicate is
                      ;; kept for error diagnostics only.
                      :oauth-auth-request? (fn [req] (boolean (::oauth? req)))})]
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
        done?       (atom false)
        ;; Review 50: started? tracks whether :start was emitted (message_start
        ;; received). stream-anthropic previously had no started? tracking —
        ;; :start was emitted only inside the message_start case branch, so
        ;; emit-terminal-done! (message_stop / EOF flush) and the "error"
        ;; branch emitted :done/:error with no preceding :start when the
        ;; stream never received message_start — the only three-transport
        ;; asymmetry left in the review-48 EOF-level flush (both sibling
        ;; transports emit :start first when not started:
        ;; emit-chat-completion-finish!'s stream-started? compare-and-set and
        ;; the codex EOF flush's emit-codex-start!).
        started?    (atom false)]
    (try
      (capture-request! model options url request)
      (when strategy
        (consume-fn {:type :structured-output-strategy
                     :structured-output strategy}))
      (letfn [(emit-terminal-done! []
                ;; The terminal :done shared by the message_stop branch and
                ;; the review-48 EOF-level flush (below): structured-output
                ;; results for the completed buffers, then the :done with the
                ;; review-47 usage-with-cost shape. Review 49: done? is reset
                ;; FIRST — before the structured-output emissions and the
                ;; :done consume — mirroring the message_delta-with-stop_reason
                ;; branch and every OpenAI-transport terminal emitter
                ;; (emit-chat-completion-finish!/emit-chat-error!/
                ;; emit-codex-done!/emit-codex-error!): a downstream exception
                ;; during the terminal processing (a structured-output
                ;; emission or the :done consume-fn, e.g. a statechart
                ;; dispatch failure inside make-provider-event-consumer's
                ;; :done → :turn/done send) must NOT propagate to the outer
                ;; catch with done? still false and emit a SECOND :error
                ;; terminal — the double-terminal class reviews 43/44/46
                ;; eliminated on every other terminal path
                ;; (OnceDoneNoFurtherEvent).
                (reset! done? true)
                ;; Review 50: emit :start first (when the stream never
                ;; received message_start) — mirroring
                ;; emit-chat-completion-finish!'s ordering (done? reset, then
                ;; :start, then the terminal).
                (emit-start! consume-fn started?)
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
                             :reason :stop
                             :usage  (usage/usage-with-cost model usage-acc)}))
              (consume-stream-response! [response]
                (with-open [reader (io/reader (:body response))]
                  (doseq [line (line-seq reader)]
                    (when-let [event-data (parse-sse-line line)]
                      (capture-response! model options url event-data)
                      ;; Review 46: short-circuit the entire SSE dispatch once
                      ;; the stream has terminated (done?) — NOT just the
                      ;; terminal emissions. A post-error trailing event (a
                      ;; content_block_stop / content_block_delta /
                      ;; content_block_start, a trailing message_delta, a
                      ;; message_stop) must be a full no-op: previously only
                      ;; the terminal branches (:done/:error) were guarded, so
                      ;; e.g. error → trailing content_block_stop still
                      ;; emitted :text-end and could fire
                      ;; maybe-emit-structured-result!, mutating turn-data
                      ;; after handle-error! had finalized the result.
                      (when-not @done?
                        (case (:type event-data)
                          "message_start"
                          (do
                            (usage/update-start-usage! usage-acc (get-in event-data [:message :usage]))
                            (emit-start! consume-fn started?))

                          "content_block_start"
                          (let [idx   (:index event-data)
                                block (:content_block event-data)]
                            (swap! block-types assoc idx {:type (:type block)
                                                          :name (:name block)})
                            (when-not (anthropic-structured-output/structured-tool-block?
                                       structured-tool-name
                                       {:type (:type block) :name (:name block)})
                              ;; consume-event! guards nil — review 48:
                              ;; content-block-start-event returns nil for
                              ;; skipped "redacted_thinking" blocks.
                              (stream-events/consume-event! consume-fn
                                                            (stream-events/content-block-start-event idx block))))

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
                                (stream-events/consume-event! consume-fn
                                                              (stream-events/content-block-delta-event (:type block-info)
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
                              ;; consume-event! guards nil — review 48:
                              ;; content-block-stop-event returns nil for
                              ;; skipped "redacted_thinking" blocks.
                              (stream-events/consume-event! consume-fn
                                                            (stream-events/content-block-stop-event (:type block-info)
                                                                                                    idx))))

                          "error"
                          ;; Anthropic's documented mid-stream SSE error shape
                          ;; ({"type":"error","error":{...}} — e.g.
                          ;; overloaded_error / rate-limit during a stream).
                          ;; Review 43: the default case previously consumed
                          ;; these as no-ops, so a mid-stream provider error
                          ;; hung the turn until llm-stream-idle-timeout-ms
                          ;; with a misleading timeout. Surface the event's
                          ;; error body through anthropic-error and terminate
                          ;; the stream; the outer done? guard (review 46)
                          ;; makes every subsequent event a no-op.
                          ;; Review 47: http-status extraction mirrors the
                          ;; sibling transports' emit-chat-error! /
                          ;; codex-error-http-status — :status /
                          ;; [:error :status] / [:error :http_status],
                          ;; numeric >= 400 only — so a status-carrying error
                          ;; event (e.g. {"error":{"status":529,...}}) keeps
                          ;; its numeric :http-status and downstream
                          ;; retry-error?/provider-error-kind classify a
                          ;; transient mid-stream 5xx/overload as retryable
                          ;; instead of :unknown (the review-23 class the
                          ;; openai transports already handle).
                          (let [status (some (fn [s] (and (number? s) (>= s 400) s))
                                             [(get-in event-data [:error :http_status])
                                              (get-in event-data [:error :status])
                                              (:http_status event-data)
                                              (:status event-data)])
                                err    (anthropic-error/error-from-response-data
                                        {:status           status
                                         :body-text        (json/generate-string event-data)
                                         :fallback-message "Anthropic stream error"})]
                            (reset! done? true)
                            ;; Review 50: emit :start first when the stream
                            ;; never received message_start (a malformed
                            ;; stream whose first event is the error) —
                            ;; mirroring the terminal emitters' ordering.
                            (emit-start! consume-fn started?)
                            (consume-fn err))

                          "message_delta"
                          ;; Review 44: the terminal :done emission is guarded
                          ;; on done? like the message_stop branch — a trailing
                          ;; message_delta carrying delta.stop_reason after a
                          ;; mid-stream SSE error must NOT emit a second
                          ;; terminal :done (verified: events were
                          ;; [:start :error :done] for error → message_delta
                          ;; stop_reason end_turn). Usage accumulation and the
                          ;; structured-output-result emissions stay inside the
                          ;; guard with the :done so a post-error message_delta
                          ;; is a full no-op. (Redundant with the outer
                          ;; review-46 guard but kept for branch-local clarity.)
                          (when-not @done?
                            (usage/update-output-usage! usage-acc (:usage event-data))
                            (when-let [reason (get-in event-data [:delta :stop_reason])]
                              (reset! done? true)
                              ;; Review 52: emit :start first when the stream
                              ;; never received message_start (a malformed
                              ;; stream whose FIRST event is a message_delta
                              ;; carrying stop_reason) — mirroring
                              ;; emit-terminal-done!'s ordering (done? reset,
                              ;; then :start, then the terminal). Review 50
                              ;; tested message_stop-first and empty-body but
                              ;; not message_delta-first, so this branch
                              ;; emitted [:done] while message_stop-first
                              ;; emits [:start :done] — the last
                              ;; :start-before-terminal gap on the anthropic
                              ;; transport.
                              (emit-start! consume-fn started?)
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
                                           :usage  (usage/usage-with-cost model usage-acc)})))

                          "message_stop"
                          ;; The terminal :done. done? is set here too
                          ;; (review 46) so a malformed event AFTER a normal
                          ;; message_stop — or a cleanup exception — is also a
                          ;; full no-op: the guarantee is "no further event at
                          ;; all once done", not just "no second terminal".
                          ;; Review 47: the :done now carries the accumulated
                          ;; usage (usage-with-cost on usage-acc) like the
                          ;; message_delta-with-stop_reason terminal — a
                          ;; stream terminating via message_stop WITHOUT a
                          ;; preceding message_delta carrying stop_reason
                          ;; previously emitted a bare {:type :done :reason
                          ;; :stop}, so handle-done! ((map? usage) false)
                          ;; recorded ZERO usage/cost even though usage-acc
                          ;; held the input + cache tokens accumulated from
                          ;; message_start. Reachable on any
                          ;; Anthropic-compatible endpoint that omits
                          ;; message_delta — including the newly shipped
                          ;; DeepSeek provider whose streaming path is
                          ;; unverified. Review 48: emits through the shared
                          ;; emit-terminal-done! (also used by the EOF-level
                          ;; flush after the doseq).
                          (emit-terminal-done!)

                          nil)))))
                  ;; Review 48: EOF-level terminal flush — mirror the codex
                  ;; transport's (when-not @(:done? ...) ...) after its SSE
                  ;; doseq. A stream that EOFs without an in-band terminal
                  ;; event (message_stop, message_delta-with-stop_reason, or
                  ;; "error") previously emitted NO :done/:error and hung the
                  ;; turn until llm-stream-idle-timeout-ms — the review-43
                  ;; hang class via the EOF path rather than a mid-stream
                  ;; error, directly task-relevant since review 47 established
                  ;; DeepSeek's streaming path is UNVERIFIED (the review-1
                  ;; smoke test exercised only the non-streaming execute
                  ;; path), so a DeepSeek stream that ends without
                  ;; message_stop would hang 20 minutes instead of
                  ;; terminating. The flush emits the same terminal as
                  ;; message_stop (:stop, review-47 usage-with-cost shape);
                  ;; when an in-band terminal already fired, done? makes it a
                  ;; no-op.
                (when-not @done?
                  (emit-terminal-done!)
                    ;; Preserve the pre-review-48 nil return of the stream fn
                    ;; (the flush's when-not would otherwise return the last
                    ;; consumed event via emit-terminal-done!'s reset!).
                  nil))]
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
        ;; Review 44: guard the error emission on done? (mirroring the codex
        ;; transport's emit-codex-error!) — if a mid-stream SSE error already
        ;; terminated the stream, a stream-read exception thrown afterwards
        ;; must not emit a SECOND :error.
        (when-not @done?
          ;; Review 53: emit :start first — the catch block is the last
          ;; :start-before-terminal gap on this transport. A stream-read
          ;; exception before any output event (e.g. a connection reset on
          ;; the first read) previously emitted [:error] with no preceding
          ;; :start, while every in-band terminal/error emitter now emits
          ;; [:start ...] (review-50 "error" branch, review-52
          ;; message_delta branch, emit-terminal-done!). The catch now emits
          ;; :start once (compare-and-set on started?) before the :error,
          ;; mirroring the in-band error branch's ordering — so a
          ;; first-read exception yields [:start :error] like every other
          ;; error path on this transport.
          (emit-start! consume-fn started?)
          (let [err (anthropic-error/exception->error e)]
            (capture-response! model options url err)
            (consume-fn err)))))))

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
