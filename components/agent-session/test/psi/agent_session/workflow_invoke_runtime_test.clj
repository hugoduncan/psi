(ns psi.agent-session.workflow-invoke-runtime-test
  (:require
   [clojure.test :refer [deftest is]]
   [psi.agent-session.core :as session]
   [psi.agent-session.deterministic-operation-registry :as op-reg]
   [psi.agent-session.test-support :as test-support]
   [psi.agent-session.workflow-execution :as workflow-execution]
   [psi.agent-session.workflow-runtime :as workflow-runtime]))

(defn- create-session-context
  []
  (let [ctx (session/create-context (test-support/safe-context-opts {:persist? false}))
        sd  (session/new-session-in! ctx nil {})]
    [ctx (:session-id sd)]))

(def invoke-definition
  {:definition-id "invoke-proof"
   :name "invoke-proof"
   :steps [{:name "discover"
            :type :invoke
            :operation "github/search-issues-by-label"
            :args {:repo {:from :workflow-input :path [:repo]}
                   :labels {:from :workflow-input :path [:labels]}}
            :outputs {:data {:source :invoke/data}
                      :summary {:source :invoke/summary}
                      :result {:source :invoke/result}}
            :yields {:type :data :data :data}}]})

(deftest invoke-step-executes-through-deterministic-operation-registry-test
  (let [[ctx session-id] (create-session-context)
        calls* (atom [])
        _ (op-reg/register-operation-in!
           (:deterministic-operation-registry ctx)
           {:id "github/search-issues-by-label"
            :handler (fn [{:keys [args workflow-run-id step-id]}]
                       (swap! calls* conj {:args args :run-id workflow-run-id :step-id step-id})
                       {:status :ok
                        :data {:issues [{:id 1 :repo (:repo args)}]}
                        :summary "1 issue"})})
        _ (swap! (:state* ctx)
                 (fn [state]
                   (let [[s _ _] (workflow-runtime/create-run state {:definition invoke-definition
                                                                     :run-id "run-invoke"
                                                                     :workflow-input {:repo "psi"
                                                                                      :labels ["bug"]}})]
                     s)))
        result (workflow-execution/execute-run! ctx session-id "run-invoke")
        run (workflow-runtime/workflow-run-in @(:state* ctx) "run-invoke")
        accepted (get-in run [:step-runs "discover" :accepted-result])]
    (is (= :completed (:status result)))
    (is (= [{:args {:repo "psi" :labels ["bug"]}
             :run-id "run-invoke"
             :step-id "discover"}]
           @calls*))
    (is (= {:outcome :ok
            :outputs {:data {:issues [{:id 1 :repo "psi"}]}
                      :summary "1 issue"
                      :result {:status :ok
                               :data {:issues [{:id 1 :repo "psi"}]}
                               :summary "1 issue"}}}
           accepted))))

(deftest invoke-step-operation-error-fails-run-test
  (let [[ctx session-id] (create-session-context)
        _ (op-reg/register-operation-in!
           (:deterministic-operation-registry ctx)
           {:id "github/search-issues-by-label"
            :handler (fn [_]
                       {:status :error
                        :reason :not-found
                        :message "repo missing"})})
        _ (swap! (:state* ctx)
                 (fn [state]
                   (let [[s _ _] (workflow-runtime/create-run state {:definition invoke-definition
                                                                     :run-id "run-invoke-error"
                                                                     :workflow-input {:repo "psi"
                                                                                      :labels ["bug"]}})]
                     s)))
        result (workflow-execution/execute-run! ctx session-id "run-invoke-error")
        run (workflow-runtime/workflow-run-in @(:state* ctx) "run-invoke-error")]
    (is (= :failed (:status result)))
    (is (= :execution-failed (get-in run [:step-runs "discover" :attempts 0 :status])))))
