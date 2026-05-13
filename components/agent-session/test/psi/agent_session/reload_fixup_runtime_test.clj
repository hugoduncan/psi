(ns psi.agent-session.reload-fixup-runtime-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [psi.agent-session.core :as session]
   [psi.agent-session.mutations :as mutations]
   [psi.agent-session.resolvers :as session-resolvers]
   [psi.agent-session.psi-tool :as psi-tool]
   [psi.agent-session.test-support :as test-support]
   [psi.agent-session.tools :as tools]
   [psi.agent-session.workflow.bootstrap :as workflow-bootstrap]
   [psi.agent-session.workflow.runtime-state :as workflow-runtime-state]
   [psi.agent-core.core :as agent]
   [psi.state-kernel.dispatch :as kernel-dispatch]))

(defn- clean-workflow-runtime-state [f]
  (reset! workflow-runtime-state/state nil)
  (reset! workflow-runtime-state/inflight-runs {})
  (try
    (f)
    (finally
      (reset! workflow-runtime-state/state nil)
      (reset! workflow-runtime-state/inflight-runs {}))))

(use-fixtures :each clean-workflow-runtime-state)

(defn- create-session-context
  ([]
   (create-session-context {}))
  ([opts]
   (let [ctx (session/create-context (test-support/safe-context-opts opts))
         sd  (session/new-session-in! ctx nil {})]
     [ctx (:session-id sd)])))

(deftest refresh-query-runtime-invalidates-both-cached-query-envs-test
  (testing "reload fixup invalidates cached agent-session and agent-core query envs"
    (let [session-env-0 (session-resolvers/build-env)
          agent-env-0   (agent/build-env)]
      (reset! @#'psi.agent-session.resolvers/query-env session-env-0)
      (reset! @#'psi.agent-core.core/query-env agent-env-0)
      (#'psi-tool/refresh-query-runtime! {})
      (is (nil? @@#'psi.agent-session.resolvers/query-env))
      (is (nil? @@#'psi.agent-core.core/query-env)))))

(deftest refresh-dispatch-handlers-reregisters-kernel-handler-registry-test
  (testing "reload fixup rebuilds the state-kernel handler registry from current vars"
    (kernel-dispatch/clear-handlers!)
    (is (empty? (kernel-dispatch/registered-event-types)))
    (let [[ctx _] (create-session-context)]
      (kernel-dispatch/clear-handlers!)
      (let [step (#'psi-tool/refresh-dispatch-handlers! ctx)]
        (is (= :ok (:status step)))
        (is (contains? (kernel-dispatch/registered-event-types) :session/register-prompt-template))
        (is (contains? (kernel-dispatch/registered-event-types) :session/refresh-system-prompt))
        (is (pos? (:event-count step)))))))

(deftest reload-code-reinitializes-built-in-workflow-runtime-when-active-test
  (testing "reload fixup reinitializes built-in workflow runtime state when workflow is already active"
    (let [[ctx session-id] (create-session-context {:persist? false
                                                    :mutations mutations/all-mutations})
          tool            (tools/make-psi-tool (fn [_q] {}) {:ctx ctx :session-id session-id})]
      (workflow-bootstrap/init-built-in! ctx session-id)
      (let [before-api (:api @workflow-runtime-state/state)
            result     (with-redefs [psi.agent-session.psi-tool/canonical-source-path-for-ns
                                     (fn [_] (str (System/getProperty "user.dir") "/src/in-worktree.clj"))
                                     psi.agent-session.psi-tool/target-source-path-for-ns
                                     (fn [worktree-path ns-name]
                                       (str worktree-path "/src/" (str/replace ns-name "." "/") ".clj"))
                                     clojure.core/load-file (fn [_] :loaded)]
                         ((:execute tool) {"action" "reload-code"
                                           "namespaces" ["psi.agent-session.workflow.core"]}))
            parsed     (read-string (:content result))
            after-api  (:api @workflow-runtime-state/state)
            wf-step    (get-in parsed [:psi-tool/graph-refresh :steps 4])]
        (is (false? (:is-error result)))
        (is (= :ok (:psi-tool/overall-status parsed)))
        (is (= :built-in-workflow-refresh (:step wf-step)))
        (is (= :ok (:status wf-step)))
        (is (some? before-api))
        (is (some? after-api))
        (is (not (identical? before-api after-api)))))))
