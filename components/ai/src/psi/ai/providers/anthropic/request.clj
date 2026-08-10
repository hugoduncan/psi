(ns psi.ai.providers.anthropic.request
  "Anthropic request construction for the :anthropic-messages transport:
   headers, body, and the request map (build-request).

   psi.ai.providers.anthropic re-exports build-request and
   transform-messages and reads the beta constants needed by its HTTP-400
   compatibility retry."
  (:require [clojure.string :as str]
            [cheshire.core :as json]
            [psi.ai.providers.anthropic.message-transform :as message-transform]
            [psi.ai.providers.anthropic.request-schema :as request-schema]
            [psi.ai.providers.anthropic.structured-output :as anthropic-structured-output]
            [psi.ai.providers.request-support :as request-support]
            [psi.ai.structured-output :as structured-output]))
(def ^:private anthropic-version "2023-06-01")
(def ^:private claude-code-beta "claude-code-20250219")
;; OAuth requests present as the Claude Code CLI (see claude-code-system-prompt).
(def ^:private claude-code-version "2.1.75")
(def ^:private oauth-beta "oauth-2025-04-20")
(def ^:private context-management-beta "context-management-2025-06-27")
(def interleaved-thinking-beta "interleaved-thinking-2025-05-14")
(def prompt-caching-beta "prompt-caching-2024-07-31")
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
   headers/system prompt."
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
      :body    (json/generate-string body*)
      :psi.ai.providers.anthropic/oauth? oauth?})))
