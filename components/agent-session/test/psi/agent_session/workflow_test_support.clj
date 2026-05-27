(ns psi.agent-session.workflow-test-support
  "Small built-in workflow test helpers.

   Keeps repeated setup explicit while compressing ceremony for higher-core
   workflow tests."
  (:require
   [psi.agent-session.core :as session]
   [psi.agent-session.workflow.bootstrap :as workflow-bootstrap]
   [psi.workflow-loader.core :as workflow-file-loader]
   [psi.workflow-registry.registry :as workflow-registry]))

(def workflow-extensions-cwd
  (-> (System/getProperty "user.dir")
      java.io.File.
      .getCanonicalPath))

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
  [mutations]
  (let [ctx (session/create-context {:persist? false
                                     :mutations mutations
                                     :ui-type :tui
                                     :worktree-path workflow-extensions-cwd})
        sd  (session/new-session-in! ctx nil {})]
    [ctx (:session-id sd)]))

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
