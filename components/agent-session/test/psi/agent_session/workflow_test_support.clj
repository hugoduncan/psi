(ns psi.agent-session.workflow-test-support
  "Small built-in workflow test helpers.

   Keeps repeated setup explicit while compressing ceremony for higher-core
   workflow tests."
  (:require
   [psi.agent-session.core :as session]
   [psi.agent-session.workflow.bootstrap :as workflow-bootstrap]
   [psi.agent-session.workflow.core]
   [psi.test-support.repo-root :as test-repo-root]
   [psi.workflow-loader.core :as workflow-file-loader]
   [psi.workflow-registry.registry :as workflow-registry]))

(def workflow-extensions-cwd
  "Repo root (shared walk-up helper, review 41) so the session worktree (the
   profile snapshot reads <cwd>/.psi/project.edn via shared-config, a strict
   cwd path with no walk-up) and the workflow-definitions load
   (<cwd>/.psi/workflows) target the committed project config instead of
   silently missing from a component-local cwd (review 40: the delegate
   review live test failed from a component-local cwd with \"Unknown
   workflow review-task-implementation\" because user.dir drove both
   paths)."
  (str (test-repo-root/repo-root)))

(defn poll-until
  "Poll `pred-fn` every `interval-ms` milliseconds until it returns truthy or
  `timeout-ms` elapses.  Returns the last value of `pred-fn`."
  ([pred-fn] (poll-until pred-fn 3000 50))
  ([pred-fn timeout-ms interval-ms]
   (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
     (loop []
       (let [v (pred-fn)]
         (if (or v (>= (System/currentTimeMillis) deadline))
           v
           (do (Thread/sleep ^long interval-ms)
               (recur))))))))

(defn create-tui-context+session
  ([mutations]
   (create-tui-context+session mutations {}))
  ([mutations opts]
   (let [ctx (session/create-context (merge {:persist? false
                                             :mutations mutations
                                             :ui-type :tui
                                             ;; :cwd (not :worktree-path) is the
                                             ;; create-context* opt that drives the
                                             ;; session's worktree path (resolved-cwd
                                             ;; → session-defaults :worktree-path);
                                             ;; a top-level :worktree-path opt is
                                             ;; ignored by create-context*.
                                             :cwd workflow-extensions-cwd}
                                            opts))
         sd  (session/new-session-in! ctx nil {})]
     [ctx (:session-id sd)])))

(defn init-built-in-workflow!
  [ctx session-id]
  (workflow-bootstrap/init-built-in! ctx session-id))

(defn load-all-workflow-definitions!
  [ctx]
  (let [result (workflow-file-loader/load-workflow-definitions workflow-extensions-cwd)
        definitions (vals (:definitions result))]
    (doseq [d definitions]
      (swap! (:state* ctx)
             (fn [state]
               (first (workflow-registry/register-definition state d)))))
    definitions))

(defn built-in-workflow-state
  []
  @@#'psi.agent-session.workflow.core/state)
