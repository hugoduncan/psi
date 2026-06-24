(ns psi.rpc.session.login
  "OAuth/login helpers for RPC session workflows."
  (:require
   [clojure.string :as str]
   [psi.agent-session.commands :as commands]
   [psi.provider-auth.oauth.core :as oauth]
   [psi.agent-session.state-accessors :as sa]
   [psi.rpc.session.emit :as emit]
   [psi.rpc.state :as rpc.state]
   [psi.rpc.transport :refer [response-frame]]))

(defn normalize-provider-param
  [v]
  (cond
    (nil? v) nil
    (keyword? v) (name v)
    (string? v)  (let [trimmed (str/trim v)]
                   (when-not (str/blank? trimmed)
                     trimmed))
    :else        ::invalid))

(defn pending-login-state
  [ctx]
  (:pending-login (or (sa/oauth-projection-in ctx) {})))

(defn handle-login-begin!
  [{:keys [ctx request params session-id session-deps current-ai-model]}]
  (let [oauth-ctx (:oauth-ctx ctx)]
    (when-not oauth-ctx
      (throw (ex-info "OAuth not available."
                      {:error-code "request/invalid-params"})))
    (when (pending-login-state ctx)
      (throw (ex-info "login already in progress"
                      {:error-code "request/invalid-params"})))
    (let [provider-param (normalize-provider-param (get params :provider))
          _              (when (= ::invalid provider-param)
                           (throw (ex-info "invalid request parameter :provider: string or keyword"
                                           {:error-code "request/invalid-params"})))
          ai-model       (current-ai-model ctx session-deps session-id)
          _              (when (and (nil? provider-param) (nil? ai-model))
                           (throw (ex-info "provider is required when session model is not configured"
                                           {:error-code "request/invalid-params"})))
          providers      (oauth/available-providers oauth-ctx)
          {:keys [provider error]}
          (commands/select-login-provider providers
                                          (or (:provider ai-model) provider-param)
                                          provider-param)]
      (when error
        (throw (ex-info error
                        {:error-code "request/invalid-params"})))
      (let [{:keys [url login-state]} (oauth/begin-login! oauth-ctx (:id provider))
            callback? (boolean (:uses-callback-server provider))]
        (sa/set-oauth-pending-login-in! ctx
                                        {:provider-id   (:id provider)
                                         :provider-name (:name provider)
                                         :login-state   login-state})
        (response-frame (:id request) "login_begin" true
                        {:provider {:id (name (:id provider))
                                    :name (:name provider)}
                         :url url
                         :uses-callback-server callback?
                         :pending-login true})))))

(defn handle-login-complete!
  [{:keys [ctx request params]}]
  (let [oauth-ctx (:oauth-ctx ctx)]
    (when-not oauth-ctx
      (throw (ex-info "OAuth not available."
                      {:error-code "request/invalid-params"})))
    (when-not (or (nil? (:input params)) (string? (:input params)))
      (throw (ex-info "invalid request parameter :input: string or nil"
                      {:error-code "request/invalid-params"})))
    (let [{:keys [provider-id provider-name login-state]} (pending-login-state ctx)]
      (when-not provider-id
        (throw (ex-info "no pending login"
                        {:error-code "request/no-pending-login"})))
      (let [trimmed (some-> (:input params) str/trim)
            input   (when-not (str/blank? trimmed) trimmed)]
        (oauth/complete-login! oauth-ctx provider-id input login-state)
        (sa/complete-oauth-login-in! ctx provider-id)
        (response-frame (:id request) "login_complete" true
                        {:provider {:id (name provider-id)
                                    :name provider-name}
                         :logged-in true})))))

(defn complete-pending-login!
  [{:keys [ctx session-id message emit!]}]
  (when-let [{:keys [provider-id provider-name login-state]} (pending-login-state ctx)]
    (if-let [oauth-ctx (:oauth-ctx ctx)]
      (try
        (let [trimmed (some-> message str/trim)
              input   (when-not (str/blank? trimmed) trimmed)]
          (oauth/complete-login! oauth-ctx provider-id input login-state)
          (sa/complete-oauth-login-in! ctx provider-id)
          (emit/emit-assistant-text! emit! session-id (str "✓ Logged in to " (or provider-name
                                                                                 (some-> provider-id name)
                                                                                 "provider"))))
        (catch Throwable e
          (sa/complete-oauth-login-in! ctx provider-id)
          (emit/emit-assistant-text! emit! session-id (str "✗ Login failed: " (ex-message e)))))
      (emit/emit-assistant-text! emit! session-id "OAuth not available."))))

(defn handle-login-start-command!
  [{:keys [ctx state session-id emit-frame! request-id cmd-result emit! start-daemon-thread!]}]
  (let [provider-id   (get-in cmd-result [:provider :id])
        provider-name (or (get-in cmd-result [:provider :name])
                          (some-> provider-id name)
                          "provider")
        login-state   (:login-state cmd-result)
        callback?     (boolean (:uses-callback-server cmd-result))
        url-line      (str "Login: " provider-name
                           " — open URL: " (:url cmd-result))]
    ;; Emit a single combined message per branch: emacs coalesces consecutive
    ;; assistant messages, so a separate follow-up line would be dropped.
    (if callback?
      (do
        (emit/emit-assistant-text! emit! session-id
                                   (str url-line "\n\nWaiting for browser callback…"))
        (if-let [oauth-ctx (:oauth-ctx ctx)]
          (let [worker (start-daemon-thread!
                        (fn []
                          (binding [*out* (rpc.state/err-writer state)
                                    *err* (rpc.state/err-writer state)]
                            (let [emit-login! (emit/make-request-emitter emit-frame! state request-id)]
                              (try
                                (oauth/complete-login! oauth-ctx provider-id nil login-state)
                                (emit/emit-assistant-text! emit-login! session-id (str "✓ Logged in to " provider-name))
                                (catch Throwable e
                                  (emit/emit-assistant-text! emit-login! session-id (str "✗ Login failed: " (ex-message e))))
                                (finally
                                  (emit/emit-session-snapshots! emit-login! ctx state session-id))))))
                        "rpc-oauth-worker")]
            (rpc.state/add-inflight-future! state worker))
          (emit/emit-assistant-text! emit! session-id "OAuth not available.")))
      (do
        (sa/set-oauth-pending-login-in! ctx
                                        {:provider-id provider-id
                                         :provider-name provider-name
                                         :login-state login-state})
        (emit/emit-assistant-text! emit! session-id
                                   (str url-line
                                        "\n\nPaste the authorization code as your next prompt message."))))))
