(ns psi.project-nrepl.commands
  "Formatting + command dispatch helpers for managed project nREPL operations."
  (:require
   [clojure.string :as str]
   [psi.project-nrepl.config :as project-nrepl-config]
   [psi.project-nrepl.ops :as project-nrepl-ops]
   [psi.project-nrepl.runtime :as project-nrepl-runtime]
   [psi.session-state.state :as ss]))

(defn format-project-nrepl-status
  [ctx session-id]
  (let [worktree-path (ss/session-worktree-path-in ctx session-id)
        instance      (project-nrepl-runtime/instance-in ctx worktree-path)]
    (if-not instance
      (str "── Project nREPL ─────────────────────\n"
           "  worktree : " worktree-path "\n"
           "  state    : absent\n"
           "───────────────────────────────────────")
      (str "── Project nREPL ─────────────────────\n"
           "  worktree : " (:worktree-path instance) "\n"
           "  mode     : " (name (:acquisition-mode instance)) "\n"
           "  state    : " (name (:lifecycle-state instance)) "\n"
           "  ready    : " (boolean (:readiness instance)) "\n"
           (when-let [{:keys [host port port-source]} (:endpoint instance)]
             (str "  endpoint : " host ":" port " [" (name port-source) "]\n"))
           (when-let [session-id* (:active-session-id instance)]
             (str "  session  : " session-id* "\n"))
           (when-let [last-eval (:last-eval instance)]
             (str "  last-eval: " (name (:status last-eval))
                  (when-let [value (:value last-eval)]
                    (str " value=" (pr-str value)))
                  "\n"))
           (when-let [last-error (:last-error instance)]
             (str "  error    : " (:message last-error) "\n"))
           "───────────────────────────────────────"))))

(defn- parse-tail
  [trimmed prefix]
  (some-> trimmed
          (subs (count prefix))
          str/trim
          not-empty))

(defn- missing-start-command-message
  [worktree-path]
  (str "Project nREPL start requires a configured start-command for " worktree-path "\n"
       "Configure `:agent-session :project-nrepl :start-command` in either:\n"
       "- ~/.psi/agent/config.edn\n"
       "- " worktree-path "/.psi/project.edn (shared project defaults)\n"
       "- " worktree-path "/.psi/project.local.edn (local project overrides)\n"
       "\n"
       "Example:\n"
       "{:agent-session\n"
       " {:project-nrepl\n"
       "  {:start-command [\"bb\" \"nrepl-server\"]}}}"))

(defn dispatch-project-nrepl-command
  [ctx session-id trimmed]
  (let [worktree-path (ss/session-worktree-path-in ctx session-id)]
    (cond
      (= trimmed "/project-repl")
      {:type :text :message (format-project-nrepl-status ctx session-id)}

      (= trimmed "/project-repl start")
      (let [cfg            (project-nrepl-config/resolve-config worktree-path)
            command-vector (project-nrepl-config/resolved-start-command cfg)]
        (if-not command-vector
          {:type :text
           :message (missing-start-command-message worktree-path)}
          (let [{:keys [status instance]} (project-nrepl-ops/start ctx worktree-path)]
            {:type :text
             :message (case status
                        :present (str "Project nREPL already ready for " (:worktree-path instance)
                                      " at " (get-in instance [:endpoint :host]) ":" (get-in instance [:endpoint :port]))
                        :started (str "Started project nREPL for " (:worktree-path instance)
                                      " at " (get-in instance [:endpoint :host]) ":" (get-in instance [:endpoint :port]))
                        (str "Project nREPL start: " (name status)))})))

      (= trimmed "/project-repl attach")
      (let [cfg      (project-nrepl-config/resolve-config worktree-path)
            attach   (project-nrepl-config/resolved-attach-endpoint cfg)
            {:keys [instance]} (project-nrepl-ops/attach ctx worktree-path attach)]
        {:type :text
         :message (str "Attached project nREPL for " (:worktree-path instance)
                       " at " (get-in instance [:endpoint :host]) ":" (get-in instance [:endpoint :port]))})

      (= trimmed "/project-repl interrupt")
      (let [result (project-nrepl-ops/interrupt ctx worktree-path)]
        {:type :text
         :message (str "Project nREPL interrupt: " (name (:status result))
                       (when-let [reason (:reason result)]
                         (str " (" (name reason) ")")))})

      (= trimmed "/project-repl stop")
      (let [result (project-nrepl-ops/stop ctx worktree-path)]
        {:type :text
         :message (str "Stopped project nREPL for " worktree-path
                       (when-let [mode (:prior-acquisition-mode result)]
                         (str " (mode=" (name mode) ")")))})

      (str/starts-with? trimmed "/project-repl eval ")
      (let [code   (parse-tail trimmed "/project-repl eval ")
            result (project-nrepl-ops/eval-op ctx worktree-path code)]
        {:type :text
         :message (str "Project nREPL eval " (name (:status result))
                       (when-let [value (:value result)]
                         (str "\n" value))
                       (when-let [out (:out result)]
                         (str "\nout: " out))
                       (when-let [err (:err result)]
                         (str "\nerr: " err)))})

      :else nil)))
