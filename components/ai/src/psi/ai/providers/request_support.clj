(ns psi.ai.providers.request-support
  "Shared request authentication, stream-event, and capture-redaction helpers
   for the Anthropic Messages and OpenAI transports."
  (:require
   [clojure.string :as str]
   [psi.ai.providers.environment-boundary :as environment-boundary]))

(defn resolve-key-spec
  "Resolve an API-key spec at request time. Lowercase `env:VAR` reads VAR
   from the supplied environment; nil and blank specs resolve to nil; all
   other strings are literal keys. Empty variable names are invalid."
  ([raw]
   (resolve-key-spec raw environment-boundary/real))
  ([raw environment]
   (cond
     (or (nil? raw) (str/blank? raw)) nil
     (str/starts-with? raw "env:")
     (let [var (subs raw 4)]
       ;; A blank variable name after the prefix (e.g. "env:") is an
       ;; unresolvable spec, never an environment lookup of the empty string.
       (when-not (str/blank? var)
         (environment-boundary/lookup environment var)))
     :else raw)))

(def openai-api-key-config
  "Shared OpenAI API-key configuration for chat-completions and Codex
   responses."
  {:builtin-provider    :openai
   :env-var             "OPENAI_API_KEY"
   :builtin-missing-msg "Missing OpenAI API key. Set OPENAI_API_KEY or login via /login openai."})

(defn auth-header?
  "True when a header name is a recognized auth header (case-insensitive)."
  [header]
  (contains? #{"x-api-key" "authorization"}
             (str/lower-case (name header))))

(defn no-auth?
  "True for explicitly keyless requests, or when recognized custom auth headers
   carry authentication without a configured API key. Incidental headers do
   not make a request keyless."
  [options]
  (or (:no-auth-header options)
      (and (seq (:headers options))
           (str/blank? (:api-key options))
           (some auth-header? (keys (:headers options))))))

(defn builtin?
  "True for a built-in catalog model of the given provider. Custom models are
   never built-in, even when their provider name matches."
  [model builtin-provider-kw]
  (and (not (:custom? model))
       (let [provider (:provider model)]
         (or (nil? provider) (= builtin-provider-kw provider)))))

(defn builtin-openai-chat-completions?
  "True for a built-in OpenAI chat-completions model."
  [model]
  (and (builtin? model :openai)
       (= :openai-completions (:api model))))

(defn resolve-api-key
  "Resolve an API key at request time without crossing provider boundaries.

   Built-in models may fall back to their configured environment variable.
   Custom providers must supply their own key unless the request is explicitly
   keyless or carries a recognized custom auth header. Missing custom env specs
   produce provider-scoped recovery guidance."
  [model options config]
  (when-not (no-auth? options)
    (let [{:keys [builtin-provider env-var builtin-missing-msg]} config
          provider    (:provider model)
          builtin?    (builtin? model builtin-provider)
          environment (environment-boundary/boundary options)
          api-key     (resolve-key-spec (:api-key options) environment)
          api-key     (if (and builtin? (str/blank? api-key))
                        (environment-boundary/lookup environment env-var)
                        api-key)]
      (when (str/blank? api-key)
        (if builtin?
          (throw (ex-info builtin-missing-msg
                          {:error-code "auth/missing-api-key"
                           :provider builtin-provider}))
          ;; Custom-provider errors name their own configuration remedy and
          ;; never suggest the built-in-only OAuth login flow.
          (let [spec (some-> (:api-key options) str)
                env-var (when (and (string? spec)
                                   (str/starts-with? spec "env:"))
                          (subs spec 4))]
            (cond
              ;; Empty env names are configuration errors, not lookups.
              (and (string? env-var) (str/blank? env-var))
              (throw (ex-info (str "Missing API key for provider " (name provider)
                                   ": api-key spec \"" spec "\" names an empty"
                                   " environment variable (use \"env:VAR_NAME\").")
                              {:error-code "auth/missing-api-key"
                               :provider provider}))

              env-var
              (throw (ex-info (str "Missing API key for provider " (name provider)
                                   ": environment variable " env-var
                                   " is unset (env: keys are re-read per request;"
                                   " relaunch psi with the variable set in its process"
                                   " environment, then retry).")
                              {:error-code "auth/missing-api-key"
                               :provider provider}))

              :else
              (throw (ex-info (str "Missing API key for provider " (name provider)
                                   ". Configure the provider's :auth {:api-key ...} in models.edn"
                                   ;; Shell variable names use underscores.
                                   " (e.g. \"env:" (-> (name provider)
                                                       (str/replace "-" "_")
                                                       str/upper-case)
                                   "_API_KEY\").")
                              {:error-code "auth/missing-api-key"
                               :provider provider}))))))
      api-key)))

;; ── Stream event helpers ─────────────────────────────────────────────────────

(defn emit-start!
  "Emit :start exactly once. Transports call this before any event that may
   be first, preserving start-before-output and start-before-terminal ordering
   for well-formed, truncated, and error-first streams."
  [consume-fn started?]
  (when (compare-and-set! started? false true)
    (consume-fn {:type :start})))

;; ── Capture redaction ────────────────────────────────────────────────────────

(defn find-headers
  "Return every header entry matching a name case-insensitively. Finding all
   casing variants prevents duplicate auth headers from escaping redaction."
  [headers header-name]
  (let [target (str/lower-case header-name)]
    (keep (fn [[k v]]
            (when (= target (str/lower-case (name k)))
              [k v]))
          headers)))

(defn redact-secret
  [value]
  (when (string? value)
    (str "***REDACTED***"
         (when (> (count value) 20)
           (str " (len=" (count value) ")")))))

(defn redact-authorization
  [value]
  (when (string? value)
    (str "Bearer "
         ;; Length metadata measures the secret, not the Bearer prefix.
         (redact-secret (str/replace value #"^Bearer\s+" "")))))

(defn mask-chatgpt-account-id
  [value]
  (when (string? value)
    (str (subs value 0 (min 6 (count value))) "...")))

(defn redact-headers
  "Redact every case variant of configured header names while preserving each
   original key. Non-auth headers pass through unchanged."
  [headers redactors]
  (reduce (fn [hdr [name redactor]]
            (reduce (fn [h [k v]]
                      (assoc h k (redactor v)))
                    hdr
                    (find-headers hdr name)))
          headers
          redactors))
