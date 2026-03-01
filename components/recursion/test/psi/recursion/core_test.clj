(ns psi.recursion.core-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [malli.core :as m]
   [psi.recursion.core :as core]
   [psi.recursion.future-state :as future-state]
   [psi.recursion.policy :as policy]))

(deftest create-context-defaults
  ;; Verify context creation with default state matches spec locked decisions
  (testing "default context"
    (let [ctx   (core/create-context)
          state (core/get-state-in ctx)]

      (testing "status starts idle"
        (is (= :idle (:status state))))

      (testing "no current future state"
        (is (nil? (:current-future-state state))))

      (testing "default policy matches spec locked decisions"
        (let [p (:policy state)]
          (is (true? (:require-human-approval p)))
          (is (= 1 (:max-actions-per-cycle p)))
          (is (true? (:atomic-only p)))
          (is (true? (:rollback-on-verification-failure p)))
          (is (= 2 (:max-retries-per-goal p)))))

      (testing "default config has all accepted trigger types"
        (let [c (:config state)]
          (is (= #{:manual :session-end :graph-changed
                   :memory-updated :verification-failed}
                 (:accepted-trigger-types c)))))

      (testing "hooks and cycles start empty"
        (is (= [] (:hooks state)))
        (is (= [] (:cycles state))))

      (testing "no paused reason or error"
        (is (nil? (:paused-reason state)))
        (is (nil? (:last-error state)))))))

(deftest create-context-with-overrides
  ;; Verify state and config overrides are applied
  (testing "state overrides"
    (let [ctx   (core/create-context {:state-overrides {:status :paused
                                                        :paused-reason "test"}})
          state (core/get-state-in ctx)]
      (is (= :paused (:status state)))
      (is (= "test" (:paused-reason state)))))

  (testing "config overrides"
    (let [ctx   (core/create-context {:config-overrides {:trusted-local-mode-enabled true}})
          state (core/get-state-in ctx)]
      (is (true? (get-in state [:config :trusted-local-mode-enabled])))))

  (testing "config overrides merge with defaults"
    (let [ctx   (core/create-context {:config-overrides {:trusted-local-mode-enabled true}})
          state (core/get-state-in ctx)]
      ;; default keys still present
      (is (= #{:manual :session-end :graph-changed
               :memory-updated :verification-failed}
             (get-in state [:config :accepted-trigger-types]))))))

(deftest state-access-and-swap
  ;; Verify get-state-in and swap-state-in! work correctly
  (testing "get-state-in returns current state"
    (let [ctx (core/create-context)]
      (is (= :idle (:status (core/get-state-in ctx))))))

  (testing "swap-state-in! updates state"
    (let [ctx (core/create-context)]
      (core/swap-state-in! ctx assoc :status :observing)
      (is (= :observing (:status (core/get-state-in ctx))))))

  (testing "swap-state-in! with extra args"
    (let [ctx (core/create-context)]
      (core/swap-state-in! ctx assoc-in [:policy :max-actions-per-cycle] 5)
      (is (= 5 (get-in (core/get-state-in ctx) [:policy :max-actions-per-cycle]))))))

(deftest default-policy-schema-validation
  ;; Verify default policy validates against GuardrailPolicy schema
  (testing "default-policy conforms to GuardrailPolicy"
    (is (true? (policy/valid-policy? (policy/default-policy)))))

  (testing "invalid policy fails validation"
    (is (false? (policy/valid-policy? {:require-human-approval "yes"})))))

(deftest default-config-values
  ;; Verify default config values match spec
  (testing "default config"
    (let [c (policy/default-config)]
      (is (= :medium (:default-horizon c)))
      (is (false? (:trusted-local-mode-enabled c)))
      (is (true? (:auto-approve-low-risk-in-trusted-local-mode c)))
      (is (= #{"tests" "lint" "eql-health"} (:required-verification-checks c))))))

(deftest initial-future-state-schema-validation
  ;; Verify initial-future-state validates against FutureStateSnapshot schema
  (testing "initial-future-state conforms to schema"
    (is (true? (future-state/valid? (future-state/initial-future-state)))))

  (testing "initial-future-state has version 0"
    (is (= 0 (:version (future-state/initial-future-state)))))

  (testing "initial-future-state has empty goals"
    (is (= [] (:goals (future-state/initial-future-state))))))

(deftest future-state-next-version
  ;; Verify version incrementing
  (testing "next-version increments from 0"
    (let [fs0 (future-state/initial-future-state)
          fs1 (future-state/next-version fs0)]
      (is (= 1 (:version fs1)))
      (is (inst? (:generated-at fs1)))))

  (testing "next-version increments from N"
    (let [fs (-> (future-state/initial-future-state)
                 future-state/next-version
                 future-state/next-version
                 future-state/next-version)]
      (is (= 3 (:version fs))))))

(deftest future-state-advance-goals
  ;; Verify goal advancement
  (testing "advance-goals marks specified goals as complete"
    (let [fs (assoc (future-state/initial-future-state)
                    :goals [{:id "g1" :title "Goal 1" :description "d"
                             :priority :high :success-criteria #{}
                             :constraints #{} :status :active}
                            {:id "g2" :title "Goal 2" :description "d"
                             :priority :medium :success-criteria #{}
                             :constraints #{} :status :proposed}])
          advanced (future-state/advance-goals fs #{"g1"})]
      (is (= :complete (-> advanced :goals first :status)))
      (is (= :proposed (-> advanced :goals second :status)))
      (is (= 1 (:version advanced))))))

(deftest future-state-add-blockers
  ;; Verify blocker addition from evidence
  (testing "add-blockers creates blocked goals from evidence"
    (let [fs (future-state/initial-future-state)
          blocked (future-state/add-blockers fs #{"test failure" "lint error"})]
      (is (= 2 (count (:goals blocked))))
      (is (every? #(= :blocked (:status %)) (:goals blocked)))
      (is (= 1 (:version blocked))))))

(deftest trigger-signal-schema-validation
  ;; Verify TriggerSignal schema
  (testing "valid trigger signal"
    (is (true? (policy/valid-trigger-signal?
                {:type :manual
                 :reason "user requested"
                 :payload {}
                 :timestamp (java.time.Instant/now)}))))

  (testing "invalid trigger signal — missing type"
    (is (false? (policy/valid-trigger-signal?
                 {:reason "test"
                  :payload {}
                  :timestamp (java.time.Instant/now)})))))

(deftest initial-state-controller-status-schema
  ;; Verify controller status values are valid
  (testing "initial status is valid ControllerStatus"
    (is (m/validate policy/ControllerStatus :idle)))

  (testing "all expected statuses validate"
    (doseq [s [:idle :observing :planning :awaiting-approval
               :executing :verifying :learning :paused :error]]
      (is (m/validate policy/ControllerStatus s) (str s " should be valid")))))
