(ns psi.workflow-runtime.progression-recording-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.workflow-runtime.attempts :as workflow-attempts]
   [psi.workflow-runtime.model :as workflow-model]
   [psi.workflow-runtime.progression-recording :as workflow-recording]
   [psi.workflow-runtime.core :as workflow-runtime]
   [psi.workflow-registry.registry :as workflow-registry]))

(def definition
  {:definition-id "plan-build-review"
   :name "Plan Build Review"
   :steps [{:name "plan"
            :type :session
            :contributions [{:type :template
                             :text "Plan {{task}}"
                             :vars {"task" {:from :workflow-input :path [:task]}}}]}
           {:name "build"
            :type :session
            :contributions [{:type :template
                             :text "Build {{plan}}"
                             :vars {"plan" {:from {:step "plan" :yield :text}}}}]}]})

(defn- base-state-with-run
  []
  (let [[state1 _ _] (workflow-registry/register-definition {:workflows (workflow-model/initial-workflow-state)}
                                                            definition)
        [state2 run-id _] (workflow-runtime/create-run state1 {:definition-id "plan-build-review"
                                                               :run-id "run-1"
                                                               :workflow-input {:task "ship it"}})
        attempt           (workflow-attempts/new-attempt {:attempt-id "a1"
                                                          :status :pending
                                                          :execution-session-id "child-1"})
        state3            (update-in state2 [:workflows :runs run-id]
                                     #(workflow-attempts/append-attempt-to-run % "plan" attempt))]
    [state3 run-id]))

(deftest start-latest-attempt-test
  (testing "latest attempt can be marked running and run status enters running"
    (let [[state run-id] (base-state-with-run)
          state'         (workflow-recording/start-latest-attempt state run-id "plan")
          run            (get-in state' [:workflows :runs run-id])]
      (is (= :running (:status run)))
      (is (= :running (get-in run [:step-runs "plan" :attempts 0 :status]))))))

(deftest increment-iteration-count-test
  (testing "increments from nil (0) to 1"
    (let [[state run-id] (base-state-with-run)
          state' (workflow-recording/increment-iteration-count state run-id "plan")
          run    (get-in state' [:workflows :runs run-id])]
      (is (= 1 (get-in run [:step-runs "plan" :iteration-count])))))

  (testing "increments from 1 to 2"
    (let [[state run-id] (base-state-with-run)
          state' (-> state
                     (workflow-recording/increment-iteration-count run-id "plan")
                     (workflow-recording/increment-iteration-count run-id "plan"))
          run    (get-in state' [:workflows :runs run-id])]
      (is (= 2 (get-in run [:step-runs "plan" :iteration-count]))))))

(deftest record-step-result-test
  (testing "record-step-result records envelope without advancing or changing run status/current-step-id"
    (let [[state run-id] (base-state-with-run)
          state' (-> state
                     (workflow-recording/start-latest-attempt run-id "plan")
                     (workflow-recording/record-step-result run-id "plan"
                                                            {:outcome :ok :outputs {:text "plan output"}}))
          run    (get-in state' [:workflows :runs run-id])]
      (is (= "plan" (:current-step-id run)))
      (is (= {:outcome :ok :outputs {:text "plan output"}}
             (get-in run [:step-runs "plan" :accepted-result])))
      (is (= :succeeded (get-in run [:step-runs "plan" :attempts 0 :status])))
      (is (= :running (:status run))))))

(deftest record-step-result-preserves-structured-output-metadata-test
  ;; Tests persisted workflow run state stores the workflow structured-output
  ;; envelope exactly, including provider strategy metadata, so replay/source
  ;; resolution can inspect accepted results without re-running model generation.
  (testing "accepted result, latest attempt, and history preserve structured-output metadata"
    (let [[state run-id] (base-state-with-run)
          structured-result {:raw-output nil
                             :structured-output {:mode :structured
                                                 :schema-id :psi.workflow/bug-reproduction-classification
                                                 :schema-version 1
                                                 :strategy :provider-native
                                                 :native-mechanism :openai/chat-completions-json-schema-response-format
                                                 :source :openai/message-content
                                                 :fallback-used? false
                                                 :payload {"next-action" "handoff-to-fix"}
                                                 :raw-payload "{raw}"
                                                 :status :valid
                                                 :value {:next-action :handoff-to-fix}}}
          envelope {:outcome :ok
                    :outputs {:classification structured-result}}
          state' (-> state
                     (workflow-recording/start-latest-attempt run-id "plan")
                     (workflow-recording/record-step-result run-id "plan" envelope))
          run (get-in state' [:workflows :runs run-id])]
      (is (= structured-result
             (get-in run [:step-runs "plan" :accepted-result :outputs :classification])))
      (is (= structured-result
             (get-in run [:step-runs "plan" :attempts 0 :result-envelope :outputs :classification])))
      (is (= structured-result
             (get-in (last (:history run))
                     [:data :envelope :outputs :classification])))
      (is (true? (workflow-model/valid-workflow-run? run))))))

(deftest record-actor-result-test
  (testing "record-actor-result remains an explicit alias for judged-step success recording"
    (let [[state run-id] (base-state-with-run)
          state' (-> state
                     (workflow-recording/start-latest-attempt run-id "plan")
                     (workflow-recording/record-actor-result run-id "plan"
                                                             {:outcome :ok :outputs {:text "plan output"}}))
          run    (get-in state' [:workflows :runs run-id])]
      (is (= "plan" (:current-step-id run)))
      (is (= {:outcome :ok :outputs {:text "plan output"}}
             (get-in run [:step-runs "plan" :accepted-result])))
      (is (= :succeeded (get-in run [:step-runs "plan" :attempts 0 :status])))
      (is (= :running (:status run))))))

(deftest record-attempt-execution-failure-test
  (testing "record-attempt-execution-failure updates attempt failure metadata without owning run control flow"
    (let [[state run-id] (base-state-with-run)
          state' (-> state
                     (workflow-recording/start-latest-attempt run-id "plan")
                     (workflow-recording/record-attempt-execution-failure run-id "plan" {:message "boom"}))
          run    (get-in state' [:workflows :runs run-id])]
      (is (= :running (:status run)))
      (is (= "plan" (:current-step-id run)))
      (is (= :execution-failed (get-in run [:step-runs "plan" :attempts 0 :status])))
      (is (= "boom" (get-in run [:step-runs "plan" :attempts 0 :execution-error :message]))))))

(def judged-definition
  {:definition-id "plan-build-review"
   :name "Plan Build Review"
   :steps [{:name "plan"
            :type :session
            :contributions [{:type :template
                             :text "Plan {{task}}"
                             :vars {"task" {:from :workflow-input :path [:task]}}}]}
           {:name "build"
            :type :session
            :contributions [{:type :template
                             :text "Build {{plan}}"
                             :vars {"plan" {:from {:step "plan" :yield :text}}}}]}
           {:name "review"
            :type :session
            :contributions [{:type :template
                             :text "Review {{build}}"
                             :vars {"build" {:from {:step "build" :yield :text}}}}]
            :judge {:type :llm
                    :contributions [{:type :template
                                     :text "APPROVED or REVISE?"
                                     :vars {}}]}
            :on {"APPROVED" {:goto :next}
                 "REVISE" {:goto "build" :max-iterations 3}}}]})

(defn- judged-state-at-review
  "Set up a workflow run that has reached the review step with accepted results for plan and build."
  []
  (let [[state1 _ _] (workflow-registry/register-definition {:workflows (workflow-model/initial-workflow-state)}
                                                            judged-definition)
        [state2 run-id _] (workflow-runtime/create-run state1 {:definition-id "plan-build-review"
                                                               :run-id "run-j1"
                                                               :workflow-input {:task "ship it"}})
        attempt (workflow-attempts/new-attempt {:attempt-id "r-a1"
                                                :status :pending
                                                :execution-session-id "child-review"})
        state3 (-> state2
                   (assoc-in [:workflows :runs run-id :current-step-id] "review")
                   (assoc-in [:workflows :runs run-id :step-runs "plan" :accepted-result]
                             {:outcome :ok :outputs {:text "plan output"}})
                   (assoc-in [:workflows :runs run-id :step-runs "build" :accepted-result]
                             {:outcome :ok :outputs {:text "build output"}})
                   (assoc-in [:workflows :runs run-id :step-runs "build" :iteration-count] 1)
                   (update-in [:workflows :runs run-id]
                              #(workflow-attempts/append-attempt-to-run % "review" attempt))
                   (workflow-recording/start-latest-attempt run-id "review")
                   (workflow-recording/record-actor-result run-id "review"
                                                           {:outcome :ok :outputs {:text "review output"}}))]
    [state3 run-id]))

(deftest record-judge-result-test
  (testing "record-judge-result writes judge metadata without changing run status/current-step-id"
    (let [[state run-id] (judged-state-at-review)
          judge-result {:judge-session-id "judge-r"
                        :judge-output "REVISE"
                        :judge-event "REVISE"
                        :routing-result {:action :goto :target "build"}}
          state' (workflow-recording/record-judge-result state run-id "review" judge-result)
          run    (get-in state' [:workflows :runs run-id])
          attempt (get-in run [:step-runs "review" :attempts 0])]
      (is (= :running (:status run)))
      (is (= "review" (:current-step-id run)))
      (is (= "judge-r" (:judge-session-id attempt)))
      (is (= "REVISE" (:judge-output attempt)))
      (is (= "REVISE" (:judge-event attempt))))))
