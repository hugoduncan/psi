(ns psi.agent-session.workflow-delegate-failure-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.workflow-execution :as workflow-execution]
   [psi.agent-session.workflow-execution-test-support :as support]
   [psi.workflow-runtime.core :as workflow-runtime]
   [psi.workflow-registry.registry :as workflow-registry]))

(def child-definition
  {:definition-id "child"
   :name "child"
   :steps [{:name "child-step"
            :type :session
            :contributions [{:type :template
                             :text "Do {{input}}"
                             :vars {"input" {:from :workflow-input}}}]}]})

(def parent-definition
  {:definition-id "parent"
   :name "parent"
   :steps [{:name "delegate-child"
            :type :delegate
            :target "child"
            :prompt-string "Carry out the child workflow."
            :context []}]})

(defn- install-parent-run!
  [ctx]
  (swap! (:state* ctx)
         (fn [state]
           (let [[state _ _] (workflow-registry/register-definition state child-definition)
                 [state _ _] (workflow-runtime/create-run
                              state
                              {:definition parent-definition
                               :run-id "parent-run"
                               :workflow-input "parent input"})]
             state))))

(defn- failed-turn
  [session-id]
  {:status :error
   :session-id session-id
   :assistant-message {:role "assistant"
                       :error-message "upstream request rejected"
                       :content [{:type :error
                                  :text "upstream request rejected"}]}
   :assistant-text ""
   :execution-result {}
   :failure {:reason :provider-unavailable
             :message "upstream request rejected"}})

(deftest failed-child-delegation-persists-canonical-envelope-test
  ;; A real delegated statechart execution records the lower-runtime envelope
  ;; verbatim on the parent attempt rather than a generic payload or child data.
  (testing "actionable child execution failures become parent attempt envelopes"
    (let [[base-ctx session-id] (support/create-session-context {:persist? false})
          ctx (assoc base-ctx
                     :workflow-execute-actor-turn-fn
                     (fn [_ctx child-session-id _prompt]
                       (failed-turn child-session-id)))
          _ (install-parent-run! ctx)
          result (workflow-execution/execute-run! ctx session-id "parent-run")
          parent-run (workflow-runtime/workflow-run-in @(:state* ctx) "parent-run")
          child-run-id (get-in parent-run [:step-runs "delegate-child" :attempts 0 :execution-error :delegate-failure :run-id])
          child-run (workflow-runtime/workflow-run-in @(:state* ctx) child-run-id)
          parent-error (get-in parent-run [:step-runs "delegate-child" :attempts 0 :execution-error])]
      (is (= :failed (:status result)))
      (is (= :failed (:status parent-run)))
      (is (= :failed (:status child-run)))
      (is (= {:reason :delegated-workflow-failed
              :message "Delegated workflow 'child' failed at step 'child-step': upstream request rejected"
              :delegate-failure {:source :execution-error
                                 :run-id child-run-id
                                 :target "child"
                                 :reason :provider-unavailable
                                 :step-id "child-step"
                                 :attempt-id (get-in child-run [:step-runs "child-step" :attempts 0 :attempt-id])}}
             parent-error))
      (is (not (contains? parent-error :details)))
      (is (nil? (get-in parent-run [:step-runs "delegate-child" :accepted-result]))))))
