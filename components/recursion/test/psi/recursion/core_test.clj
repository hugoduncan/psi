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

;;; --- Trigger intake and readiness gating tests ---

(def ^:private all-ready
  "System state with all readiness flags true."
  {:query-ready true
   :graph-ready true
   :introspection-ready true
   :memory-ready true})

(defn- make-trigger
  "Create a trigger signal of given type."
  ([ttype]
   (make-trigger ttype "test trigger"))
  ([ttype reason]
   {:type      ttype
    :reason    reason
    :payload   {}
    :timestamp (java.time.Instant/now)}))

(deftest register-hooks-in-test
  (testing "register-hooks-in! populates hooks from config"
    (let [ctx   (core/create-context)
          hooks (core/register-hooks-in! ctx)
          state (core/get-state-in ctx)]
      (is (= 5 (count hooks)) "should have one hook per accepted trigger type")
      (is (= 5 (count (:hooks state))))
      (is (every? :enabled hooks) "all hooks enabled by default config")
      (is (every? #(string? (:id %)) hooks))
      (is (every? #(keyword? (:trigger-type %)) hooks))))

  (testing "register-hooks-in! respects enabled subset"
    (let [ctx   (core/create-context {:config-overrides {:enabled-trigger-hooks #{:manual}}})
          hooks (core/register-hooks-in! ctx)]
      (is (= 1 (count (filter :enabled hooks))) "only :manual should be enabled")
      (is (= 4 (count (remove :enabled hooks)))))))

(deftest handle-trigger-accepted-test
  ;; AC #1: accepted trigger creates cycle in observing, controller becomes observing
  (testing "accepted trigger with all readiness"
    (let [ctx    (core/create-context)
          _      (core/register-hooks-in! ctx)
          result (core/handle-trigger-in! ctx (make-trigger :manual) all-ready)
          state  (core/get-state-in ctx)]
      (is (= :accepted (:result result)))
      (is (string? (:cycle-id result)))
      (is (= :observing (:status state)) "controller should be observing")
      (is (= 1 (count (:cycles state))))
      (let [cycle (first (:cycles state))]
        (is (= :observing (:status cycle)) "cycle should be observing")
        (is (= (:cycle-id result) (:cycle-id cycle)))
        (is (= :manual (get-in cycle [:trigger :type])))
        (is (inst? (:started-at cycle)))
        (is (nil? (:ended-at cycle)))
        (is (nil? (:observation cycle)))
        (is (nil? (:proposal cycle)))
        (is (= [] (:execution-attempts cycle)))
        (is (nil? (:verification cycle)))
        (is (nil? (:outcome cycle)))
        (is (= #{} (:learning-memory-ids cycle)))))))

(deftest handle-trigger-ignored-test
  ;; AC #2: disabled trigger type returns ignored, no state change, no cycle
  (testing "disabled trigger type is ignored"
    (let [ctx    (core/create-context
                  {:config-overrides {:enabled-trigger-hooks #{:manual}}})
          _      (core/register-hooks-in! ctx)
          result (core/handle-trigger-in! ctx (make-trigger :graph-changed) all-ready)
          state  (core/get-state-in ctx)]
      (is (= :ignored (:result result)))
      (is (nil? (:cycle-id result)))
      (is (= :idle (:status state)) "controller state unchanged")
      (is (= [] (:cycles state)) "no cycle created"))))

(deftest handle-trigger-blocked-test
  ;; AC #3: readiness fails → controller paused, blocked cycle created
  (testing "blocked when memory not ready"
    (let [ctx    (core/create-context)
          _      (core/register-hooks-in! ctx)
          result (core/handle-trigger-in! ctx (make-trigger :manual)
                                          (assoc all-ready :memory-ready false))
          state  (core/get-state-in ctx)]
      (is (= :blocked (:result result)))
      (is (string? (:cycle-id result)))
      (is (= :paused (:status state)) "controller should be paused")
      (is (= "recursion_prerequisites_not_ready" (:paused-reason state)))
      (let [cycle (first (:cycles state))]
        (is (= :blocked (:status cycle))))))

  (testing "blocked when query not ready"
    (let [ctx    (core/create-context)
          result (core/handle-trigger-in! ctx (make-trigger :manual)
                                          (assoc all-ready :query-ready false))
          state  (core/get-state-in ctx)]
      (is (= :blocked (:result result)))
      (is (= :paused (:status state)))))

  (testing "blocked when introspection not ready"
    (let [ctx    (core/create-context)
          result (core/handle-trigger-in! ctx (make-trigger :manual)
                                          (assoc all-ready :introspection-ready false))]
      (is (= :blocked (:result result)))))

  (testing "blocked when graph not ready"
    (let [ctx    (core/create-context)
          result (core/handle-trigger-in! ctx (make-trigger :manual)
                                          (assoc all-ready :graph-ready false))]
      (is (= :blocked (:result result))))))

(deftest handle-trigger-rejected-unknown-test
  ;; Unknown trigger type is rejected
  (testing "unknown trigger type rejected"
    (let [ctx    (core/create-context)
          result (core/handle-trigger-in! ctx (make-trigger :unknown-type) all-ready)
          state  (core/get-state-in ctx)]
      (is (= :rejected (:result result)))
      (is (= :unknown-trigger-type (:reason result)))
      (is (= :idle (:status state)) "controller state unchanged")
      (is (= [] (:cycles state)) "no cycle created"))))

(deftest handle-trigger-rejected-busy-test
  ;; Controller busy (not idle) is rejected
  (testing "rejected when controller not idle"
    (let [ctx    (core/create-context {:state-overrides {:status :observing}})
          result (core/handle-trigger-in! ctx (make-trigger :manual) all-ready)
          state  (core/get-state-in ctx)]
      (is (= :rejected (:result result)))
      (is (= :controller-busy (:reason result)))
      (is (= :observing (:status state)) "status unchanged")))

  (testing "rejected when active cycle exists"
    (let [ctx    (core/create-context)
          ;; First trigger succeeds
          _      (core/handle-trigger-in! ctx (make-trigger :manual) all-ready)
          ;; Second trigger while first is active
          result (core/handle-trigger-in! ctx (make-trigger :manual) all-ready)]
      (is (= :rejected (:result result)))
      (is (= :controller-busy (:reason result))))))
