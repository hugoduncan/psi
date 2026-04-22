(ns psi.agent-session.workflow-loader-reload-runtime-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.core :as session]
   [psi.agent-session.extension-runtime :as extension-runtime]
   [psi.agent-session.tools :as tools]
   [psi.query.core :as query]))

(defn- create-session-context
  ([]
   (create-session-context {}))
  ([opts]
   (let [ctx (session/create-context (merge {:cwd (System/getProperty "user.dir")}
                                            opts))
         sd  (session/new-session-in! ctx nil {})]
     [ctx (:session-id sd)])))

(deftest ^:integration reload-code-preserves-workflow-loader-state-across-namespace-reload
  (testing "namespace reload preserves workflow-loader state so delegate-reload remains usable"
    (let [[ctx session-id] (create-session-context {:persist? false})
          qctx            (query/create-query-context)
          q               (fn [query-v]
                            (query/query-in qctx
                                            {:psi/agent-session-ctx ctx
                                             :psi.agent-session/session-id session-id}
                                            query-v))
          tool            (tools/make-psi-tool q {:ctx ctx :session-id session-id})
          _               (session/register-resolvers-in! qctx false)
          _               (extension-runtime/reload-extensions-in! ctx session-id [] (System/getProperty "user.dir"))
          before          (q [:psi.extension/command-names :psi.extension/tool-names :psi.workflow/definitions])
          result          ((:execute tool) {"action" "reload-code"
                                            "worktree-path" (System/getProperty "user.dir")})
          parsed          (read-string (:content result))
          after           (q [:psi.extension/command-names :psi.extension/tool-names :psi.workflow/definitions])
          complexity-def  (some #(when (= "complexity-reduction-pr" (:psi.workflow.definition/id %)) %)
                                (:psi.workflow/definitions after))]
      (is (false? (:is-error result)))
      (is (= :ok (:psi-tool/overall-status parsed)))
      (is (some #{"delegate-reload"} (:psi.extension/command-names before)))
      (is (some #{"delegate-reload"} (:psi.extension/command-names after)))
      (is (some #{"work-on"} (:psi.extension/tool-names after)))
      (is (some? complexity-def))
      (is (= #{"bash" "read" "edit" "write" "work-on"}
             (get-in complexity-def [:psi.workflow.definition/steps 0 :psi.workflow.step/capability-policy :tools]))))))
