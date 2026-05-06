(ns psi.agent-session.state-accessors
  "Named semantic accessors and mutators for canonical session state slices.
   Thin wrappers over state-path + get-state-value-in / dispatch."
  (:require
   [psi.agent-session.dispatch :as dispatch]
   [psi.agent-session.oauth.core :as oauth]
   [psi.session-state.state :as session]))

;;; Readers — non-session-scoped (no session-id required)

(defn nrepl-runtime-in
  "Return canonical runtime-visible nREPL metadata."
  [ctx]
  (session/get-state-value-in ctx (session/state-path :nrepl-runtime)))

(defn rpc-trace-state-in
  "Return canonical runtime-visible RPC trace state."
  [ctx]
  (session/get-state-value-in ctx (session/state-path :rpc-trace)))

(defn oauth-projection-in
  "Return canonical runtime-visible oauth projection state."
  [ctx]
  (session/get-state-value-in ctx (session/state-path :oauth)))

(defn recursion-state-in
  "Return canonical recursion projection state."
  [ctx]
  (session/get-state-value-in ctx (session/state-path :recursion)))

;;; Readers — session-scoped (explicit session-id required)

(defn turn-context-in
  "Return the current turn statechart context from canonical runtime state."
  [ctx session-id]
  (session/get-state-value-in ctx (session/state-path :turn-ctx session-id)))

(defn journal-state-in
  "Return the canonical in-memory journal vector."
  [ctx session-id]
  (session/get-state-value-in ctx (session/state-path :journal session-id)))

(defn flush-state-in
  "Return canonical persistence flush-state metadata."
  [ctx session-id]
  (session/get-state-value-in ctx (session/state-path :flush-state session-id)))

(defn tool-output-stats-in
  "Return canonical tool-output statistics state."
  [ctx session-id]
  (session/get-state-value-in ctx (session/state-path :tool-output-stats session-id)))

(defn tool-call-attempts-in
  "Return canonical tool-call attempt telemetry vector."
  [ctx session-id]
  (session/get-state-value-in ctx (session/state-path :tool-call-attempts session-id)))

(defn tool-lifecycle-events-in
  "Return canonical tool lifecycle telemetry vector."
  [ctx session-id]
  (session/get-state-value-in ctx (session/state-path :tool-lifecycle-events session-id)))

(defn provider-requests-in
  "Return canonical provider request capture vector."
  [ctx session-id]
  (session/get-state-value-in ctx (session/state-path :provider-requests session-id)))

(defn provider-replies-in
  "Return canonical provider reply capture vector."
  [ctx session-id]
  (session/get-state-value-in ctx (session/state-path :provider-replies session-id)))

;;; Mutators — non-session-scoped

(defn set-oauth-projection-in!
  "Persist runtime-visible OAuth projection data.
   Routed through the dispatch pipeline."
  [ctx oauth]
  (dispatch/dispatch! ctx :session/set-oauth-projection {:oauth oauth} {:origin :core})
  (oauth-projection-in ctx))

;;; Mutators — session-scoped (explicit session-id required)

(defn set-nrepl-runtime-in!
  "Persist canonical runtime-visible nREPL metadata.
   Routed through the dispatch pipeline."
  [ctx session-id runtime]
  (dispatch/dispatch! ctx :session/set-nrepl-runtime {:session-id session-id :runtime runtime} {:origin :core})
  (nrepl-runtime-in ctx))

(defn set-recursion-state-in!
  "Persist canonical recursion state projection.
   Routed through the dispatch pipeline."
  [ctx session-id recursion-state]
  (dispatch/dispatch! ctx :session/set-recursion-state {:session-id session-id :recursion-state recursion-state} {:origin :core})
  (recursion-state-in ctx))

(defn set-turn-context-in!
  "Persist the current turn statechart context into canonical runtime state.
   Intended for executor/runtime introspection plumbing.
   Routed through the dispatch pipeline."
  [ctx session-id turn-ctx]
  (dispatch/dispatch! ctx :session/set-turn-context {:session-id session-id :turn-ctx turn-ctx} {:origin :core})
  (session/get-state-value-in ctx (session/state-path :turn-ctx session-id)))

(defn append-tool-call-attempt-in!
  "Append one tool-call attempt telemetry entry into canonical state.
   Intended for executor-side telemetry capture.
   Routed through the dispatch pipeline."
  [ctx session-id attempt]
  (dispatch/dispatch! ctx :session/append-tool-call-attempt {:session-id session-id :attempt attempt} {:origin :core})
  (session/get-state-value-in ctx (session/state-path :tool-call-attempts session-id)))

(defn append-provider-request-capture-in!
  "Append one provider request capture into canonical state with bounded retention.
   Intended for executor-side provider telemetry capture.
   Routed through the dispatch pipeline."
  [ctx session-id capture]
  (dispatch/dispatch! ctx :session/append-provider-request-capture {:session-id session-id :capture capture} {:origin :core})
  (session/get-state-value-in ctx (session/state-path :provider-requests session-id)))

(defn append-provider-reply-capture-in!
  "Append one provider reply capture into canonical state with bounded retention.
   Intended for executor-side provider telemetry capture.
   Routed through the dispatch pipeline."
  [ctx session-id capture]
  (dispatch/dispatch! ctx :session/append-provider-reply-capture {:session-id session-id :capture capture} {:origin :core})
  (session/get-state-value-in ctx (session/state-path :provider-replies session-id)))

(defn record-tool-output-stat-in!
  "Record one tool-output statistics entry into canonical state.
   Intended for executor-side tool output accounting.
   Routed through the dispatch pipeline."
  [ctx session-id stat context-bytes-added limit-hit?]
  (dispatch/dispatch! ctx
                      :session/record-tool-output-stat
                      {:session-id          session-id
                       :stat                stat
                       :context-bytes-added context-bytes-added
                       :limit-hit?          limit-hit?}
                      {:origin :core})
  (session/get-state-value-in ctx (session/state-path :tool-output-stats session-id)))

(defn resolve-active-dialog-in!
  "Resolve the active canonical UI dialog and advance the queue.
   Public named API so transport code does not own dialog queue transition logic."
  [ctx session-id dialog-id result]
  (-> (dispatch/dispatch! ctx
                          :session/ui-resolve-dialog
                          {:session-id session-id :dialog-id dialog-id :result result}
                          {:origin :rpc})
      :accepted?
      boolean))

(defn cancel-active-dialog-in!
  "Cancel the active canonical UI dialog and advance the queue.
   Public named API so transport code does not own dialog queue transition logic."
  [ctx session-id]
  (-> (dispatch/dispatch! ctx
                          :session/ui-cancel-dialog
                          {:session-id session-id}
                          {:origin :rpc})
      :accepted?
      boolean))

;;; OAuth helpers — non-session-scoped

(defn update-oauth-authenticated-providers-in!
  "Update only the authenticated-providers field of the canonical oauth projection."
  [ctx ids]
  (let [oauth* (assoc (or (oauth-projection-in ctx) {})
                      :authenticated-providers ids)]
    (set-oauth-projection-in! ctx oauth*)))

(defn refresh-oauth-authenticated-providers-in!
  "Refresh canonical authenticated-provider ids from the oauth runtime context.
   Public named API so adjacent namespaces do not own oauth projection refresh logic."
  [ctx]
  (let [ids (if-let [oauth-ctx (:oauth-ctx ctx)]
              (->> (oauth/available-providers oauth-ctx)
                   (keep (fn [provider]
                           (let [provider-id (:id provider)]
                             (when (and provider-id
                                        (oauth/has-auth? oauth-ctx provider-id))
                               (name provider-id)))))
                   distinct
                   sort
                   vec)
              [])]
    (update-oauth-authenticated-providers-in! ctx ids)
    ids))

(defn set-oauth-pending-login-in!
  "Set the canonical oauth pending-login projection.
   Intended for RPC login_begin flows."
  [ctx {:keys [provider-id provider-name login-state]}]
  (let [oauth* (assoc (or (oauth-projection-in ctx) {})
                      :pending-login {:provider-id provider-id
                                      :provider-name provider-name
                                      :login-state login-state})]
    (set-oauth-projection-in! ctx oauth*)))

(defn complete-oauth-login-in!
  "Clear canonical oauth pending-login and record last-login metadata.
   Intended for RPC login_complete flows."
  [ctx provider-id]
  (let [oauth* (assoc (or (oauth-projection-in ctx) {})
                      :pending-login nil
                      :last-login-provider (name provider-id)
                      :last-login-at (java.time.Instant/now))]
    (set-oauth-projection-in! ctx oauth*)))
