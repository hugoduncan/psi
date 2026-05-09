(ns psi.agent-session.workflow-test-support
  "Small built-in workflow test helpers.

   Keeps repeated setup explicit while compressing ceremony for higher-core
   workflow tests."
  (:require
   [psi.agent-session.core :as session]
   [psi.agent-session.workflow.bootstrap :as workflow-bootstrap]
   [psi.workflow-loader.compiler :as workflow-file-compiler]
   [psi.workflow-loader.core :as workflow-file-loader]))

(def workflow-extensions-cwd
  "/Users/duncan/projects/hugoduncan/psi/workflow-extensions")

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
  (let [parsed (workflow-file-loader/scan-directory (str workflow-extensions-cwd "/.psi/workflows"))
        {:keys [definitions errors]} (workflow-file-compiler/compile-workflow-files parsed)]
    (when (seq errors)
      (throw (ex-info "compile errors" {:errors errors})))
    (doseq [d definitions]
      (swap! (:state* ctx) assoc-in [:workflows :definitions (:definition-id d)] d))
    definitions))
