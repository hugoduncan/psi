(ns psi.agent-session.workflow-loader-reload-runtime-test
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [extensions.workflow-loader :as wl]
   [psi.agent-session.core :as session]
   [psi.agent-session.extensions :as ext]
   [psi.agent-session.extensions.runtime-fns :as runtime-fns]
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
            :cwd (System/getProperty "user.dir")
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

(defn- workflow-loader-state []
  @@#'wl/state)

(deftest ^:integration reload-code-preserves-workflow-loader-state-across-namespace-reload
  (testing "namespace reload preserves workflow-loader state so delegate-reload remains usable"
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
          _               (load-extension-path! ctx session-id "extensions/workflow-loader/src/extensions/workflow_loader.clj")
          before-cmds     (ext/command-names-in reg)
          before-tools    (ext/tool-names-in reg)
          before-defs     (keys (:loaded-definitions (workflow-loader-state)))
          result          ((:execute tool) {"action" "reload-code"
                                            "namespaces" ["extensions.workflow-loader.text"
                                                          "extensions.workflow-loader.delivery"
                                                          "extensions.workflow-loader.orchestration"
                                                          "extensions.workflow-loader"
                                                          "extensions.work-on"]})
          parsed          (read-string (:content result))
          after-cmds      (ext/command-names-in reg)
          after-tools     (ext/tool-names-in reg)
          after-defs      (:loaded-definitions (workflow-loader-state))
          complexity-def  (get after-defs "complexity-reduction-pr")]
      (is (false? (:is-error result)))
      (is (= :ok (:psi-tool/overall-status parsed)))
      (is (some #{"delegate-reload"} before-cmds))
      (is (some #{"delegate-reload"} after-cmds))
      (is (some #{"work-on"} before-tools))
      (is (some #{"work-on"} after-tools))
      (is (some #{"complexity-reduction-pr"} before-defs))
      (is (some? complexity-def))
      (is (= #{"bash" "read" "edit" "write" "work-on"}
             (get-in complexity-def [:steps "step-1" :capability-policy :tools]))))))
