(ns psi.agent-session.workflow-model-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.workflow-model :as workflow-model]))

(def valid-definition
  {:definition-id "plan-build-review"
   :name "Plan Build Review"
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
                     :retry-policy {:max-attempts 2 :retry-on #{:execution-failed}}
                     :capability-policy {:tools #{"read" "edit" "write" "bash"}}}
           "review" {:label "Review"
                      :executor {:type :agent :profile "reviewer" :mode :sync}
                      :input-bindings {:build-result {:source :step-output :path ["build" :outputs]}}
                      :result-schema [:map [:outcome [:= :ok]] [:outputs :map]]
                      :retry-policy {:max-attempts 1 :retry-on #{:validation-failed}}}}})

(def valid-run
  {:run-id "run-1"
   :status :pending
   :effective-definition valid-definition
   :source-definition-id "plan-build-review"
   :workflow-input {:task "implement feature"}
   :current-step-id "plan"
   :step-runs {"plan" {:step-id "plan"
                        :attempts [{:attempt-id "plan-a1"
                                    :status :pending
                                    :created-at (java.time.Instant/now)
                                    :updated-at (java.time.Instant/now)}]}}
   :history [{:event :workflow/run-created
              :timestamp (java.time.Instant/now)
              :data {:run-id "run-1"}}]
   :created-at (java.time.Instant/now)
   :updated-at (java.time.Instant/now)})

(deftest workflow-state-shape-test
  (testing "initial workflow state matches schema"
    (let [state (workflow-model/initial-workflow-state)]
      (is (= {:definitions {} :runs {} :run-order []} state))
      (is (workflow-model/valid-workflow-state? state))))

  (testing "workflow definition schema accepts sequential agent-backed slice-one shape"
    (is (workflow-model/valid-workflow-definition? valid-definition)))

  (testing "workflow run schema accepts canonical run nesting"
    (is (workflow-model/valid-workflow-run? valid-run))))
