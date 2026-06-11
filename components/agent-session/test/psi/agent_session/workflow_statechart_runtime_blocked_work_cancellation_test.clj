(ns psi.agent-session.workflow-statechart-runtime-blocked-work-cancellation-test
  (:require
   [clojure.test :refer [deftest is]]
   [psi.agent-session.core :as session]
   [psi.agent-session.test-support :as test-support]
   [psi.deterministic-operation-registry.registry :as operation-registry]
   [psi.deterministic-operation-runtime.core :as operation-runtime]
   [psi.workflow-registry.registry :as workflow-registry]
   [psi.workflow-runtime.core :as workflow-runtime]
   [psi.workflow-runtime.turn-execution-contract]))

(defn- create-session-context
  []
  (let [ctx (session/create-context (test-support/safe-context-opts {:persist? false}))
        sd (session/new-session-in! ctx nil {})]
    [ctx (:session-id sd)]))

(def linear-definition
  {:definition-id "linear"
   :steps [{:name "plan"
            :type :session
            :contributions [{:type :template
                             :text "Plan {{input}}"
                             :vars {"input" {:from :workflow-input :path [:input]}}}]}
           {:name "build"
            :type :session
            :contributions [{:type :template
                             :text "Build {{plan}}"
                             :vars {"plan" {:from {:step "plan" :yield :text}}}}]}]})

(defn- install-run!
  ([ctx definition run-id]
   (install-run! ctx definition run-id {}))
  ([ctx definition run-id run-opts]
   (swap! (:state* ctx)
          (fn [state]
            (let [[s _ _] (workflow-registry/register-definition state definition)
                  [s _ _] (workflow-runtime/create-run s (merge {:definition-id (:definition-id definition)
                                                                 :run-id run-id
                                                                 :workflow-input {:input "ship it"
                                                                                  :original {:ticket 123}}}
                                                                run-opts))]
              s)))))

(deftest actor-turn-cancel-dispatch-does-not-wait-for-blocked-prompt-execution-test
  ;; Regression for task 225 implementation review pass 16: the workflow
  ;; cancellation-entry lock must linearize entry only, not cover the whole actor
  ;; prompt execution. A cancel arriving after ordinary actor work has started
  ;; must still commit D31 promptly and emit cancellation effects.
  (let [[ctx0 _session-id] (create-session-context)
        entered (promise)
        release (promise)
        run-id "run-actor-blocked-cancel"
        ctx (test-support/with-workflow-execution-adapter-overrides
              ctx0
              {:get-session-data (fn [_ctx session-id]
                                   {:session-id session-id
                                    :workflow-owned? true
                                    :workflow-run-id run-id
                                    :workflow-step-id "plan"
                                    :workflow-attempt-id "attempt-plan"})
               :prompt-execution-result! (fn [& _]
                                           (deliver entered :entered)
                                           @release
                                           {:execution-result/assistant-message
                                            {:role "assistant"
                                             :content [{:type :text :text "started before cancel"}]
                                             :stop-reason :stop}})})]
    (install-run! ctx linear-definition run-id)
    (swap! (:state* ctx) assoc-in [:workflows :runs run-id :current-step-id] "plan")
    (swap! (:state* ctx) assoc-in [:workflows :runs run-id :step-runs "plan" :attempts]
           [{:attempt-id "attempt-plan"
             :status :running
             :execution-session-id "plan-child"}])
    (let [turn-future (future
                        (psi.workflow-runtime.turn-execution-contract/execute-actor-turn!
                         ctx "plan-child" "Plan"))]
      (try
        (is (= :entered (deref entered 1000 ::timeout))
            "the actor prompt execution is blocked in ordinary work before cancel")
        (let [cancel-future (future
                              (session/dispatch-in! ctx :psi.workflow/cancel-run
                                                    {:run-id run-id
                                                     :reason "blocked actor cancel"}))
              cancel-result (deref cancel-future 1000 ::timeout)]
          (is (not= ::timeout cancel-result)
              "cancel dispatch must not wait for the blocked actor turn to finish")
          (when (not= ::timeout cancel-result)
            (is (= :cancelled (:psi.workflow/status cancel-result)))
            (is (= :cancelled (get-in @(:state* ctx) [:workflows :runs run-id :status])))))
        (finally
          (deliver release :release)
          (deref turn-future 1000 nil))))))

(deftest invoke-operation-cancel-dispatch-does-not-wait-for-blocked-handler-test
  ;; Regression for task 225 implementation review pass 16: deterministic
  ;; operation handlers are ordinary work that may block; cancellation must not
  ;; hold behind them after the operation has already entered.
  (let [[ctx0 _session-id] (create-session-context)
        entered (promise)
        release (promise)
        run-id "run-invoke-blocked-cancel"
        op-reg (:deterministic-operation-registry ctx0)
        _ (operation-registry/register-operation-in!
           op-reg
           {:id "workflow/blocked-cancel"
            :handler (fn [_]
                       (deliver entered :entered)
                       @release
                       {:status :ok :data {:started-before-cancel? true}})})
        operation (operation-registry/get-operation-in op-reg "workflow/blocked-cancel")]
    (install-run! ctx0 {:definition-id "invoke-blocked-cancel"
                        :steps [{:name "invoke"
                                 :type :invoke
                                 :operation "workflow/blocked-cancel"
                                 :args {}}]}
                  run-id)
    (swap! (:state* ctx0) assoc-in [:workflows :runs run-id :current-step-id] "invoke")
    (swap! (:state* ctx0) assoc-in [:workflows :runs run-id :step-runs "invoke" :attempts]
           [{:attempt-id "attempt-invoke"
             :status :running}])
    (let [operation-future (future
                             (operation-runtime/invoke-operation
                              operation
                              {:ctx ctx0
                               :workflow-run-id run-id
                               :workflow-attempt-id "attempt-invoke"
                               :step-id "invoke"
                               :args {}}))]
      (try
        (is (= :entered (deref entered 1000 ::timeout))
            "the operation handler is blocked in ordinary work before cancel")
        (let [cancel-future (future
                              (session/dispatch-in! ctx0 :psi.workflow/cancel-run
                                                    {:run-id run-id
                                                     :reason "blocked invoke cancel"}))
              cancel-result (deref cancel-future 1000 ::timeout)]
          (is (not= ::timeout cancel-result)
              "cancel dispatch must not wait for the blocked operation handler to finish")
          (when (not= ::timeout cancel-result)
            (is (= :cancelled (:psi.workflow/status cancel-result)))
            (is (= :cancelled (get-in @(:state* ctx0) [:workflows :runs run-id :status])))))
        (finally
          (deliver release :release)
          (deref operation-future 1000 nil))))))
