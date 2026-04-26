(ns psi.agent-session.workflow-progression-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.workflow-attempts :as workflow-attempts]
   [psi.agent-session.workflow-model :as workflow-model]
   [psi.agent-session.workflow-progression-recording :as workflow-recording]
   [psi.agent-session.workflow-runtime :as workflow-runtime]
   [psi.agent-session.workflow-sequential-compat-test-support :as workflow-seq-compat]))

(def definition
  {:definition-id "plan-build-review"
   :name "Plan Build Review"
   :step-order ["plan" "build"]
   :steps {"plan" {:executor {:type :agent :profile "planner" :mode :sync}
                   :result-schema [:map [:outcome [:= :ok]] [:outputs :map]]
                   :retry-policy {:max-attempts 2 :retry-on #{:execution-failed :validation-failed}}}
           "build" {:executor {:type :agent :profile "builder" :mode :async}
                    :result-schema [:map [:outcome [:= :ok]] [:outputs :map]]
                    :retry-policy {:max-attempts 1 :retry-on #{:execution-failed}}}}})

(defn- base-state-with-run
  []
  (let [[state1 _ _] (workflow-runtime/register-definition {:workflows (workflow-model/initial-workflow-state)}
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

(deftest submit-ok-envelope-advances-to-next-step-test
  (testing "valid ok envelope succeeds step and advances workflow to next step"
    (let [[state run-id] (base-state-with-run)
          state'         (-> state
                             (workflow-recording/start-latest-attempt run-id "plan")
                             (workflow-seq-compat/submit-result-envelope run-id "plan"
                                                                         {:outcome :ok
                                                                          :outputs {:plan "do it"}}))
          run            (get-in state' [:workflows :runs run-id])]
      (is (= :running (:status run)))
      (is (= "build" (:current-step-id run)))
      (is (= :succeeded (get-in run [:step-runs "plan" :attempts 0 :status])))
      (is (= {:outcome :ok :outputs {:plan "do it"}}
             (get-in run [:step-runs "plan" :accepted-result]))))))

(deftest submit-ok-envelope-completes-final-step-test
  (testing "valid ok envelope on final step completes workflow"
    (let [[state1 _ _] (workflow-runtime/register-definition {:workflows (workflow-model/initial-workflow-state)} definition)
          [state2 run-id _] (workflow-runtime/create-run state1 {:definition-id "plan-build-review"
                                                                 :run-id "run-2"})
          state3            (assoc-in state2 [:workflows :runs run-id :current-step-id] "build")
          state4            (assoc-in state3 [:workflows :runs run-id :step-runs "build" :attempts]
                                      [(workflow-attempts/new-attempt {:attempt-id "b1"
                                                                       :status :pending
                                                                       :execution-session-id "child-2"})])
          state'            (-> state4
                                (workflow-recording/start-latest-attempt run-id "build")
                                (workflow-seq-compat/submit-result-envelope run-id "build"
                                                                            {:outcome :ok
                                                                             :outputs {:review "approved"}}))
          run               (get-in state' [:workflows :runs run-id])]
      (is (= :completed (:status run)))
      (is (nil? (:current-step-id run)))
      (is (= :succeeded (get-in run [:step-runs "build" :attempts 0 :status]))))))

(deftest submit-blocked-envelope-blocks-run-test
  (testing "blocked envelope moves attempt and run into blocked state"
    (let [[state run-id] (base-state-with-run)
          state'         (-> state
                             (workflow-recording/start-latest-attempt run-id "plan")
                             (workflow-seq-compat/submit-result-envelope run-id "plan"
                                                                         {:outcome :blocked
                                                                          :blocked {:question "need approval"}}))
          run            (get-in state' [:workflows :runs run-id])]
      (is (= :blocked (:status run)))
      (is (= {:question "need approval"} (:blocked run)))
      (is (= :blocked (get-in run [:step-runs "plan" :attempts 0 :status]))))))

(deftest validation-failure-retries-when-available-test
  (testing "step-schema validation failure retries when retry policy allows it"
    (let [[state run-id] (base-state-with-run)
          state'         (-> state
                             (workflow-recording/start-latest-attempt run-id "plan")
                             (workflow-seq-compat/submit-result-envelope run-id "plan"
                                                                         {:outcome :ok
                                                                          :outputs "wrong-shape"}))
          run            (get-in state' [:workflows :runs run-id])]
      (is (= :running (:status run)))
      (is (= :validation-failed (get-in run [:step-runs "plan" :attempts 0 :status])))))

  (testing "generic envelope validation failure also retries when retry policy allows it"
    (let [[state run-id] (base-state-with-run)
          state'         (-> state
                             (workflow-recording/start-latest-attempt run-id "plan")
                             (workflow-seq-compat/submit-result-envelope run-id "plan"
                                                                         {:outputs {:plan "missing outcome"}}))
          run            (get-in state' [:workflows :runs run-id])]
      (is (= :running (:status run)))
      (is (= :validation-failed (get-in run [:step-runs "plan" :attempts 0 :status]))))))

(deftest execution-failure-fails-when-retries-exhausted-test
  (testing "execution failure on a no-retry-remaining step fails the workflow"
    (let [[state1 _ _] (workflow-runtime/register-definition {:workflows (workflow-model/initial-workflow-state)} definition)
          [state2 run-id _] (workflow-runtime/create-run state1 {:definition-id "plan-build-review"
                                                                 :run-id "run-3"})
          state3            (assoc-in state2 [:workflows :runs run-id :current-step-id] "build")
          state4            (assoc-in state3 [:workflows :runs run-id :step-runs "build" :attempts]
                                      [(workflow-attempts/new-attempt {:attempt-id "b1"
                                                                       :status :running
                                                                       :execution-session-id "child-2"})])
          state'            (workflow-seq-compat/record-execution-failure state4 run-id "build" {:message "provider error"})
          run               (get-in state' [:workflows :runs run-id])]
      (is (= :failed (:status run)))
      (is (= :execution-failed (get-in run [:step-runs "build" :attempts 0 :status]))))))

(deftest resume-run-test
  (testing "workflow-runtime/resume-run clears blocked payload and returns to running"
    (let [[state run-id] (base-state-with-run)
          blocked-state   (-> state
                              (workflow-recording/start-latest-attempt run-id "plan")
                              (workflow-seq-compat/submit-result-envelope run-id "plan"
                                                                          {:outcome :blocked
                                                                           :blocked {:question "need approval"}}))
          [resumed-state resumed-run] (workflow-runtime/resume-run blocked-state run-id)
          run             (get-in resumed-state [:workflows :runs run-id])]
      (is (= :running (:status resumed-run)))
      (is (= resumed-run run))
      (is (nil? (:blocked run))))))

;;; Judge-aware progression

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
   :step-order ["plan" "build" "review"]
   :steps {"plan"   {:executor {:type :agent :profile "planner" :mode :sync}
                     :result-schema [:map [:outcome [:= :ok]] [:outputs :map]]
                     :retry-policy {:max-attempts 1 :retry-on #{:execution-failed}}}
           "build"  {:executor {:type :agent :profile "builder" :mode :sync}
                     :result-schema [:map [:outcome [:= :ok]] [:outputs :map]]
                     :retry-policy {:max-attempts 1 :retry-on #{:execution-failed}}}
           "review" {:executor {:type :agent :profile "reviewer" :mode :sync}
                     :result-schema [:map [:outcome [:= :ok]] [:outputs :map]]
                     :retry-policy {:max-attempts 1 :retry-on #{:execution-failed}}
                     :judge {:prompt "APPROVED or REVISE?"}
                     :on {"APPROVED" {:goto :next}
                          "REVISE"   {:goto "build" :max-iterations 3}}}}})

(defn- judged-state-at-review
  "Set up a workflow run that has reached the review step with accepted results for plan and build."
  []
  (let [[state1 _ _] (workflow-runtime/register-definition {:workflows (workflow-model/initial-workflow-state)}
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

(deftest submit-judged-result-goto-test
  (testing "judge REVISE routes to build step"
    (let [[state run-id] (judged-state-at-review)
          judge-result {:judge-session-id "judge-1"
                        :judge-output "REVISE"
                        :judge-event "REVISE"
                        :routing-result {:action :goto :target "build"}}
          state' (workflow-seq-compat/submit-judged-result state run-id "review" judge-result)
          run    (get-in state' [:workflows :runs run-id])]
      ;; Routed to build
      (is (= "build" (:current-step-id run)))
      (is (= :running (:status run)))
      ;; Build iteration count NOT incremented here — that happens in execute-current-step!
      (is (= 1 (get-in run [:step-runs "build" :iteration-count])))
      ;; Judge fields on attempt
      (let [attempt (get-in run [:step-runs "review" :attempts 0])]
        (is (= "judge-1" (:judge-session-id attempt)))
        (is (= "REVISE" (:judge-output attempt)))
        (is (= "REVISE" (:judge-event attempt))))
      ;; History has :verdict/goto
      (is (some #(= :verdict/goto (:event %)) (:history run))))))

(deftest submit-judged-result-complete-test
  (testing "judge APPROVED completes the workflow"
    (let [[state run-id] (judged-state-at-review)
          judge-result {:judge-session-id "judge-2"
                        :judge-output "APPROVED"
                        :judge-event "APPROVED"
                        :routing-result {:action :complete}}
          state' (workflow-seq-compat/submit-judged-result state run-id "review" judge-result)
          run    (get-in state' [:workflows :runs run-id])]
      (is (= :completed (:status run)))
      (is (nil? (:current-step-id run)))
      (is (some? (:finished-at run)))
      (is (= :completed (get-in run [:terminal-outcome :outcome])))
      ;; History has :verdict/advance
      (is (some #(= :verdict/advance (:event %)) (:history run))))))

(deftest submit-judged-result-fail-test
  (testing "iteration exhaustion fails the workflow"
    (let [[state run-id] (judged-state-at-review)
          judge-result {:judge-session-id "judge-3"
                        :judge-output "REVISE"
                        :judge-event "REVISE"
                        :routing-result {:action :fail :reason :iteration-exhausted :step-id "build"}}
          state' (workflow-seq-compat/submit-judged-result state run-id "review" judge-result)
          run    (get-in state' [:workflows :runs run-id])]
      (is (= :failed (:status run)))
      (is (some? (:finished-at run)))
      (is (= :failed (get-in run [:terminal-outcome :outcome])))
      (is (= :iteration-exhausted (get-in run [:terminal-outcome :reason])))
      ;; History has :verdict/exhausted
      (is (some #(= :verdict/exhausted (:event %)) (:history run))))))

(deftest submit-judged-result-no-match-test
  (testing "judge no-match (retries exhausted) fails the workflow"
    (let [[state run-id] (judged-state-at-review)
          judge-result {:judge-session-id "judge-4"
                        :judge-output "hmm not sure"
                        :judge-event nil
                        :routing-result {:action :no-match}}
          state' (workflow-seq-compat/submit-judged-result state run-id "review" judge-result)
          run    (get-in state' [:workflows :runs run-id])]
      (is (= :failed (:status run)))
      (is (= :judge-no-match (get-in run [:terminal-outcome :reason]))))))
