(ns psi.provider-auth.oauth.providers
  "OAuth provider registry and provider implementations.

   Each provider is a map:
     :id                  — keyword identifier
     :name                — display name
     :uses-callback-server — true if login uses a local redirect server
     :login               — (fn [callbacks]) → credential map (blocking, callback-based)
     :begin-login         — (fn []) → {:url ... :login-state ...} (non-blocking)
     :complete-login      — (fn [input login-state]) → credential map
     :refresh-token       — (fn [credential]) → updated credential map
     :get-api-key         — (fn [credential]) → api key string"
  (:require [clj-http.client :as http]
            [clojure.string :as str]
            [cheshire.core :as json]
            [psi.provider-auth.oauth.callback-server :as cb]
            [psi.provider-auth.oauth.pkce :as pkce])
  (:import [java.net URI URLDecoder URLEncoder]
           [java.util UUID]))

;;; Provider registry

(defonce ^:private registry (atom {}))

(defn register-provider!
  "Register an OAuth provider."
  [provider]
  (swap! registry assoc (:id provider) provider))

(defn get-provider
  "Look up a provider by id keyword."
  [id]
  (get @registry (keyword id)))

(defn list-providers
  "Return all registered providers."
  []
  (vals @registry))

;;; URL encoding helper

(defn- url-encode ^String [^String s]
  (URLEncoder/encode s "UTF-8"))

(defn- build-query-string
  "Build a URL query string from a map."
  [params]
  (->> params
       (map (fn [[k v]] (str (url-encode (name k)) "=" (url-encode (str v)))))
       (clojure.string/join "&")))

(defn- parse-query-string
  "Parse a URL query string into a map of string keys/values."
  [^String query]
  (if (str/blank? query)
    {}
    (->> (str/split query #"&")
         (keep (fn [part]
                 (let [[k v] (str/split part #"=" 2)]
                   (when (and k v)
                     [(URLDecoder/decode k "UTF-8")
                      (URLDecoder/decode v "UTF-8")]))))
         (into {}))))

(defn- parse-authorization-input
  "Parse user-pasted authorization input.

   Accepts any of:
   - full URL (query contains code/state)
   - code#state
   - code=...&state=...
   - raw code

   Returns {:code string? :state string?}."
  [input]
  (let [value (str/trim (or input ""))]
    (cond
      (str/blank? value)
      {:code nil :state nil}

      ;; full URL
      (re-find #"^https?://" value)
      (try
        (let [uri   (URI. value)
              m     (parse-query-string (.getQuery uri))
              code  (get m "code")
              state (get m "state")]
          {:code (or code value)
           :state state})
        (catch Exception _
          {:code value :state nil}))

      ;; code#state format (also accepts code=...#state=...)
      (str/includes? value "#")
      (let [[code-part state-part] (str/split value #"#" 2)
            parsed                (when (or (str/includes? (or code-part "") "=")
                                            (str/includes? (or state-part "") "="))
                                    (parse-query-string (str (or code-part "")
                                                             "&"
                                                             (or state-part ""))))
            code                  (or (get parsed "code")
                                      (some-> code-part
                                              str/trim
                                              (str/replace-first #"^code=" "")
                                              not-empty))
            state                 (or (get parsed "state")
                                      (some-> state-part
                                              str/trim
                                              (str/replace-first #"^state=" "")
                                              not-empty))]
        {:code code
         :state state})

      ;; key=value&... format
      (or (str/includes? value "code=") (str/includes? value "state="))
      (let [q (if (str/starts-with? value "?") (subs value 1) value)
            m (parse-query-string q)]
        {:code (get m "code")
         :state (get m "state")})

      :else
      {:code value :state nil})))

(defn- expires-at-from-seconds
  "Convert OAuth expires_in seconds to epoch millis with a 5-minute safety buffer."
  [expires-in]
  (- (+ (System/currentTimeMillis) (* expires-in 1000))
     (* 5 60 1000)))

;;; Browser open

(defn- open-browser!
  "Attempt to open a URL in the default browser. Best-effort, no error on failure."
  [^String url]
  (try
    (let [os (System/getProperty "os.name")]
      (cond
        (.contains os "Mac")   (.exec (Runtime/getRuntime) ^"[Ljava.lang.String;" (into-array String ["open" url]))
        (.contains os "Linux") (.exec (Runtime/getRuntime) ^"[Ljava.lang.String;" (into-array String ["xdg-open" url]))
        (.contains os "Win")   (.exec (Runtime/getRuntime) ^"[Ljava.lang.String;" (into-array String ["cmd" "/c" "start" url]))))
    (catch Exception _)))

;;; Anthropic OAuth provider

(def ^:private anthropic-default-client-id "9d1c250a-e61b-44d9-88ed-5944d1962f5e")
(def ^:private anthropic-default-authorize-url "https://claude.ai/oauth/authorize")
(def ^:private anthropic-default-token-url "https://console.anthropic.com/v1/oauth/token")
(def ^:private anthropic-default-redirect-uri "https://console.anthropic.com/oauth/code/callback")
(def ^:private anthropic-default-scopes "org:create_api_key user:profile user:inference")

(defn- get-env
  [k]
  (System/getenv k))

(defn- env-nonblank
  [k]
  (some-> (get-env k)
          str/trim
          not-empty))

(defn- anthropic-oauth-config
  []
  {:client-id (or (env-nonblank "PSI_ANTHROPIC_OAUTH_CLIENT_ID")
                  (env-nonblank "ANTHROPIC_OAUTH_CLIENT_ID")
                  anthropic-default-client-id)
   :authorize-url (or (env-nonblank "PSI_ANTHROPIC_OAUTH_AUTHORIZE_URL")
                      anthropic-default-authorize-url)
   :token-url (or (env-nonblank "PSI_ANTHROPIC_OAUTH_TOKEN_URL")
                  anthropic-default-token-url)
   :redirect-uri (or (env-nonblank "PSI_ANTHROPIC_OAUTH_REDIRECT_URI")
                     anthropic-default-redirect-uri)
   :scopes (or (env-nonblank "PSI_ANTHROPIC_OAUTH_SCOPES")
               (env-nonblank "ANTHROPIC_OAUTH_SCOPES")
               anthropic-default-scopes)})

(defn- anthropic-credential-metadata
  [{:keys [client-id token-url redirect-uri scopes]}]
  {:client-id client-id
   :token-url token-url
   :redirect-uri redirect-uri
   :scopes scopes})

(defn- anthropic-begin-login
  "Generate PKCE + authorize URL. Returns {:url ... :login-state ...}."
  []
  (let [{:keys [verifier challenge]} (pkce/generate-pkce)
        {:keys [client-id authorize-url redirect-uri scopes] :as oauth-config}
        (anthropic-oauth-config)
        auth-url (str authorize-url "?"
                      (build-query-string
                       {"code"                  "true"
                        "client_id"             client-id
                        "response_type"         "code"
                        "redirect_uri"          redirect-uri
                        "scope"                 scopes
                        "code_challenge"        challenge
                        "code_challenge_method" "S256"
                        "state"                 verifier}))]
    (open-browser! auth-url)
    {:url         auth-url
     :login-state (assoc (select-keys oauth-config
                                      [:client-id :token-url :redirect-uri :scopes])
                         :verifier verifier)}))

(defn- anthropic-complete-login
  "Exchange authorization code for tokens.
   `input` may be code#state, query params, full URL, or raw code.
   `login-state` is the map returned by begin-login."
  [input login-state]
  (let [{:keys [code state]} (parse-authorization-input input)
        expected-state       (:verifier login-state)
        oauth-config         (merge (anthropic-oauth-config)
                                    (select-keys login-state
                                                 [:client-id :token-url :redirect-uri :scopes]))
        {:keys [client-id token-url redirect-uri]} oauth-config]
    (when-not (seq code)
      (throw (ex-info "Missing authorization code" {:provider :anthropic})))
    (when (and (seq state) (not= state expected-state))
      (throw (ex-info "State mismatch (use the most recent /login URL/code and include full code+state)"
                      {:provider :anthropic
                       :expected-state expected-state
                       :received-state state})))
    (let [response (http/post token-url
                              {:content-type  :json
                               :as            :json
                               :cookie-policy :none
                               :body          (json/generate-string
                                               {"grant_type"    "authorization_code"
                                                "client_id"     client-id
                                                "code"          code
                                                "state"         (or state expected-state)
                                                "redirect_uri"  redirect-uri
                                                "code_verifier" (:verifier login-state)})})
          {:keys [access_token refresh_token expires_in]} (:body response)]
      {:type     :oauth
       :refresh  refresh_token
       :access   access_token
       :expires  (expires-at-from-seconds expires_in)
       :metadata (anthropic-credential-metadata oauth-config)})))

(defn- anthropic-login
  "Run Anthropic OAuth authorization code + PKCE flow (callback-based).

   Callbacks map:
     :on-auth     — (fn [{:keys [url]}]) show URL to user
     :on-prompt   — (fn [{:keys [message]}]) → user input string
     :on-progress — (fn [message]) optional progress updates"
  [callbacks]
  (let [{:keys [url login-state]} (anthropic-begin-login)]
    ((:on-auth callbacks) {:url url})
    (let [input ((:on-prompt callbacks) {:message "Paste the authorization code:"})]
      (anthropic-complete-login input login-state))))

(defn- refresh-anthropic-token
  [{:keys [client-id token-url]} refresh-token]
  (http/post token-url
             {:content-type  :json
              :as            :json
              :cookie-policy :none
              :body          (json/generate-string
                              {"grant_type"    "refresh_token"
                               "client_id"     client-id
                               "refresh_token" refresh-token})}))

(defn- anthropic-refresh
  "Refresh an Anthropic OAuth token."
  [credential]
  (let [stored-config   (select-keys (:metadata credential)
                                     [:client-id :token-url :redirect-uri :scopes])
        oauth-config    (merge (anthropic-oauth-config) stored-config)
        refresh-token   (:refresh credential)
        response        (refresh-anthropic-token oauth-config refresh-token)
        {:keys [access_token refresh_token expires_in]} (:body response)]
    {:type     :oauth
     :refresh  (or refresh_token (:refresh credential))
     :access   access_token
     :expires  (expires-at-from-seconds expires_in)
     :metadata (anthropic-credential-metadata oauth-config)}))

(def anthropic-provider
  {:id                   :anthropic
   :name                 "Anthropic (Claude Pro/Max)"
   :uses-callback-server false
   :login                anthropic-login
   :begin-login          anthropic-begin-login
   :complete-login       anthropic-complete-login
   :refresh-token        anthropic-refresh
   :get-api-key          :access})

;;; OpenAI OAuth provider

(def ^:private openai-client-id "app_EMoamEEZ73f0CkXaXp7hrann")
(def ^:private openai-authorize-url "https://auth.openai.com/oauth/authorize")
(def ^:private openai-token-url "https://auth.openai.com/oauth/token")
(def ^:private openai-redirect-uri "http://localhost:1455/auth/callback")
(def ^:private openai-scopes "openid profile email offline_access")

(defn- openai-begin-login
  "Generate PKCE + authorize URL for OpenAI OAuth.
   Starts a local callback server on /auth/callback when possible.
   Returns {:url ... :login-state ...}."
  []
  (let [{:keys [verifier challenge]} (pkce/generate-pkce)
        state           (str (UUID/randomUUID))
        callback-server (try
                          (cb/start-server {:port 1455 :path "/auth/callback"})
                          (catch Exception _ nil))
        redirect-uri    (if callback-server
                          (str "http://localhost:" (:port callback-server) "/auth/callback")
                          openai-redirect-uri)
        auth-url        (str openai-authorize-url "?"
                             (build-query-string
                              {"response_type"              "code"
                               "client_id"                  openai-client-id
                               "redirect_uri"               redirect-uri
                               "scope"                      openai-scopes
                               "code_challenge"             challenge
                               "code_challenge_method"      "S256"
                               "state"                      state
                               "id_token_add_organizations" "true"
                               "codex_cli_simplified_flow"  "true"
                               "originator"                 "psi"}))]
    (open-browser! auth-url)
    {:url         auth-url
     :login-state {:verifier        verifier
                   :state           state
                   :redirect-uri    redirect-uri
                   :callback-server callback-server}}))

(defn- openai-complete-login
  "Exchange OpenAI authorization code for tokens.
   `input` may be a raw code, query params, code#state, full URL, or nil.
   If input has no code and callback-server is present, waits for callback."
  [input login-state]
  (let [callback-server (:callback-server login-state)
        expected-state  (:state login-state)]
    (try
      (let [{input-code :code input-state :state}
            (parse-authorization-input input)
            callback-result (when (and (not (seq input-code)) callback-server)
                              ((:wait-for-code callback-server) 180000))
            code  (or input-code (:code callback-result))
            state (or input-state (:state callback-result))]
        (when-not (seq code)
          (throw (ex-info "Missing authorization code" {:provider :openai})))
        (when (and (seq state) (not= state expected-state))
          (throw (ex-info "State mismatch (use the most recent /login URL/code and include full code+state)"
                          {:provider :openai
                           :expected-state expected-state
                           :received-state state})))
        (let [response (http/post openai-token-url
                                  {:content-type  :x-www-form-urlencoded
                                   :as            :json
                                   :cookie-policy :none
                                   :form-params   {"grant_type"    "authorization_code"
                                                   "client_id"     openai-client-id
                                                   "code"          code
                                                   "code_verifier" (:verifier login-state)
                                                   "redirect_uri"  (or (:redirect-uri login-state)
                                                                       openai-redirect-uri)}})
              {:keys [access_token refresh_token expires_in]} (:body response)]
          (when-not (and (seq access_token) (seq refresh_token) (number? expires_in))
            (throw (ex-info "OpenAI token response missing fields"
                            {:provider :openai :body (:body response)})))
          {:type    :oauth
           :refresh refresh_token
           :access  access_token
           :expires (expires-at-from-seconds expires_in)}))
      (finally
        (when callback-server
          ((:close callback-server)))))))

(defn- openai-login
  "Run OpenAI OAuth authorization flow (callback-based).
   Users can paste a code/URL, or press Enter to wait for browser callback."
  [callbacks]
  (let [{:keys [url login-state]} (openai-begin-login)]
    ((:on-auth callbacks) {:url url})
    (let [input ((:on-prompt callbacks)
                 {:message "Paste authorization code/URL, or press Enter to wait for callback:"})]
      (openai-complete-login input login-state))))

(defn- openai-refresh
  "Refresh an OpenAI OAuth token."
  [credential]
  (let [response (http/post openai-token-url
                            {:content-type  :x-www-form-urlencoded
                             :as            :json
                             :cookie-policy :none
                             :form-params   {"grant_type"    "refresh_token"
                                             "refresh_token" (:refresh credential)
                                             "client_id"     openai-client-id}})
        {:keys [access_token refresh_token expires_in]} (:body response)]
    (when-not (and (seq access_token) (number? expires_in))
      (throw (ex-info "OpenAI refresh response missing fields"
                      {:provider :openai :body (:body response)})))
    {:type    :oauth
     :refresh (or refresh_token (:refresh credential))
     :access  access_token
     :expires (expires-at-from-seconds expires_in)}))

(def openai-provider
  {:id                   :openai
   :name                 "OpenAI (ChatGPT Plus/Pro)"
   :uses-callback-server true
   :login                openai-login
   :begin-login          openai-begin-login
   :complete-login       openai-complete-login
   :refresh-token        openai-refresh
   :get-api-key          :access})

;;; Registration

(defn register-default-providers!
  "Register all built-in OAuth providers."
  []
  (register-provider! anthropic-provider)
  (register-provider! openai-provider))
