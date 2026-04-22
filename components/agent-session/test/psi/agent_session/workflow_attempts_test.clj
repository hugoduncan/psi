(ns psi.agent-session.workflow-attempts-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.core :as session-core]
   [psi.agent-session.session-state :as session-state]
   [psi.agent-session.test-support :as test-support]
   [psi.agent-session.workflow-attempts :as workflow-attempts]))

(defn- create-session-context
  ([]
   (create-session-context {}))
  ([opts]
   (let [ctx (session-core/create-context (test-support/safe-context-opts opts))
         sd  (session-core/new-session-in! ctx nil {})]
     [ctx (:session-id sd)])))

(deftest create-step-attempt-session-test
  (testing "each workflow step attempt gets one canonical child session with workflow linkage"
    (let [[ctx parent-session-id] (create-session-context {:persist? false})
          {:keys [attempt execution-session]}
          (workflow-attempts/create-step-attempt-session!
           ctx
           parent-session-id
           {:workflow-run-id "run-1"
            :workflow-step-id "plan"
            :attempt-id "attempt-1"
            :session-name "workflow plan attempt"
            :tool-defs []
            :thinking-level :off})]
      (is (= "attempt-1" (:attempt-id attempt)))
      (is (= :pending (:status attempt)))
      (is (= (:session-id execution-session) (:execution-session-id attempt)))
      (is (= :agent (:spawn-mode execution-session)))
      (is (true? (:workflow-owned? execution-session)))
      (is (= "run-1" (:workflow-run-id execution-session)))
      (is (= "plan" (:workflow-step-id execution-session)))
      (is (= "attempt-1" (:workflow-attempt-id execution-session)))
      (is (= parent-session-id (:parent-session-id execution-session)))
      (is (instance? java.time.Instant (:created-at execution-session)))
      (is (instance? java.time.Instant (:updated-at execution-session)))
      (is (= (:created-at execution-session) (:updated-at execution-session)))
      (is (some? (session-state/agent-ctx-in ctx (:session-id execution-session))))
      (is (some? (session-state/sc-session-id-in ctx (:session-id execution-session))))
      (session-core/shutdown-context! ctx))))

(deftest append-attempt-to-run-test
  (testing "append-attempt-to-run records attempt under the selected step"
    (let [run {:run-id "run-1"
               :status :pending
               :effective-definition {:definition-id "def"
                                      :step-order ["plan"]
                                      :steps {"plan" {:executor {:type :agent}
                                                      :result-schema :any
                                                      :retry-policy {:max-attempts 1 :retry-on #{:execution-failed}}}}}
               :workflow-input {}
               :current-step-id "plan"
               :step-runs {"plan" {:step-id "plan" :attempts []}}
               :history []
               :created-at (java.time.Instant/now)
               :updated-at (java.time.Instant/now)}
          attempt {:attempt-id "a1"
                   :status :pending
                   :execution-session-id "child-1"
                   :created-at (java.time.Instant/now)
                   :updated-at (java.time.Instant/now)}
          run' (workflow-attempts/append-attempt-to-run run "plan" attempt)]
      (is (= [attempt] (get-in run' [:step-runs "plan" :attempts]))))))
