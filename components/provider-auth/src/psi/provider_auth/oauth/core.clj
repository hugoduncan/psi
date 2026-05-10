(ns psi.provider-auth.oauth.core
  "Top-level OAuth API — orchestrates store, providers, and login flows.

   Nullable pattern: `create-context` for production (file-backed store,
   real providers), `create-null-context` for tests (in-memory store,
   stub providers).

   An OAuthContext bundles:
     :store        — credential store (see oauth.store)
     :providers-fn — (fn [] providers) to list available providers
     :get-provider — (fn [id] provider) to look up a provider"
  (:require [psi.provider-auth.oauth.store :as store]
            [psi.provider-auth.oauth.providers :as providers]))

;;; Context factories

(defn create-context
  "Create a production OAuth context with file-backed store and
   registered providers.

   Options:
     :auth-path — path to auth.json (default ~/.psi/agent/auth.json)"
  ([] (create-context {}))
  ([{:keys [auth-path]}]
   (providers/register-default-providers!)
   {:store        (if auth-path
                    (store/create-store {:path auth-path})
                    (store/create-store))
    :providers-fn providers/list-providers
    :get-provider providers/get-provider}))

(defn create-null-context
  "Create a test OAuth context with in-memory store and optional
   stub providers.

   Options:
     :credentials — initial credential map (seed the store)
     :providers   — seq of provider maps to register (overrides default)
     :login-fn    — (fn [callbacks]) override for all provider logins
     :refresh-fn  — (fn [credential]) override for all token refreshes

   Example:
     (create-null-context
       {:credentials {:anthropic {:type :api-key :key \"sk-test\"}}})

     (create-null-context
       {:providers [{:id :test-provider :name \"Test\"
                     :login (fn [_] {:type :oauth :access \"tok\" :refresh \"ref\"
                                     :expires (+ (System/currentTimeMillis) 3600000)})}]})"
  ([] (create-null-context {}))
  ([{:keys [credentials providers login-fn refresh-fn]}]
   (let [mk-test-provider
         (fn [id name]
           {:id                   id
            :name                 name
            :uses-callback-server false
            :login                (or login-fn
                                      (fn [_callbacks]
                                        {:type    :oauth
                                         :refresh "test-refresh"
                                         :access  "test-access"
                                         :expires (+ (System/currentTimeMillis)
                                                     3600000)}))
            :begin-login          (fn []
                                    {:url         "https://test.example.com/auth"
                                     :login-state {:verifier "test-verifier" :state "test-state"}})
            :complete-login       (fn [_input _login-state]
                                    {:type    :oauth
                                     :refresh "test-refresh"
                                     :access  "test-access"
                                     :expires (+ (System/currentTimeMillis)
                                                 3600000)})
            :refresh-token        (or refresh-fn
                                      (fn [_cred]
                                        {:type    :oauth
                                         :refresh "refreshed-refresh"
                                         :access  "refreshed-access"
                                         :expires (+ (System/currentTimeMillis)
                                                     3600000)}))
            :get-api-key          :access})
         test-providers (or providers
                            [(mk-test-provider :anthropic "Anthropic (Test)")
                             (mk-test-provider :openai "OpenAI (Test)")])
         provider-map   (into {} (map (fn [p] [(:id p) p]) test-providers))]
     {:store        (store/create-null-store (or credentials {}))
      :providers-fn (fn [] test-providers)
      :get-provider (fn [id] (get provider-map (keyword id)))})))

;;; Public API

(defn login!
  "Run the OAuth login flow for a provider.

   `callbacks` is a map:
     :on-auth     — (fn [{:keys [url instructions]}]) show URL to user
     :on-prompt   — (fn [{:keys [message placeholder]}]) → user input string
     :on-progress — (fn [message]) optional progress
     :signal      — atom, set to true to cancel

   Returns the credential map on success. Throws on failure."
  [ctx provider-id callbacks]
  (let [provider ((:get-provider ctx) provider-id)]
    (when-not provider
      (throw (ex-info (str "Unknown OAuth provider: " provider-id)
                      {:provider-id provider-id})))
    (let [credential ((:login provider) callbacks)]
      (store/set-credential! (:store ctx) provider-id credential)
      credential)))

(defn begin-login!
  "Start a two-step login. Returns {:url ... :login-state ... :provider-id ...}.
   Does NOT block — caller shows URL, collects code, then calls complete-login!."
  [ctx provider-id]
  (let [provider ((:get-provider ctx) provider-id)]
    (when-not provider
      (throw (ex-info (str "Unknown OAuth provider: " provider-id)
                      {:provider-id provider-id})))
    (let [result ((:begin-login provider))]
      (assoc result :provider-id provider-id))))

(defn complete-login!
  "Finish a two-step login. `input` is the authorization code string.
   `login-state` is the :login-state from begin-login!.
   Exchanges code for tokens and stores the credential."
  [ctx provider-id input login-state]
  (let [provider ((:get-provider ctx) provider-id)]
    (when-not provider
      (throw (ex-info (str "Unknown OAuth provider: " provider-id)
                      {:provider-id provider-id})))
    (let [credential ((:complete-login provider) input login-state)]
      (store/set-credential! (:store ctx) provider-id credential)
      credential)))

(defn logout!
  "Remove credentials for a provider."
  [ctx provider-id]
  (store/remove-credential! (:store ctx) provider-id))

(defn refresh-token!
  "Refresh an expired OAuth token for a provider.
   Uses file locking for cross-process safety. Re-reads from disk inside
   the lock — if another process already refreshed, uses their result.
   Returns the new credential."
  [ctx provider-id]
  (let [provider ((:get-provider ctx) provider-id)]
    (when-not provider
      (throw (ex-info (str "Unknown OAuth provider: " provider-id)
                      {:provider-id provider-id})))
    (store/with-locked-refresh! (:store ctx) provider-id
      (fn [current]
        (when-not (= :oauth (:type current))
          (throw (ex-info (str "No OAuth credential for: " provider-id)
                          {:provider-id provider-id})))
        ;; If another process already refreshed, skip
        (if (< (System/currentTimeMillis) (:expires current 0))
          current
          ((:refresh-token provider) current))))))

(defn get-api-key
  "Resolve API key for a provider. Auto-refreshes expired OAuth tokens.
   Returns the API key string or nil.

   Reloads backing storage first so multiple psi processes (CLI/TUI/RPC)
   observe credential updates written by each other."
  [ctx provider-id]
  (let [s (:store ctx)]
    ;; Reload first so cross-process logins/refreshes are visible immediately.
    (store/reload! s)
    ;; Check if OAuth token needs refresh
    (when (store/oauth-expired? s provider-id)
      (try
        (refresh-token! ctx provider-id)
        (catch Exception _e
          ;; Refresh failed — reload store in case another process succeeded
          (store/reload! s))))
    (store/get-api-key s provider-id)))

(defn has-auth?
  "Quick check: is any form of auth configured for a provider?
   Does not refresh tokens."
  [ctx provider-id]
  (store/has-auth? (:store ctx) provider-id))

(defn available-providers
  "Return all registered OAuth providers."
  [ctx]
  ((:providers-fn ctx)))

(defn logged-in-providers
  "Return providers that have OAuth credentials stored."
  [ctx]
  (let [s (:store ctx)]
    (filter (fn [p]
              (let [cred (store/get-credential s (:id p))]
                (= :oauth (:type cred))))
            ((:providers-fn ctx)))))
