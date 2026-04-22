(ns psi.agent-session.workflow-lifecycle-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.core :as session]
   [psi.agent-session.test-support :as test-support]
   [psi.agent-session.workflow-attempts :as workflow-attempts]
   [psi.agent-session.workflow-progression :as workflow-progression]
   [psi.agent-session.workflow-runtime :as workflow-runtime]))

(defn- create-session-context
  ([]
   (create-session-context {}))
  ([opts]
   (let [ctx (session/create-context (test-support/safe-context-opts opts))
         sd  (session/new-session-in! ctx nil {})]
     [ctx (:session-id sd)])))

(def plan-build-review-definition
  {:definition-id "plan-build-review"
   :name "Plan Build Review"
   :summary "Representative chain-like workflow proof"
   :step-order ["plan" "build" "review"]
   :steps {"plan" {:label "Plan"
                   :executor {:type :agent :profile "planner" :mode :sync}
                   :input-bindings {:task {:source :workflow-input :path [:task]}}
                   :result-schema [:map [:outcome [:= :ok]] [:outputs :map]]
                   :retry-policy {:max-attempts 2 :retry-on #{:execution-failed :validation-failed}}
                   :capability-policy {:tools #{"read" "bash"}}}
           "build" {:label "Build"
                    :executor {:type :agent :profile "builder" :mode :async}
                    :input-bindings {:plan {:source :step-output :path ["plan" :outputs :plan]}}
                    :result-schema [:map [:outcome [:= :ok]] [:outputs :map]]
                    :retry-policy {:max-attempts 2 :retry-on #{:execution-failed :validation-failed}}
                    :capability-policy {:tools #{"read" "edit" "write"}}}
           "review" {:label "Review"
                     :executor {:type :agent :profile "reviewer" :mode :sync}
                     :input-bindings {:build {:source :step-output :path ["build" :outputs :build]}}
                     :result-schema [:map [:outcome [:= :ok]] [:outputs :map]]
                     :retry-policy {:max-attempts 1 :retry-on #{:validation-failed}}
                     :capability-policy {:tools #{"read"}}}}})

(defn- install-definition-and-run!
  [ctx]
  (let [[state1 definition-id _]
        (workflow-runtime/register-definition @(:state* ctx) plan-build-review-definition)
        [state2 run-id _]
        (workflow-runtime/create-run state1 {:definition-id definition-id
                                             :run-id "run-1"
                                             :workflow-input {:task "ship it"}})]
    (reset! (:state* ctx) state2)
    run-id))

(defn- create-and-start-attempt!
  [ctx parent-session-id run-id step-id attempt-id]
  (let [{:keys [attempt execution-session]}
        (workflow-attempts/create-step-attempt-session!
         ctx
         parent-session-id
         {:workflow-run-id run-id
          :workflow-step-id step-id
          :attempt-id attempt-id
          :session-name (str step-id " attempt " attempt-id)
          :tool-defs []
          :thinking-level :off})]
    (swap! (:state* ctx)
           (fn [state]
             (-> state
                 (update-in [:workflows :runs run-id]
                            #(workflow-attempts/append-attempt-to-run % step-id attempt))
                 (workflow-progression/start-latest-attempt run-id step-id))))
    {:attempt attempt
     :execution-session execution-session}))

(deftest representative-sequential-workflow-lifecycle-test
  (testing "plan -> build -> review executes deterministically with introspectable session linkage"
    (let [[ctx session-id] (create-session-context {:persist? false})
          run-id           (install-definition-and-run! ctx)
          plan-run         (create-and-start-attempt! ctx session-id run-id "plan" "plan-1")
          _                (swap! (:state* ctx)
                                  workflow-progression/submit-result-envelope
                                  run-id
                                  "plan"
                                  {:outcome :ok
                                   :outputs {:plan {:summary "do it" :files ["src/x.clj"]}}})
          build-run        (create-and-start-attempt! ctx session-id run-id "build" "build-1")
          _                (swap! (:state* ctx)
                                  workflow-progression/submit-result-envelope
                                  run-id
                                  "build"
                                  {:outcome :ok
                                   :outputs {:build {:status :implemented
                                                     :files ["src/x.clj" "test/x_test.clj"]}}})
          review-run       (create-and-start-attempt! ctx session-id run-id "review" "review-1")
          _                (swap! (:state* ctx)
                                  workflow-progression/submit-result-envelope
                                  run-id
                                  "review"
                                  {:outcome :ok
                                   :outputs {:review {:verdict :approved}}})
          run              (workflow-runtime/workflow-run-in @(:state* ctx) run-id)
          detail-result    (session/query-in ctx
                                             [:psi.workflow.run/detail]
                                             {:psi.workflow.run/id run-id})
          run-detail       (:psi.workflow.run/detail detail-result)]
      (is (= :completed (:status run)))
      (is (nil? (:current-step-id run)))
      (is (= {:outcome :ok
              :outputs {:plan {:summary "do it" :files ["src/x.clj"]}}}
             (get-in run [:step-runs "plan" :accepted-result])))
      (is (= {:outcome :ok
              :outputs {:build {:status :implemented
                                :files ["src/x.clj" "test/x_test.clj"]}}}
             (get-in run [:step-runs "build" :accepted-result])))
      (is (= {:outcome :ok
              :outputs {:review {:verdict :approved}}}
             (get-in run [:step-runs "review" :accepted-result])))
      (let [actual-session-ids (mapv #(get-in % [:psi.workflow.attempt/execution-session-id])
                                     (mapcat :psi.workflow.step-run/attempts
                                             (:psi.workflow.run/step-runs run-detail)))]
        (is (= 3 (count actual-session-ids)))
        (is (= #{(get-in plan-run [:execution-session :session-id])
                 (get-in build-run [:execution-session :session-id])
                 (get-in review-run [:execution-session :session-id])}
               (set actual-session-ids))))
      (is (= :completed (:psi.workflow.run/status run-detail)))
      (is (= ["plan" "build" "review"]
             (mapv :psi.workflow.step-run/id
                   (:psi.workflow.run/step-runs run-detail))))
      (is (= [:succeeded :succeeded :succeeded]
             (mapv :psi.workflow.step-run/status
                   (:psi.workflow.run/step-runs run-detail))))
      (is (= [:workflow/run-created
              :workflow/attempt-started
              :workflow/result-received
              :workflow/step-succeeded
              :workflow/attempt-started
              :workflow/result-received
              :workflow/step-succeeded
              :workflow/attempt-started
              :workflow/result-received
              :workflow/complete]
             (mapv :psi.workflow.history/event
                   (:psi.workflow.run/history run-detail))))
      (session/shutdown-context! ctx))))

(deftest blocked-resume-creates-new-attempt-proof-test
  (testing "blocked workflows resume by creating a new attempt while preserving prior blocked attempt history"
    (let [[ctx session-id] (create-session-context {:persist? false})
          run-id           (install-definition-and-run! ctx)
          first-run        (create-and-start-attempt! ctx session-id run-id "plan" "plan-1")
          _                (swap! (:state* ctx)
                                  workflow-progression/submit-result-envelope
                                  run-id
                                  "plan"
                                  {:outcome :blocked
                                   :blocked {:question "ship it?" :choices [:yes :no]}})
          _                (swap! (:state* ctx) workflow-progression/resume-blocked-run run-id)
          second-run       (create-and-start-attempt! ctx session-id run-id "plan" "plan-2")
          _                (swap! (:state* ctx)
                                  workflow-progression/submit-result-envelope
                                  run-id
                                  "plan"
                                  {:outcome :ok
                                   :outputs {:plan {:decision :yes}}})
          run              (workflow-runtime/workflow-run-in @(:state* ctx) run-id)
          plan-attempts    (get-in run [:step-runs "plan" :attempts])
          detail-result    (session/query-in ctx
                                             [:psi.workflow.run/detail]
                                             {:psi.workflow.run/id run-id})
          run-detail       (:psi.workflow.run/detail detail-result)
          eql-attempts     (get-in run-detail [:psi.workflow.run/step-runs 0 :psi.workflow.step-run/attempts])]
      (is (= :running (:status run)))
      (is (= "build" (:current-step-id run)))
      (is (= 2 (count plan-attempts)))
      (is (= :blocked (get-in plan-attempts [0 :status])))
      (is (= {:question "ship it?" :choices [:yes :no]}
             (get-in plan-attempts [0 :blocked])))
      (is (= :succeeded (get-in plan-attempts [1 :status])))
      (is (= [(get-in first-run [:execution-session :session-id])
              (get-in second-run [:execution-session :session-id])]
             (mapv :execution-session-id plan-attempts)))
      (let [actual-session-ids (mapv :psi.workflow.attempt/execution-session-id eql-attempts)]
        (is (= 2 (count actual-session-ids)))
        (is (= #{(get-in first-run [:execution-session :session-id])
                 (get-in second-run [:execution-session :session-id])}
               (set actual-session-ids))))
      (is (= [:blocked :succeeded]
             (mapv :psi.workflow.attempt/status eql-attempts)))
      (is (= [:workflow/run-created
              :workflow/attempt-started
              :workflow/result-received
              :workflow/block
              :workflow/resume
              :workflow/attempt-started
              :workflow/result-received
              :workflow/step-succeeded]
             (mapv :psi.workflow.history/event
                   (:psi.workflow.run/history run-detail))))
      (session/shutdown-context! ctx))))
