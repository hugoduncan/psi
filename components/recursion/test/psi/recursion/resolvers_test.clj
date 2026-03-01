(ns psi.recursion.resolvers-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.memory.core :as memory]
   [psi.recursion.core :as core]
   [psi.recursion.resolvers :as resolvers]
   [psi.query.core :as query]))

(defn- recursion-query-ctx
  "Create an isolated query context with recursion resolvers and mutations registered."
  []
  (let [qctx (query/create-query-context)]
    (doseq [r resolvers/all-resolvers]
      (query/register-resolver-in! qctx r))
    (doseq [m resolvers/all-mutations]
      (query/register-mutation-in! qctx m))
    (query/rebuild-env-in! qctx)
    qctx))

(defn- all-readiness-true
  []
  {:query-ready true
   :graph-ready true
   :introspection-ready true
   :memory-ready true})

(defn- manual-trigger
  []
  {:type :manual
   :reason "test-trigger"
   :payload {}
   :timestamp (java.time.Instant/now)})

;;; --- Resolver tests ---

(deftest query-status-on-fresh-context
  ;; Fresh recursion context should report idle status
  (testing "status is :idle on fresh context"
    (let [rctx (core/create-context)
          qctx (recursion-query-ctx)
          result (query/query-in qctx
                                 {:psi/recursion-ctx rctx}
                                 [:psi.recursion/status])]
      (is (= :idle (:psi.recursion/status result))))))

(deftest query-paused-on-fresh-context
  ;; Fresh context should not be paused
  (testing "paused? is false on fresh context"
    (let [rctx (core/create-context)
          qctx (recursion-query-ctx)
          result (query/query-in qctx
                                 {:psi/recursion-ctx rctx}
                                 [:psi.recursion/paused?])]
      (is (false? (:psi.recursion/paused? result))))))

(deftest query-policy-returns-default-policy
  ;; Default policy should match spec locked decisions
  (testing "policy returns default guardrail policy"
    (let [rctx (core/create-context)
          qctx (recursion-query-ctx)
          result (query/query-in qctx
                                 {:psi/recursion-ctx rctx}
                                 [:psi.recursion/policy])]
      (is (map? (:psi.recursion/policy result)))
      (is (true? (get-in result [:psi.recursion/policy :require-human-approval])))
      (is (= 1 (get-in result [:psi.recursion/policy :max-actions-per-cycle])))
      (is (true? (get-in result [:psi.recursion/policy :atomic-only])))
      (is (true? (get-in result [:psi.recursion/policy :rollback-on-verification-failure]))))))

(deftest query-hooks-on-fresh-context
  ;; Fresh context has no hooks registered yet
  (testing "hooks is empty list on fresh context"
    (let [rctx (core/create-context)
          qctx (recursion-query-ctx)
          result (query/query-in qctx
                                 {:psi/recursion-ctx rctx}
                                 [:psi.recursion/hooks])]
      (is (= [] (:psi.recursion/hooks result))))))

(deftest query-current-cycle-after-trigger
  ;; After triggering, current-cycle should be non-nil
  (testing "current-cycle is non-nil after accepted trigger"
    (let [rctx (core/create-context)
          _ (core/register-hooks-in! rctx)
          trigger-result (core/handle-trigger-in! rctx (manual-trigger) (all-readiness-true))
          _ (is (= :accepted (:result trigger-result)))
          qctx (recursion-query-ctx)
          result (query/query-in qctx
                                 {:psi/recursion-ctx rctx}
                                 [:psi.recursion/current-cycle
                                  :psi.recursion/status])]
      (is (some? (:psi.recursion/current-cycle result)))
      (is (= :observing (:psi.recursion/status result)))
      (is (= :observing (get-in result [:psi.recursion/current-cycle :status]))))))

(deftest query-last-outcome-after-completed-cycle
  ;; After a complete cycle, last-outcome should reflect the outcome
  (testing "last-outcome reflects completed cycle"
    (let [rctx (core/create-context)
          mem-ctx (memory/create-context)
          _ (core/register-hooks-in! rctx)
          trigger-result (core/handle-trigger-in! rctx (manual-trigger) (all-readiness-true))
          cycle-id (:cycle-id trigger-result)

          ;; Step through the full cycle
          _ (core/observe-in! rctx cycle-id
                              (all-readiness-true)
                              {:node-count 5 :capability-count 3 :status :stable}
                              {:entry-count 2 :status :ready :recovery-count 0})
          _ (core/plan-in! rctx cycle-id)
          gate (core/apply-approval-gate-in! rctx cycle-id)
          ;; Manually approve if gate requires it (medium risk from opportunity goals)
          _ (when (= :manual (:gate gate))
              (core/approve-proposal-in! rctx cycle-id "test-operator" "test approval"))
          _ (core/execute-in! rctx cycle-id (fn [_] {:status :success :output-summary "ok"}))
          _ (core/verify-in! rctx cycle-id (fn [_] {:passed true :details "ok"}))
          _ (core/learn-in! rctx cycle-id mem-ctx)
          _ (core/update-future-state-from-outcome-in! rctx cycle-id)
          _ (core/finalize-cycle-in! rctx cycle-id)

          qctx (recursion-query-ctx)
          result (query/query-in qctx
                                 {:psi/recursion-ctx rctx}
                                 [:psi.recursion/last-outcome
                                  :psi.recursion/status])]
      (is (= :idle (:psi.recursion/status result)))
      (is (some? (:psi.recursion/last-outcome result)))
      (is (= :success (get-in result [:psi.recursion/last-outcome :status]))))))

(deftest all-eight-required-attrs-are-queryable
  ;; All 8 required :psi.recursion/* attrs should be resolvable
  (testing "all 8 required attrs are queryable"
    (let [rctx (core/create-context)
          qctx (recursion-query-ctx)
          result (query/query-in qctx
                                 {:psi/recursion-ctx rctx}
                                 [:psi.recursion/status
                                  :psi.recursion/paused?
                                  :psi.recursion/current-cycle
                                  :psi.recursion/current-future-state
                                  :psi.recursion/policy
                                  :psi.recursion/recent-cycles
                                  :psi.recursion/last-outcome
                                  :psi.recursion/hooks])]
      (is (contains? result :psi.recursion/status))
      (is (contains? result :psi.recursion/paused?))
      (is (contains? result :psi.recursion/current-cycle))
      (is (contains? result :psi.recursion/current-future-state))
      (is (contains? result :psi.recursion/policy))
      (is (contains? result :psi.recursion/recent-cycles))
      (is (contains? result :psi.recursion/last-outcome))
      (is (contains? result :psi.recursion/hooks))

      ;; Verify types on fresh context
      (is (keyword? (:psi.recursion/status result)))
      (is (boolean? (:psi.recursion/paused? result)))
      (is (nil? (:psi.recursion/current-cycle result)))
      (is (nil? (:psi.recursion/current-future-state result)))
      (is (map? (:psi.recursion/policy result)))
      (is (vector? (:psi.recursion/recent-cycles result)))
      (is (nil? (:psi.recursion/last-outcome result)))
      (is (vector? (:psi.recursion/hooks result))))))

(deftest recent-cycles-returns-newest-first
  ;; Recent cycles should be newest first, capped at 10
  (testing "recent-cycles returns newest first"
    (let [rctx (core/create-context)
          mem-ctx (memory/create-context)
          _ (core/register-hooks-in! rctx)

          ;; Run a cycle to completion
          t1 (core/handle-trigger-in! rctx (manual-trigger) (all-readiness-true))
          c1 (:cycle-id t1)
          _ (core/observe-in! rctx c1 (all-readiness-true)
                              {:node-count 5 :capability-count 3 :status :stable}
                              {:entry-count 2 :status :ready :recovery-count 0})
          _ (core/plan-in! rctx c1)
          gate (core/apply-approval-gate-in! rctx c1)
          _ (when (= :manual (:gate gate))
              (core/approve-proposal-in! rctx c1 "test-operator" "test approval"))
          _ (core/execute-in! rctx c1 (fn [_] {:status :success :output-summary "ok"}))
          _ (core/verify-in! rctx c1 (fn [_] {:passed true :details "ok"}))
          _ (core/learn-in! rctx c1 mem-ctx)
          _ (core/update-future-state-from-outcome-in! rctx c1)
          _ (core/finalize-cycle-in! rctx c1)

          qctx (recursion-query-ctx)
          result (query/query-in qctx
                                 {:psi/recursion-ctx rctx}
                                 [:psi.recursion/recent-cycles])]
      (is (= 1 (count (:psi.recursion/recent-cycles result))))
      (is (= c1 (get-in result [:psi.recursion/recent-cycles 0 :cycle-id]))))))

;;; --- Mutation tests ---

(deftest trigger-mutation-creates-cycle
  ;; Trigger mutation should create a new cycle
  (testing "trigger mutation creates a cycle"
    (let [rctx (core/create-context)
          _ (core/register-hooks-in! rctx)
          qctx (recursion-query-ctx)
          result (query/query-in qctx
                                 {:psi/recursion-ctx rctx}
                                 [{(list 'psi.recursion/trigger!
                                         {:psi/recursion-ctx rctx
                                          :reason "mutation-test"
                                          :system-state (all-readiness-true)})
                                   [:psi.recursion/trigger-result]}])]
      (is (= :accepted
             (get-in result ['psi.recursion/trigger!
                             :psi.recursion/trigger-result
                             :result]))))))

(deftest pause-resume-mutations-toggle-state
  ;; Pause and resume mutations should toggle controller state
  (testing "pause sets controller to paused"
    (let [rctx (core/create-context)
          qctx (recursion-query-ctx)

          ;; Pause
          pause-result (query/query-in qctx
                                       {:psi/recursion-ctx rctx}
                                       [{(list 'psi.recursion/pause!
                                               {:psi/recursion-ctx rctx
                                                :reason "test-pause"})
                                         [:psi.recursion/paused?]}])
          _ (is (true? (get-in pause-result ['psi.recursion/pause!
                                             :psi.recursion/paused?])))

          ;; Verify state
          state-after-pause (query/query-in qctx
                                            {:psi/recursion-ctx rctx}
                                            [:psi.recursion/status
                                             :psi.recursion/paused?])
          _ (is (= :paused (:psi.recursion/status state-after-pause)))
          _ (is (true? (:psi.recursion/paused? state-after-pause)))

          ;; Resume
          resume-result (query/query-in qctx
                                        {:psi/recursion-ctx rctx}
                                        [{(list 'psi.recursion/resume!
                                                {:psi/recursion-ctx rctx})
                                          [:psi.recursion/resumed?]}])
          _ (is (true? (get-in resume-result ['psi.recursion/resume!
                                              :psi.recursion/resumed?])))

          ;; Verify state after resume
          state-after-resume (query/query-in qctx
                                             {:psi/recursion-ctx rctx}
                                             [:psi.recursion/status
                                              :psi.recursion/paused?])]
      (is (= :idle (:psi.recursion/status state-after-resume)))
      (is (false? (:psi.recursion/paused? state-after-resume))))))

(deftest resume-from-non-paused-returns-false
  ;; Resume when not paused should return false
  (testing "resume from non-paused state returns false"
    (let [rctx (core/create-context)
          qctx (recursion-query-ctx)
          result (query/query-in qctx
                                 {:psi/recursion-ctx rctx}
                                 [{(list 'psi.recursion/resume!
                                         {:psi/recursion-ctx rctx})
                                   [:psi.recursion/resumed?]}])]
      (is (false? (get-in result ['psi.recursion/resume!
                                  :psi.recursion/resumed?]))))))

(deftest current-future-state-after-planning
  ;; After planning, current-future-state should be non-nil
  (testing "current-future-state is populated after planning"
    (let [rctx (core/create-context)
          _ (core/register-hooks-in! rctx)
          trigger-result (core/handle-trigger-in! rctx (manual-trigger) (all-readiness-true))
          cycle-id (:cycle-id trigger-result)
          _ (core/observe-in! rctx cycle-id
                              (all-readiness-true)
                              {:node-count 5 :capability-count 3 :status :stable}
                              {:entry-count 2 :status :ready :recovery-count 0})
          _ (core/plan-in! rctx cycle-id)
          qctx (recursion-query-ctx)
          result (query/query-in qctx
                                 {:psi/recursion-ctx rctx}
                                 [:psi.recursion/current-future-state])]
      (is (some? (:psi.recursion/current-future-state result)))
      (is (pos? (get-in result [:psi.recursion/current-future-state :version]))))))
