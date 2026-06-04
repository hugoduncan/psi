(ns psi.project-nrepl.ops
  "Canonical managed project nREPL operations and structured result shaping.

   Shared by psi-tool and /project-repl command handling. Contains no
   slash-command parsing and no command-formatted response text."
  (:require
   [psi.project-nrepl.attach :as project-nrepl-attach]
   [psi.project-nrepl.config :as project-nrepl-config]
   [psi.project-nrepl.eval :as project-nrepl-eval]
   [psi.project-nrepl.runtime :as project-nrepl-runtime]
   [psi.project-nrepl.started :as project-nrepl-started]
   [psi.session-state.state :as ss]))

(defn resolved-worktree-path!
  [ctx session-id explicit-worktree-path]
  (-> (project-nrepl-config/resolve-target-worktree
       {:target-worktree-path explicit-worktree-path
        :session-worktree-path (when (and ctx session-id)
                                 (ss/session-worktree-path-in ctx session-id))})
      project-nrepl-config/absolute-directory-path!))

(defn- instance-payload
  [instance]
  (when instance
    {:worktree-path        (:worktree-path instance)
     :acquisition-mode     (:acquisition-mode instance)
     :lifecycle-state      (:lifecycle-state instance)
     :readiness            (boolean (:readiness instance))
     :readiness-timeout-ms (:readiness-timeout-ms instance)
     :endpoint             (:endpoint instance)
     :active-session-id    (:active-session-id instance)
     :last-eval            (:last-eval instance)
     :last-error           (:last-error instance)}))

(defn status
  [ctx worktree-path]
  (if-let [instance (project-nrepl-runtime/instance-in ctx worktree-path)]
    {:status :present
     :instance (instance-payload instance)}
    {:status :absent
     :worktree-path worktree-path}))

(defn- missing-start-command-result
  [worktree-path]
  (let [config-paths ["~/.psi/agent/config.edn"
                      (str worktree-path "/.psi/project.edn")
                      (str worktree-path "/.psi/project.local.edn")]
        message      (str "Project nREPL start requires a configured start-command for " worktree-path "\n"
                          "Configure `:agent-session :project-nrepl :start-command` in either:\n"
                          "- ~/.psi/agent/config.edn\n"
                          "- " worktree-path "/.psi/project.edn (shared project defaults)\n"
                          "- " worktree-path "/.psi/project.local.edn (local project overrides)\n"
                          "\n"
                          "Example:\n"
                          "{:agent-session\n"
                          " {:project-nrepl\n"
                          "  {:start-command [\"bb\" \"nrepl-server\"]}}}")]
    {:status :missing-start-command
     :worktree-path worktree-path
     :phase :config
     :config-paths config-paths
     :message message
     :hint "Configure :agent-session :project-nrepl :start-command in user or project config before starting project-repl."
     :example-config {:agent-session {:project-nrepl {:start-command ["bb" "nrepl-server"]}}}}))

(defn start
  [ctx worktree-path]
  (let [cfg            (project-nrepl-config/resolve-config worktree-path)
        command-vector (project-nrepl-config/resolved-start-command cfg)]
    (if-not command-vector
      (missing-start-command-result worktree-path)
      (let [existing (project-nrepl-runtime/instance-in ctx worktree-path)]
        (if (and existing (:readiness existing))
          {:status :present
           :instance (instance-payload existing)}
          (let [timeout-ms (project-nrepl-config/resolved-start-readiness-timeout-ms cfg)
                opts       (cond-> {}
                             (some? timeout-ms) (assoc :timeout-ms timeout-ms))
                instance   (project-nrepl-started/start-instance-in!
                            ctx worktree-path command-vector opts)]
            {:status :started
             :instance (instance-payload instance)}))))))

(defn attach
  [ctx worktree-path attach-input]
  (let [instance (project-nrepl-attach/attach-instance-in! ctx worktree-path attach-input)]
    {:status :attached
     :instance (instance-payload instance)}))

(defn stop
  [ctx worktree-path]
  (let [instance   (project-nrepl-runtime/instance-in ctx worktree-path)
        prior-mode (:acquisition-mode instance)]
    (when instance
      (if (= :started prior-mode)
        (project-nrepl-started/stop-started-instance-in! ctx worktree-path)
        (project-nrepl-attach/detach-instance-in! ctx worktree-path)))
    {:status :stopped
     :had-instance? (boolean instance)
     :prior-acquisition-mode prior-mode}))

(defn- public-eval-payload
  ([result]
   (public-eval-payload result (:status result)))
  ([result public-status]
   {:status public-status
    :value (:value result)
    :out (:out result)
    :err (:err result)
    :ns (:ns result)
    :timing {:started-at (:started-at result)
             :finished-at (:finished-at result)}}))

(defn eval-op
  [ctx worktree-path code]
  (let [result (project-nrepl-eval/eval-instance-in! ctx worktree-path code)]
    (case (:status result)
      :success (public-eval-payload result :ok)
      :error (public-eval-payload result :eval-error)
      :interrupted (public-eval-payload result :interrupted)
      :unavailable {:status :unavailable
                    :error (:error result)}
      {:status (:status result)
       :value (:value result)
       :out (:out result)
       :err (:err result)
       :ns (:ns result)})))

(defn interrupt
  [ctx worktree-path]
  (let [result (project-nrepl-eval/interrupt-instance-in! ctx worktree-path)]
    {:status (case (:status result)
               :success :ok
               :interrupted :ok
               :unavailable :unavailable
               (:status result))
     :reason (or (:reason result)
                 (when (= :interrupted (:status result)) :interrupted))}))

(defn perform!
  [ctx session-id {:keys [op worktree-path host port code]}]
  (let [effective-worktree (resolved-worktree-path! ctx session-id worktree-path)]
    {:worktree-path effective-worktree
     :project-repl
     (case op
       "status" (status ctx effective-worktree)
       "start" (start ctx effective-worktree)
       "attach" (attach ctx effective-worktree (cond-> {}
                                                 (some? host) (assoc :host host)
                                                 (some? port) (assoc :port port)))
       "stop" (stop ctx effective-worktree)
       "eval" (eval-op ctx effective-worktree code)
       "interrupt" (interrupt ctx effective-worktree))}))
