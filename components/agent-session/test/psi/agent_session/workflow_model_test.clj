(ns psi.agent-session.workflow-model-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [malli.core :as m]
   [psi.agent-session.workflow-model :as workflow-model]))

(def valid-definition
  {:definition-id "plan-build-review"
   :name "Plan Build Review"
   :step-order ["plan" "build" "review"]
   :steps {"plan" {:label "Plan"
                   :executor {:type :agent :profile "planner" :mode :sync}
                   :prompt-template "$INPUT"
                   :input-bindings {:task {:source :workflow-input :path [:task]}}
                   :result-schema [:map [:outcome [:= :ok]] [:outputs :map]]
                   :retry-policy {:max-attempts 2 :retry-on #{:execution-failed :validation-failed}}
                   :capability-policy {:tools #{"read" "bash"}}}
           "build" {:label "Build"
                    :executor {:type :agent :profile "builder" :mode :async}
                    :prompt-template "Execute this plan: $INPUT"
                    :input-bindings {:plan {:source :step-output :path ["plan" :outputs :plan]}}
                    :result-schema [:map [:outcome [:= :ok]] [:outputs :map]]
                    :retry-policy {:max-attempts 2 :retry-on #{:execution-failed}}
                    :capability-policy {:tools #{"read" "edit" "write" "bash"}}}
           "review" {:label "Review"
                     :executor {:type :agent :profile "reviewer" :mode :sync}
                     :prompt-template "Review this implementation: $INPUT"
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
    (is (workflow-model/valid-workflow-definition? valid-definition))
    (is (= "$INPUT" (get-in valid-definition [:steps "plan" :prompt-template]))))

  (testing "workflow run schema accepts canonical run nesting"
    (is (workflow-model/valid-workflow-run? valid-run))))

;;; Projection schema

(deftest projection-schema-test
  (testing "keyword projections"
    (is (m/validate workflow-model/projection-schema :none))
    (is (m/validate workflow-model/projection-schema :full)))

  (testing "tail projection with turns"
    (is (m/validate workflow-model/projection-schema {:type :tail :turns 3})))

  (testing "tail projection with tool-output control"
    (is (m/validate workflow-model/projection-schema {:type :tail :turns 2 :tool-output false}))
    (is (m/validate workflow-model/projection-schema {:type :tail :turns 1 :tool-output true})))

  (testing "invalid projections"
    (is (not (m/validate workflow-model/projection-schema :other)))
    (is (not (m/validate workflow-model/projection-schema {:type :tail})))
    (is (not (m/validate workflow-model/projection-schema {:type :tail :turns 0})))
    (is (not (m/validate workflow-model/projection-schema {:type :tail :turns -1})))))

;;; Judge schema

(deftest judge-schema-test
  (testing "minimal judge — prompt only"
    (is (m/validate workflow-model/judge-schema
                    {:prompt "Respond exactly: APPROVED or REVISE"})))

  (testing "judge with system-prompt and projection"
    (is (m/validate workflow-model/judge-schema
                    {:prompt "APPROVED or REVISE?"
                     :system-prompt "You are a routing judge."
                     :projection {:type :tail :turns 1}})))

  (testing "judge with keyword projection"
    (is (m/validate workflow-model/judge-schema
                    {:prompt "APPROVED or REVISE?"
                     :projection :full})))

  (testing "invalid judge — missing prompt"
    (is (not (m/validate workflow-model/judge-schema
                         {:system-prompt "judge"})))))

;;; Routing directive schema

(deftest routing-directive-schema-test
  (testing "keyword goto targets"
    (is (m/validate workflow-model/routing-directive-schema {:goto :next}))
    (is (m/validate workflow-model/routing-directive-schema {:goto :previous}))
    (is (m/validate workflow-model/routing-directive-schema {:goto :done})))

  (testing "string goto target (step-id)"
    (is (m/validate workflow-model/routing-directive-schema {:goto "step-2-builder"})))

  (testing "with max-iterations"
    (is (m/validate workflow-model/routing-directive-schema {:goto "step-2-builder" :max-iterations 3})))

  (testing "invalid — missing goto"
    (is (not (m/validate workflow-model/routing-directive-schema {:max-iterations 3}))))

  (testing "invalid — zero max-iterations"
    (is (not (m/validate workflow-model/routing-directive-schema {:goto :next :max-iterations 0})))))

;;; Routing table schema

(deftest routing-table-schema-test
  (testing "valid routing table"
    (is (m/validate workflow-model/routing-table-schema
                    {"APPROVED" {:goto :next}
                     "REVISE"   {:goto "step-2-builder" :max-iterations 3}})))

  (testing "empty table is valid"
    (is (m/validate workflow-model/routing-table-schema {})))

  (testing "invalid — non-string key"
    (is (not (m/validate workflow-model/routing-table-schema
                         {:approved {:goto :next}})))))

;;; Extended step definition schema (with judge and on)

(deftest step-definition-with-judge-test
  (testing "step definition without judge/on is still valid"
    (is (workflow-model/valid-workflow-definition? valid-definition)))

  (testing "step definition with judge and on"
    (let [def-with-judge (assoc-in valid-definition [:steps "review"]
                                   {:label "Review"
                                    :executor {:type :agent :profile "reviewer" :mode :sync}
                                    :prompt-template "Review: $INPUT"
                                    :input-bindings {:build-result {:source :step-output :path ["build" :outputs]}}
                                    :result-schema [:map [:outcome [:= :ok]] [:outputs :map]]
                                    :retry-policy {:max-attempts 1 :retry-on #{:validation-failed}}
                                    :judge {:prompt "APPROVED or REVISE?"
                                            :projection {:type :tail :turns 1}}
                                    :on {"APPROVED" {:goto :next}
                                         "REVISE"   {:goto "build" :max-iterations 3}}})]
      (is (workflow-model/valid-workflow-definition? def-with-judge))))

  (testing "step definition with judge but no on is valid at schema level"
    (let [def-judge-only (assoc-in valid-definition [:steps "review" :judge]
                                   {:prompt "APPROVED or REVISE?"})]
      (is (workflow-model/valid-workflow-definition? def-judge-only)))))

;;; Extended step run schema (with iteration-count)

(deftest step-run-iteration-count-test
  (testing "step run with iteration-count is valid"
    (let [run-with-count (assoc-in valid-run [:step-runs "plan" :iteration-count] 2)]
      (is (workflow-model/valid-workflow-run? run-with-count))))

  (testing "step run without iteration-count is valid (backward compat)"
    (is (workflow-model/valid-workflow-run? valid-run))))

;;; Extended step attempt schema (with judge fields)

(deftest step-attempt-judge-fields-test
  (testing "attempt with judge fields is valid"
    (let [run-with-judge (update-in valid-run [:step-runs "plan" :attempts 0]
                                    assoc
                                    :judge-session-id "judge-1"
                                    :judge-output "APPROVED"
                                    :judge-event "APPROVED")]
      (is (workflow-model/valid-workflow-run? run-with-judge))))

  (testing "attempt without judge fields is valid (backward compat)"
    (is (workflow-model/valid-workflow-run? valid-run))))
