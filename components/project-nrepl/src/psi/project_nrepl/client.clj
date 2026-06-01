(ns psi.project-nrepl.client
  "nREPL socket + managed client-session connection for project nREPL instances."
  (:require
   [psi.project-nrepl.config :as project-nrepl-config]
   [psi.project-nrepl.runtime :as project-nrepl-runtime]))

(defn real-nrepl-connector
  "Real-default `:nrepl-connector` seam implementation.

   Establishes a real nREPL `connect → client → client-session` against
   `host`/`port`, returning `{:transport :client :client-session}`. Session-id
   derivation is intentionally NOT part of this seam — it stays in
   `connect-instance-in!` (interpretation of the returned session fn)."
  [{:keys [host port]}]
  (let [connect        (requiring-resolve 'nrepl.core/connect)
        client         (requiring-resolve 'nrepl.core/client)
        client-session (requiring-resolve 'nrepl.core/client-session)
        transport      (connect :host host :port port)
        client-fn      (client transport 1000)
        session-fn     (client-session client-fn)]
    {:transport      transport
     :client         client-fn
     :client-session session-fn}))

(defn connect-instance-in!
  "Connect to the discovered nREPL endpoint for a managed project instance and
   establish the first-slice single managed client session."
  [ctx worktree-path]
  (let [effective-worktree (project-nrepl-config/absolute-directory-path! worktree-path)
        instance           (project-nrepl-runtime/instance-in ctx effective-worktree)]
    (when-not instance
      (throw (ex-info "Managed project nREPL instance not found"
                      {:phase :connect-instance
                       :worktree-path effective-worktree})))
    (let [{:keys [host port]} (:endpoint instance)]
      (when-not (and (string? host) (integer? port))
        (throw (ex-info "Managed project nREPL instance requires discovered host/port before connect"
                        {:phase :connect-instance
                         :worktree-path effective-worktree
                         :endpoint (:endpoint instance)})))
      (let [connector      (or (get-in instance [:runtime-handle :nrepl-connector])
                               real-nrepl-connector)
            {:keys [transport client client-session]} (connector {:host host :port port})
            session-id     (or (-> client-session meta (get (keyword "nrepl.core" "taking-until")) :session)
                               (throw (ex-info "Managed project nREPL client session did not expose a session id"
                                               {:phase :connect-instance
                                                :worktree-path effective-worktree
                                                :endpoint (:endpoint instance)})))]
        (project-nrepl-runtime/update-instance-in!
         ctx effective-worktree
         #(-> %
              (assoc :lifecycle-state :ready
                     :readiness true
                     :active-session-id session-id
                     :can-eval? true
                     :can-interrupt? true
                     :runtime-handle (merge (:runtime-handle %)
                                            {:transport transport
                                             :client client
                                             :client-session client-session
                                             :session-id session-id})
                     :last-error nil)))))))

(defn disconnect-instance-in!
  [ctx worktree-path]
  (let [effective-worktree (project-nrepl-config/absolute-directory-path! worktree-path)
        instance           (project-nrepl-runtime/instance-in ctx effective-worktree)
        transport          (get-in instance [:runtime-handle :transport])]
    (when (instance? java.io.Closeable transport)
      (.close ^java.io.Closeable transport))
    (project-nrepl-runtime/update-instance-in!
     ctx effective-worktree
     #(-> %
          (assoc :readiness false
                 :active-session-id nil)
          (update :runtime-handle dissoc :transport :client :client-session :session-id)))))
