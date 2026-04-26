(ns psi.agent-session.workflow-resolvers-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.core :as session]
   [psi.agent-session.test-support :as test-support]
   [psi.agent-session.workflow-attempts :as workflow-attempts]
   [psi.agent-session.workflow-progression-recording :as workflow-recording]
   [psi.agent-session.workflow-runtime :as workflow-runtime]))

(defn- create-session-context
  ([]
   (create-session-context {}))
  ([opts]
   (let [ctx (session/create-context (test-support/safe-context-opts opts))
         sd  (session/new-session-in! ctx nil {})]
     [ctx (:session-id sd)])))

(def registered-definition
  {:definition-id "plan-build-review"
   :name "Plan Build Review"
   :summary "Plan, then build, then review"
   :step-order ["plan" "build"]
   :steps {"plan" {:label "Plan"
                   :description "Create a plan"
                   :executor {:type :agent :profile "planner" :mode :sync}
                   :prompt-template "$INPUT"
                   :input-bindings {:task {:source :workflow-input :path [:task]}}
                   :result-schema [:map [:outcome [:= :ok]] [:outputs :map]]
                   :retry-policy {:max-attempts 2 :retry-on #{:execution-failed :validation-failed}}
                   :capability-policy {:tools #{"read" "bash"}}}
           "build" {:label "Build"
                    :description "Implement the plan"
                    :executor {:type :agent :profile "builder" :mode :async}
                    :prompt-template "Execute this plan:\n\n$INPUT\n\nOriginal request: $ORIGINAL"
                    :input-bindings {:plan {:source :step-output :path ["plan" :outputs :plan]}}
                    :result-schema [:map [:outcome [:= :ok]] [:outputs :map]]
                    :retry-policy {:max-attempts 1 :retry-on #{:execution-failed}}
                    :capability-policy {:tools #{"read" "edit" "write"}}}}})

(defn- install-run-with-attempt!
  [ctx parent-session-id]
  (let [[state1 definition-id _]
        (workflow-runtime/register-definition @(:state* ctx) registered-definition)
        [state2 run-id _]
        (workflow-runtime/create-run state1 {:definition-id definition-id
                                             :run-id "run-1"
                                             :workflow-input {:task "ship it"}})
        _ (reset! (:state* ctx) state2)
        {:keys [attempt execution-session]}
        (workflow-attempts/create-step-attempt-session!
         ctx
         parent-session-id
         {:workflow-run-id run-id
          :workflow-step-id "plan"
          :attempt-id "attempt-1"
          :session-name "workflow plan attempt"
          :tool-defs []
          :thinking-level :off})]
    (swap! (:state* ctx)
           (fn [state]
             (-> state
                 (update-in [:workflows :runs run-id]
                            #(workflow-attempts/append-attempt-to-run % "plan" attempt))
                 (workflow-recording/start-latest-attempt run-id "plan"))))
    {:run-id run-id
     :attempt attempt
     :execution-session execution-session}))

(deftest workflow-root-resolvers-test
  (testing "workflow definitions and runs are queryable from session root"
    (let [[ctx session-id] (create-session-context {:persist? false})
          {:keys [run-id execution-session]} (install-run-with-attempt! ctx session-id)
          result (session/query-in ctx session-id
                                   [:psi.workflow/definition-count
                                    :psi.workflow/definition-ids
                                    :psi.workflow/run-count
                                    :psi.workflow/run-ids
                                    :psi.workflow/run-statuses
                                    {:psi.workflow/definitions
                                     [:psi.workflow.definition/id
                                      :psi.workflow.definition/name
                                      :psi.workflow.definition/summary
                                      :psi.workflow.definition/step-order
                                      :psi.workflow.definition/step-count
                                      {:psi.workflow.definition/steps
                                       [:psi.workflow.step/id
                                        :psi.workflow.step/label
                                        :psi.workflow.step/executor
                                        :psi.workflow.step/prompt-template
                                        :psi.workflow.step/input-bindings
                                        :psi.workflow.step/retry-policy
                                        :psi.workflow.step/capability-policy]}]}
                                    {:psi.workflow/runs
                                     [:psi.workflow.run/id
                                      :psi.workflow.run/status
                                      :psi.workflow.run/source-definition-id
                                      :psi.workflow.run/workflow-input
                                      :psi.workflow.run/current-step-id
                                      :psi.workflow.run/execution-session-ids
                                      {:psi.workflow.run/step-runs
                                       [:psi.workflow.step-run/id
                                        :psi.workflow.step-run/status
                                        :psi.workflow.step-run/attempt-count
                                        {:psi.workflow.step-run/attempts
                                         [:psi.workflow.attempt/id
                                          :psi.workflow.attempt/status
                                          :psi.workflow.attempt/execution-session-id]}]}]}])
          definitions (:psi.workflow/definitions result)
          runs (:psi.workflow/runs result)
          workflow-run (first runs)
          plan-step (first (get-in definitions [0 :psi.workflow.definition/steps]))
          plan-step-run (first (:psi.workflow.run/step-runs workflow-run))]
      (is (= 1 (:psi.workflow/definition-count result)))
      (is (= ["plan-build-review"] (:psi.workflow/definition-ids result)))
      (is (= 1 (:psi.workflow/run-count result)))
      (is (= [run-id] (:psi.workflow/run-ids result)))
      (is (= [:running] (:psi.workflow/run-statuses result)))
      (is (= "plan-build-review" (:psi.workflow.definition/id (first definitions))))
      (is (= "Plan" (:psi.workflow.step/label plan-step)))
      (is (= {:type :agent :profile "planner" :mode :sync}
             (:psi.workflow.step/executor plan-step)))
      (is (= "$INPUT"
             (:psi.workflow.step/prompt-template plan-step)))
      (is (= #{"read" "bash"}
             (get-in plan-step [:psi.workflow.step/capability-policy :tools])))
      (is (= run-id (:psi.workflow.run/id workflow-run)))
      (is (= :running (:psi.workflow.run/status workflow-run)))
      (is (= "plan" (:psi.workflow.run/current-step-id workflow-run)))
      (is (= [(:session-id execution-session)]
             (:psi.workflow.run/execution-session-ids workflow-run)))
      (is (= "plan" (:psi.workflow.step-run/id plan-step-run)))
      (is (= :running (:psi.workflow.step-run/status plan-step-run)))
      (is (= 1 (:psi.workflow.step-run/attempt-count plan-step-run)))
      (is (= "attempt-1"
             (get-in plan-step-run [:psi.workflow.step-run/attempts 0 :psi.workflow.attempt/id]))))))

(deftest workflow-detail-resolver-test
  (testing "workflow detail is queryable explicitly by run id"
    (let [[ctx session-id] (create-session-context {:persist? false})
          {:keys [run-id execution-session]} (install-run-with-attempt! ctx session-id)
          run-result (session/query-in ctx
                                       [:psi.workflow.run/detail]
                                       {:psi.workflow.run/id run-id})
          run-detail (:psi.workflow.run/detail run-result)]
      (is (= run-id (:psi.workflow.run/id run-detail)))
      (is (= :running (:psi.workflow.run/status run-detail)))
      (is (= "plan-build-review"
             (get-in run-detail [:psi.workflow.run/effective-definition :psi.workflow.definition/id])))
      (is (= "$INPUT"
             (get-in run-detail [:psi.workflow.run/effective-definition
                                 :psi.workflow.definition/steps 0
                                 :psi.workflow.step/prompt-template])))
      (is (= 2 (count (:psi.workflow.run/step-runs run-detail))))
      (is (= [(:session-id execution-session)]
             (:psi.workflow.run/execution-session-ids run-detail)))
      (is (= 2 (count (:psi.workflow.run/history run-detail))))
      (is (= :workflow/run-created
             (get-in run-detail [:psi.workflow.run/history 0 :psi.workflow.history/event])))
      (is (= :workflow/attempt-started
             (get-in run-detail [:psi.workflow.run/history 1 :psi.workflow.history/event]))))))
