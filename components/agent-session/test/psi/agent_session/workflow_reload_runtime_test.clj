(ns ^:integration psi.agent-session.workflow-reload-runtime-test
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.workflow.bootstrap :as workflow-bootstrap]
   [psi.agent-session.workflow.core :as wl]
   [psi.agent-session.core :as session]
   [psi.agent-session.extensions :as ext]
   [psi.command-registry.registry :as command-registry]
   [psi.tool-registry.registry :as tool-registry]
   [psi.agent-session.extensions.runtime-fns :as runtime-fns]
   [psi.agent-session.context :as context]
   [psi.agent-session.mutations :as mutations]
   [psi.agent-session.test-support :as test-support]
   [psi.agent-session.tools :as tools]
   [psi.query.core :as query]))

(defn- create-session-context
  ([]
   (create-session-context {}))
  ([opts]
   (test-support/create-test-session
    (merge {:persist? false
            :session-defaults {:worktree-path (System/getProperty "user.dir")}
            :mutations mutations/all-mutations}
           opts))))

(defn- load-extension-path!
  [ctx session-id rel-path]
  (let [runtime-fns* (runtime-fns/make-extension-runtime-fns ctx session-id nil)
        path         (.getAbsolutePath (io/file (System/getProperty "user.dir") rel-path))
        result       (ext/load-extension-in! (:extension-registry ctx) path runtime-fns*)]
    (when-let [error (:error result)]
      (throw (ex-info error {:path path})))
    result))

(defn- init-built-in-workflow! [ctx session-id]
  (workflow-bootstrap/init-built-in! ctx session-id))

(defn- workflow-state []
  @@#'wl/state)

(defn- first-step-tools
  [definition]
  (let [steps (:steps definition)
        first-step (cond
                     (map? steps) (or (some->> (:step-order definition) first (get steps))
                                      (some-> steps vals first))
                     (sequential? steps) (first steps)
                     :else nil)]
    (or (get-in definition [:steps "step-1" :capability-policy :tools])
        (get-in first-step [:capability-policy :tools])
        (some-> first-step :tools set))))

(deftest ^:integration reload-code-preserves-built-in-workflow-command-and-tool-surfaces-test
  (testing "namespace reload preserves built-in workflow command and tool surfaces so delegate-reload remains usable"
    (let [[ctx session-id] (create-session-context)
          qctx            (query/create-query-context)
          q               (fn [query-v]
                            (query/query-in qctx
                                            {:psi/agent-session-ctx ctx
                                             :psi.agent-session/session-id session-id}
                                            query-v))
          tool            (tools/make-psi-tool q {:ctx ctx :session-id session-id})
          reg             (:extension-registry ctx)
          _               (session/register-resolvers-in! qctx false)
          _               (session/register-mutations-in! qctx mutations/all-mutations true)
          _               (load-extension-path! ctx session-id "extensions/work-on/src/extensions/work_on.clj")
          _               (init-built-in-workflow! ctx session-id)
          before-cmds     (command-registry/command-names-in reg)
          before-tools    (tool-registry/tool-names-in reg)
          before-defs     (keys (:loaded-definitions (workflow-state)))
          result          ((:execute tool) {"action" "reload-code"
                                            "namespaces" ["psi.agent-session.workflow.text"
                                                          "psi.agent-session.workflow.delivery"
                                                          "psi.agent-session.workflow.orchestration"
                                                          "psi.agent-session.workflow.core"
                                                          "extensions.work-on"]})
          parsed          (read-string (:content result))
          after-cmds      (command-registry/command-names-in reg)
          after-tools     (tool-registry/tool-names-in reg)]
      (is (false? (:is-error result)))
      (is (= :ok (:psi-tool/overall-status parsed)))
      (is (some #{"delegate-reload"} before-cmds))
      (is (some #{"delegate-reload"} after-cmds))
      (is (some #{"work-on"} before-tools))
      (is (some #{"work-on"} after-tools))
      (is (some #{"complexity-reduction-pr"} before-defs)))))

(deftest ^:integration reload-code-preserves-built-in-workflow-loaded-definition-state-test
  (testing "namespace reload preserves built-in workflow loaded definition state"
    (let [[ctx session-id] (create-session-context)
          qctx            (query/create-query-context)
          q               (fn [query-v]
                            (query/query-in qctx
                                            {:psi/agent-session-ctx ctx
                                             :psi.agent-session/session-id session-id}
                                            query-v))
          tool            (tools/make-psi-tool q {:ctx ctx :session-id session-id})
          _               (session/register-resolvers-in! qctx false)
          _               (session/register-mutations-in! qctx mutations/all-mutations true)
          _               (load-extension-path! ctx session-id "extensions/work-on/src/extensions/work_on.clj")
          _               (init-built-in-workflow! ctx session-id)
          before-defs     (keys (:loaded-definitions (workflow-state)))
          result          ((:execute tool) {"action" "reload-code"
                                            "namespaces" ["psi.agent-session.workflow.text"
                                                          "psi.agent-session.workflow.delivery"
                                                          "psi.agent-session.workflow.orchestration"
                                                          "psi.agent-session.workflow.core"
                                                          "extensions.work-on"]})
          parsed          (read-string (:content result))
          after-defs      (:loaded-definitions (workflow-state))
          complexity-def  (get after-defs "complexity-reduction-pr")]
      (is (false? (:is-error result)))
      (is (= :ok (:psi-tool/overall-status parsed)))
      (is (some #{"complexity-reduction-pr"} before-defs))
      (is (some? complexity-def))
      (is (= #{"bash" "read" "edit" "write" "work-on"}
             (first-step-tools complexity-def))))))

(deftest ^:integration reload-code-propagates-mutations-to-all-mutations-atom-test
  (testing "namespace reload updates :all-mutations-atom so new mutations are visible via context/all-mutations-in"
    (let [[ctx session-id] (create-session-context)
          qctx            (query/create-query-context)
          q               (fn [query-v]
                            (query/query-in qctx
                                            {:psi/agent-session-ctx ctx
                                             :psi.agent-session/session-id session-id}
                                            query-v))
          tool            (tools/make-psi-tool q {:ctx ctx :session-id session-id})
          _               (session/register-resolvers-in! qctx false)
          _               (session/register-mutations-in! qctx mutations/all-mutations true)
          before-count    (count (context/all-mutations-in ctx))
          result          ((:execute tool) {"action" "reload-code"
                                            "namespaces" ["psi.agent-session.mutations.session"
                                                          "psi.agent-session.mutations.prompts"
                                                          "psi.agent-session.mutations.tools"
                                                          "psi.agent-session.mutations.extensions"
                                                          "psi.agent-session.mutations.services"
                                                          "psi.agent-session.mutations.ui"
                                                          "psi.agent-session.mutations.canonical-workflows"
                                                          "psi.agent-session.extension-workflow-mutations"
                                                          "psi.agent-session.mutations"]})
          parsed          (read-string (:content result))
          after-count     (count (context/all-mutations-in ctx))
          refresh-steps   (get-in parsed [:psi-tool/graph-refresh :steps])
          mutation-step   (some #(when (= :mutation-registration-refresh (:step %)) %) refresh-steps)]
      (is (false? (:is-error result)))
      (is (= :ok (:psi-tool/overall-status parsed)))
      ;; mutation-registration-refresh step must be real (not a no-op placeholder)
      (is (some? mutation-step))
      (is (= :ok (:status mutation-step)))
      (is (string? (:summary mutation-step)))
      (is (not= "mutation registrations refreshed with query runtime" (:summary mutation-step))
          "step summary must not be the old no-op placeholder text")
      ;; all-mutations-atom must have been refreshed
      (is (pos? after-count))
      (is (>= after-count before-count)
          "reload must not reduce the visible mutation count"))))
