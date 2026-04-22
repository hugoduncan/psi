(ns psi.agent-session.project-nrepl-attach
  "Attach-mode acquisition for managed project nREPL instances."
  (:require
   [psi.agent-session.project-nrepl-client :as project-nrepl-client]
   [psi.agent-session.project-nrepl-config :as project-nrepl-config]
   [psi.agent-session.project-nrepl-runtime :as project-nrepl-runtime]))

(defn resolve-attach-endpoint
  "Resolve attach endpoint from explicit input or worktree-local `.nrepl-port`.

   Precedence:
   1. explicit port (+ optional host)
   2. `.nrepl-port` in effective worktree

   Host defaults to 127.0.0.1 when not supplied."
  [worktree-path attach-input]
  (let [effective-worktree (project-nrepl-config/absolute-directory-path! worktree-path)
        explicit-endpoint  (project-nrepl-config/resolved-attach-endpoint
                            {:project-nrepl {:attach attach-input}})]
    (cond
      (integer? (:port explicit-endpoint))
      {:host        (or (:host explicit-endpoint) "127.0.0.1")
       :port        (:port explicit-endpoint)
       :port-source :explicit}

      :else
      (let [{:keys [port port-source]} (project-nrepl-config/read-dot-nrepl-port effective-worktree)]
        {:host        (or (:host explicit-endpoint) "127.0.0.1")
         :port        port
         :port-source port-source}))))

(defn attach-instance-in!
  "Attach to an existing nREPL endpoint and establish the managed client session."
  ([ctx worktree-path]
   (attach-instance-in! ctx worktree-path {}))
  ([ctx worktree-path attach-input]
   (let [effective-worktree (project-nrepl-config/absolute-directory-path! worktree-path)
         endpoint           (resolve-attach-endpoint effective-worktree attach-input)]
     (try
       (project-nrepl-runtime/ensure-instance-in!
        ctx
        {:worktree-path effective-worktree
         :acquisition-mode :attached
         :endpoint endpoint})
       (project-nrepl-client/connect-instance-in! ctx effective-worktree)
       (catch Throwable t
         (if (project-nrepl-runtime/instance-in ctx effective-worktree)
           (project-nrepl-runtime/update-instance-in!
            ctx effective-worktree
            #(assoc %
                    :lifecycle-state :failed
                    :readiness false
                    :endpoint endpoint
                    :last-error {:message (.getMessage t)
                                 :data (ex-data t)
                                 :at (java.time.Instant/now)}))
           (project-nrepl-runtime/replace-instance-in!
            ctx
            {:worktree-path effective-worktree
             :acquisition-mode :attached
             :endpoint endpoint
             :lifecycle-state :failed
             :last-error {:message (.getMessage t)
                          :data (ex-data t)
                          :at (java.time.Instant/now)}}))
         (throw t))))))

(defn detach-instance-in!
  [ctx worktree-path]
  (let [effective-worktree (project-nrepl-config/absolute-directory-path! worktree-path)]
    (project-nrepl-client/disconnect-instance-in! ctx effective-worktree)
    (project-nrepl-runtime/update-instance-in!
     ctx effective-worktree
     #(assoc %
             :lifecycle-state :stopping
             :readiness false))
    (project-nrepl-runtime/remove-instance-in! ctx effective-worktree)))
