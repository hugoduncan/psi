(ns psi.agent-session.project-nrepl-resolvers-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.core :as session]
   [psi.project-nrepl.runtime :as project-nrepl-runtime]
   [psi.agent-session.test-support :as test-support]))

(defn- create-session-context
  ([] (create-session-context {}))
  ([opts]
   (let [ctx (session/create-context (test-support/safe-context-opts opts))
         sd  (session/new-session-in! ctx nil {})]
     [ctx (:session-id sd)])))

(deftest project-nrepl-registry-resolver-test
  (testing "registry attrs are queryable from session root"
    (let [[ctx session-id] (create-session-context {:persist? false})
          worktree         (System/getProperty "user.dir")]
      (project-nrepl-runtime/ensure-instance-in!
       ctx
       {:worktree-path worktree
        :acquisition-mode :attached
        :endpoint {:host "127.0.0.1" :port 7888 :port-source :explicit}})
      (project-nrepl-runtime/update-instance-in!
       ctx worktree
       #(assoc %
               :lifecycle-state :ready
               :readiness true
               :active-session-id "nrepl-session-1"
               :can-eval? true
               :can-interrupt? true
               :last-eval {:status :success :value "3"}
               :last-interrupt {:status :success :interrupted-op-id "op-1"}))
      (let [result (session/query-in ctx session-id
                                     [:psi.project-nrepl/count
                                      :psi.project-nrepl/worktree-paths
                                      {:psi.project-nrepl/instances
                                       [:psi.project-nrepl/worktree-path
                                        :psi.project-nrepl/acquisition-mode
                                        :psi.project-nrepl/lifecycle-state
                                        :psi.project-nrepl/readiness
                                        :psi.project-nrepl/active-session-id
                                        :psi.project-nrepl/can-eval?
                                        :psi.project-nrepl/can-interrupt?
                                        :psi.project-nrepl/last-eval
                                        :psi.project-nrepl/last-interrupt]}])]
        (is (= 1 (:psi.project-nrepl/count result)))
        (is (= [worktree] (:psi.project-nrepl/worktree-paths result)))
        (is (= [{:psi.project-nrepl/worktree-path worktree
                 :psi.project-nrepl/acquisition-mode :attached
                 :psi.project-nrepl/lifecycle-state :ready
                 :psi.project-nrepl/readiness true
                 :psi.project-nrepl/active-session-id "nrepl-session-1"
                 :psi.project-nrepl/can-eval? true
                 :psi.project-nrepl/can-interrupt? true
                 :psi.project-nrepl/last-eval {:status :success :value "3"}
                 :psi.project-nrepl/last-interrupt {:status :success :interrupted-op-id "op-1"}}]
               (:psi.project-nrepl/instances result))))))

  (testing "session-scoped project nREPL attr resolves by invoking session worktree"
    (let [[ctx session-id] (create-session-context {:persist? false
                                                    :session-defaults {:worktree-path (System/getProperty "user.dir")}})
          worktree         (System/getProperty "user.dir")]
      (project-nrepl-runtime/ensure-instance-in!
       ctx
       {:worktree-path worktree
        :acquisition-mode :started
        :command-vector ["bb" "nrepl-server"]})
      (project-nrepl-runtime/update-instance-in!
       ctx worktree
       #(assoc %
               :lifecycle-state :ready
               :readiness true
               :active-session-id "nrepl-session-1"
               :can-eval? true
               :can-interrupt? true))
      (let [result (session/query-in ctx session-id
                                     [{:psi.agent-session/project-nrepl
                                       [:psi.project-nrepl/worktree-path
                                        :psi.project-nrepl/acquisition-mode
                                        :psi.project-nrepl/lifecycle-state
                                        :psi.project-nrepl/active-session-id]}])]
        (is (= {:psi.project-nrepl/worktree-path worktree
                :psi.project-nrepl/acquisition-mode :started
                :psi.project-nrepl/lifecycle-state :ready
                :psi.project-nrepl/active-session-id "nrepl-session-1"}
               (:psi.agent-session/project-nrepl result)))))))
