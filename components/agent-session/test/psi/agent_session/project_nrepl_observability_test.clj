(ns psi.agent-session.project-nrepl-observability-test
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

(deftest project-nrepl-observability-proof-test
  (testing "project nREPL projection exposes diagnostic summaries without colliding with psi runtime nREPL attrs"
    (let [[ctx session-id] (create-session-context {:persist? false
                                                    :session-defaults {:worktree-path (System/getProperty "user.dir")}})
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
               :last-error {:message "attach timeout" :phase :attach}
               :last-eval {:op-id "eval-1"
                           :status :success
                           :value "3"
                           :out "stdout"
                           :err nil}
               :last-interrupt {:status :success
                                :interrupted-op-id "eval-1"}))
      (let [result (session/query-in ctx session-id
                                     [:psi.runtime/nrepl-host
                                      :psi.runtime/nrepl-port
                                      :psi.runtime/nrepl-endpoint
                                      {:psi.agent-session/project-nrepl
                                       [:psi.project-nrepl/worktree-path
                                        :psi.project-nrepl/transport-kind
                                        :psi.project-nrepl/lifecycle-state
                                        :psi.project-nrepl/readiness
                                        :psi.project-nrepl/endpoint
                                        :psi.project-nrepl/can-eval?
                                        :psi.project-nrepl/can-interrupt?
                                        :psi.project-nrepl/last-error
                                        :psi.project-nrepl/last-eval
                                        :psi.project-nrepl/last-interrupt]}])
            project-nrepl (:psi.agent-session/project-nrepl result)]
        (is (contains? result :psi.runtime/nrepl-host))
        (is (contains? result :psi.runtime/nrepl-port))
        (is (contains? result :psi.runtime/nrepl-endpoint))
        (is (= worktree (:psi.project-nrepl/worktree-path project-nrepl)))
        (is (= :nrepl (:psi.project-nrepl/transport-kind project-nrepl)))
        (is (= :ready (:psi.project-nrepl/lifecycle-state project-nrepl)))
        (is (= true (:psi.project-nrepl/readiness project-nrepl)))
        (is (= {:host "127.0.0.1" :port 7888 :port-source :explicit}
               (:psi.project-nrepl/endpoint project-nrepl)))
        (is (= true (:psi.project-nrepl/can-eval? project-nrepl)))
        (is (= true (:psi.project-nrepl/can-interrupt? project-nrepl)))
        (is (= {:message "attach timeout" :phase :attach}
               (:psi.project-nrepl/last-error project-nrepl)))
        (is (= {:op-id "eval-1" :status :success :value "3" :out "stdout" :err nil}
               (:psi.project-nrepl/last-eval project-nrepl)))
        (is (= {:status :success :interrupted-op-id "eval-1"}
               (:psi.project-nrepl/last-interrupt project-nrepl))))))

  (testing "query graph exposes project nREPL attrs distinctly from runtime nREPL attrs"
    (let [[ctx session-id] (create-session-context {:persist? false})
          result (session/query-in ctx session-id [:psi.graph/root-queryable-attrs :psi.graph/edges])
          root-attrs (set (:psi.graph/root-queryable-attrs result))
          edge-attrs (set (keep :attribute (:psi.graph/edges result)))]
      (is (contains? root-attrs :psi.runtime/nrepl-host))
      (is (contains? edge-attrs :psi.runtime/nrepl-host))
      (is (contains? root-attrs :psi.project-nrepl/count))
      (is (contains? root-attrs :psi.project-nrepl/worktree-paths))
      (is (contains? edge-attrs {:psi.project-nrepl/instances
                                 [:psi.project-nrepl/id
                                  :psi.project-nrepl/worktree-path
                                  :psi.project-nrepl/acquisition-mode
                                  :psi.project-nrepl/transport-kind
                                  :psi.project-nrepl/lifecycle-state
                                  :psi.project-nrepl/readiness
                                  :psi.project-nrepl/endpoint
                                  :psi.project-nrepl/command-vector
                                  :psi.project-nrepl/session-mode
                                  :psi.project-nrepl/active-session-id
                                  :psi.project-nrepl/can-eval?
                                  :psi.project-nrepl/can-interrupt?
                                  :psi.project-nrepl/last-error
                                  :psi.project-nrepl/last-eval
                                  :psi.project-nrepl/last-interrupt
                                  :psi.project-nrepl/started-at
                                  :psi.project-nrepl/updated-at]}))
      (is (contains? edge-attrs {:psi.agent-session/project-nrepl
                                 [:psi.project-nrepl/id
                                  :psi.project-nrepl/worktree-path
                                  :psi.project-nrepl/acquisition-mode
                                  :psi.project-nrepl/transport-kind
                                  :psi.project-nrepl/lifecycle-state
                                  :psi.project-nrepl/readiness
                                  :psi.project-nrepl/endpoint
                                  :psi.project-nrepl/command-vector
                                  :psi.project-nrepl/session-mode
                                  :psi.project-nrepl/active-session-id
                                  :psi.project-nrepl/can-eval?
                                  :psi.project-nrepl/can-interrupt?
                                  :psi.project-nrepl/last-error
                                  :psi.project-nrepl/last-eval
                                  :psi.project-nrepl/last-interrupt
                                  :psi.project-nrepl/started-at
                                  :psi.project-nrepl/updated-at]})))))
