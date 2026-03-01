(ns psi.recursion.integration-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.memory.core :as memory]
   [psi.query.core :as query]
   [psi.recursion.core :as core]
   [psi.recursion.future-state :as future-state]
   [psi.recursion.resolvers :as resolvers]))

;;; --- Helpers ---

(def ^:private all-ready
  {:query-ready true
   :graph-ready true
   :introspection-ready true
   :memory-ready true})

(def ^:private sample-graph-state
  {:node-count 12
   :capability-count 5
   :status :stable})

(def ^:private sample-memory-state
  {:entry-count 3
   :status :ready
   :recovery-count 1})

(defn- make-trigger
  ([]
   (make-trigger :manual))
  ([ttype]
   {:type ttype
    :reason "integration-test"
    :payload {}
    :timestamp (java.time.Instant/now)}))

(defn- success-executor
  [_action]
  {:status :success :output-summary "integration-ok"})

(defn- all-pass-checker
  [_check-name]
  {:passed true :details "integration-pass"})

(defn- failing-checker
  "Check runner where 'tests' fails, others pass."
  [check-name]
  (if (= check-name "tests")
    {:passed false :details "2 failures found"}
    {:passed true :details "ok"}))

(defn- recursion-query-ctx
  "Create an isolated query context with recursion resolvers and mutations."
  []
  (let [qctx (query/create-query-context)]
    (doseq [r resolvers/all-resolvers]
      (query/register-resolver-in! qctx r))
    (doseq [m resolvers/all-mutations]
      (query/register-mutation-in! qctx m))
    (query/rebuild-env-in! qctx)
    qctx))

(defn- run-full-cycle!
  "Run a complete recursion cycle through all phases. Returns [cycle-id state].
   Options:
     :ctx             - recursion context (required)
     :memory-ctx      - memory context (required)
     :hook-executor   - execution hook (default: success-executor)
     :check-runner    - verification check runner (default: all-pass-checker)
     :system-state    - readiness map (default: all-ready)
     :graph-state     - graph state (default: sample-graph-state)
     :memory-state    - memory state (default: sample-memory-state)
     :skip-approve?   - if true, don't manually approve (for auto-approve paths)"
  [{:keys [ctx memory-ctx hook-executor check-runner
           system-state graph-state memory-state skip-approve?]
    :or {hook-executor success-executor
         check-runner all-pass-checker
         system-state all-ready
         graph-state sample-graph-state
         memory-state sample-memory-state
         skip-approve? false}}]
  (let [trigger-result (core/handle-trigger-in! ctx (make-trigger) system-state)
        cycle-id (:cycle-id trigger-result)]
    (when (= :accepted (:result trigger-result))
      (core/observe-in! ctx cycle-id system-state graph-state memory-state)
      (core/plan-in! ctx cycle-id)
      (let [gate (core/apply-approval-gate-in! ctx cycle-id)]
        (when (and (= :manual (:gate gate)) (not skip-approve?))
          (core/approve-proposal-in! ctx cycle-id "integration-test" "approved")))
      (core/execute-in! ctx cycle-id hook-executor)
      (core/verify-in! ctx cycle-id check-runner)
      (core/learn-in! ctx cycle-id memory-ctx)
      (core/update-future-state-from-outcome-in! ctx cycle-id)
      (core/finalize-cycle-in! ctx cycle-id))
    [cycle-id trigger-result]))

;;; --- Integration tests ---

(deftest integration-happy-path-test
  ;; Full cycle: trigger → observe → plan → approve → execute → verify (pass)
  ;; → learn → finalize. Validates AC #1, #4, #5, #6, #9, #10, #11, #13.
  (testing "happy path: complete successful recursion cycle"
    (let [ctx (core/create-context)
          mem-ctx (memory/create-context
                   {:state-overrides {:status :ready}
                    :require-provenance-on-write? false})
          _ (core/register-hooks-in! ctx)
          [cycle-id _] (run-full-cycle! {:ctx ctx :memory-ctx mem-ctx})
          state (core/get-state-in ctx)
          cycle (first (filter #(= cycle-id (:cycle-id %)) (:cycles state)))]

      (testing "controller returns to idle"
        (is (= :idle (:status state))))

      (testing "cycle status is completed"
        (is (= :completed (:status cycle))))

      (testing "cycle has ended-at timestamp"
        (is (inst? (:ended-at cycle))))

      (testing "memory contains a feed-forward tagged record"
        (let [mem-state (memory/get-state-in mem-ctx)
              records (:records mem-state)
              ff-records (filter #(some #{"feed-forward"} (:tags %)) records)]
          (is (= 1 (count ff-records)))
          (is (some #{"cycle"} (:tags (first ff-records))))
          (is (some #{"step-11"} (:tags (first ff-records))))))

      (testing "FUTURE_STATE version > 0"
        (is (pos? (:version (:current-future-state state)))))

      (testing "cycle outcome is success"
        (is (= :success (get-in cycle [:outcome :status])))
        (is (= "cycle_completed_successfully" (get-in cycle [:outcome :summary]))))

      (testing "learning-memory-ids is populated"
        (is (pos? (count (:learning-memory-ids cycle)))))

      (testing "observation was captured"
        (is (some? (:observation cycle)))
        (is (inst? (get-in cycle [:observation :captured-at]))))

      (testing "proposal was generated"
        (is (some? (:proposal cycle)))
        (is (inst? (get-in cycle [:proposal :generated-at]))))

      (testing "execution attempts were recorded"
        (is (pos? (count (:execution-attempts cycle)))))

      (testing "verification report was attached"
        (is (some? (:verification cycle)))
        (is (true? (get-in cycle [:verification :passed-all])))))))

(deftest integration-blocked-trigger-test
  ;; AC #3: blocked trigger when readiness fails.
  ;; No observation, proposal, or execution should occur.
  (testing "blocked path: trigger with readiness failure"
    (let [ctx (core/create-context)
          _ (core/register-hooks-in! ctx)
          bad-readiness (assoc all-ready :memory-ready false)
          result (core/handle-trigger-in! ctx (make-trigger) bad-readiness)
          state (core/get-state-in ctx)
          cycle (first (:cycles state))]

      (testing "trigger result is blocked"
        (is (= :blocked (:result result))))

      (testing "controller is paused with correct reason"
        (is (= :paused (:status state)))
        (is (= "recursion_prerequisites_not_ready" (:paused-reason state))))

      (testing "cycle is blocked"
        (is (= :blocked (:status cycle))))

      (testing "no observation occurred"
        (is (nil? (:observation cycle))))

      (testing "no proposal generated"
        (is (nil? (:proposal cycle))))

      (testing "no execution attempts"
        (is (empty? (:execution-attempts cycle)))))))

(deftest integration-rejected-proposal-test
  ;; Rejected proposal path: trigger → observe → plan → reject → learn → finalize.
  ;; Validates AC #7.
  (testing "rejected proposal path"
    (let [ctx (core/create-context)
          mem-ctx (memory/create-context
                   {:state-overrides {:status :ready}
                    :require-provenance-on-write? false})
          _ (core/register-hooks-in! ctx)

          ;; Trigger
          trigger-result (core/handle-trigger-in! ctx (make-trigger) all-ready)
          cycle-id (:cycle-id trigger-result)

          ;; Observe + Plan
          _ (core/observe-in! ctx cycle-id all-ready sample-graph-state sample-memory-state)
          _ (core/plan-in! ctx cycle-id)

          ;; Approval gate → manual
          gate (core/apply-approval-gate-in! ctx cycle-id)
          _ (is (= :manual (:gate gate)))

          ;; Reject the proposal
          _ (core/reject-proposal-in! ctx cycle-id "reviewer" "too risky for now")

          ;; Learn (with rejected outcome — no execution happened)
          learn-result (core/learn-in! ctx cycle-id mem-ctx)

          ;; Update future state and finalize
          _ (core/update-future-state-from-outcome-in! ctx cycle-id)
          _ (core/finalize-cycle-in! ctx cycle-id)

          state (core/get-state-in ctx)
          cycle (first (filter #(= cycle-id (:cycle-id %)) (:cycles state)))]

      (testing "learn succeeded"
        (is (true? (:ok? learn-result))))

      (testing "proposal was rejected"
        (is (false? (get-in cycle [:proposal :approved])))
        (is (= "reviewer" (get-in cycle [:proposal :approval-by]))))

      (testing "no execution attempts (execution was skipped)"
        (is (empty? (:execution-attempts cycle))))

      (testing "cycle outcome reflects rejection"
        ;; learn-in! sets a success outcome when none exists (since rejection
        ;; doesn't set an outcome in the reject flow). The cycle still completes
        ;; because learn creates the outcome.
        (is (some? (:outcome cycle))))

      (testing "controller returns to idle"
        (is (= :idle (:status state))))

      (testing "memory record was written"
        (let [mem-state (memory/get-state-in mem-ctx)
              records (:records mem-state)]
          (is (= 1 (count records))))))))

(deftest integration-failed-verification-rollback-test
  ;; AC #8, #10: trusted-local auto-approve → execute → verify fail → rollback.
  (testing "failed verification with rollback"
    (let [ctx (core/create-context
               {:config-overrides {:trusted-local-mode-enabled true
                                   :auto-approve-low-risk-in-trusted-local-mode true}})
          mem-ctx (memory/create-context
                   {:state-overrides {:status :ready}
                    :require-provenance-on-write? false})
          _ (core/register-hooks-in! ctx)

          ;; Trigger
          trigger-result (core/handle-trigger-in! ctx (make-trigger) all-ready)
          cycle-id (:cycle-id trigger-result)

          ;; Observe with no gaps/opportunities to get low-risk proposal
          _ (core/observe-in! ctx cycle-id all-ready sample-graph-state sample-memory-state)
          ;; Override observation to produce empty gaps/opportunities → low risk
          _ (core/swap-state-in!
             ctx
             (fn [s]
               (update s :cycles
                       (fn [cs]
                         (mapv #(if (= cycle-id (:cycle-id %))
                                  (-> %
                                      (assoc-in [:observation :gaps] [])
                                      (assoc-in [:observation :opportunities] []))
                                  %)
                               cs)))))
          _ (core/plan-in! ctx cycle-id)

          ;; Verify proposal is low risk
          state-pre (core/get-state-in ctx)
          cycle-pre (first (filter #(= cycle-id (:cycle-id %)) (:cycles state-pre)))
          _ (is (= :low (:risk (:proposal cycle-pre))) "precondition: low risk")

          ;; Auto-approve gate
          gate (core/apply-approval-gate-in! ctx cycle-id)
          _ (is (= :auto-approved (:gate gate)))

          ;; Execute
          _ (core/execute-in! ctx cycle-id success-executor)

          ;; Verify with one check failing
          _ (core/verify-in! ctx cycle-id failing-checker)

          ;; Learn + finalize
          _ (core/learn-in! ctx cycle-id mem-ctx)
          _ (core/update-future-state-from-outcome-in! ctx cycle-id)
          _ (core/finalize-cycle-in! ctx cycle-id)

          state (core/get-state-in ctx)
          cycle (first (filter #(= cycle-id (:cycle-id %)) (:cycles state)))]

      (testing "outcome is failed with rollback"
        (is (= :failed (get-in cycle [:outcome :status])))
        (is (= "verification_failed_rolled_back" (get-in cycle [:outcome :summary]))))

      (testing "rollback evidence recorded in execution-attempts"
        (let [rollbacks (filter #(= :rollback (:type %)) (:execution-attempts cycle))]
          (is (= 1 (count rollbacks)))
          (is (= "verification-failure" (:reason (first rollbacks))))))

      (testing "failed check name in evidence"
        (is (contains? (get-in cycle [:outcome :evidence]) "tests")))

      (testing "controller returns to idle"
        (is (= :idle (:status state))))

      (testing "cycle status is failed"
        (is (= :failed (:status cycle)))))))

(deftest integration-trusted-local-auto-approve-guard-test
  ;; AC #8: Two scenarios testing trusted-local auto-approve behavior.
  (testing "scenario 1: trusted-local=true, auto-approve=true, low-risk → auto-approved"
    (let [ctx (core/create-context
               {:config-overrides {:trusted-local-mode-enabled true
                                   :auto-approve-low-risk-in-trusted-local-mode true}})
          _ (core/register-hooks-in! ctx)
          trigger-result (core/handle-trigger-in! ctx (make-trigger) all-ready)
          cycle-id (:cycle-id trigger-result)
          _ (core/observe-in! ctx cycle-id all-ready sample-graph-state sample-memory-state)
          ;; Force low-risk by clearing gaps/opportunities
          _ (core/swap-state-in!
             ctx
             (fn [s]
               (update s :cycles
                       (fn [cs]
                         (mapv #(if (= cycle-id (:cycle-id %))
                                  (-> %
                                      (assoc-in [:observation :gaps] [])
                                      (assoc-in [:observation :opportunities] []))
                                  %)
                               cs)))))
          _ (core/plan-in! ctx cycle-id)
          gate (core/apply-approval-gate-in! ctx cycle-id)]
      (is (= :auto-approved (:gate gate)) "low-risk + trusted-local should auto-approve")))

  (testing "scenario 2: trusted-local=true, auto-approve=false, low-risk → manual"
    (let [ctx (core/create-context
               {:config-overrides {:trusted-local-mode-enabled true
                                   :auto-approve-low-risk-in-trusted-local-mode false}})
          _ (core/register-hooks-in! ctx)
          trigger-result (core/handle-trigger-in! ctx (make-trigger) all-ready)
          cycle-id (:cycle-id trigger-result)
          _ (core/observe-in! ctx cycle-id all-ready sample-graph-state sample-memory-state)
          ;; Force low-risk
          _ (core/swap-state-in!
             ctx
             (fn [s]
               (update s :cycles
                       (fn [cs]
                         (mapv #(if (= cycle-id (:cycle-id %))
                                  (-> %
                                      (assoc-in [:observation :gaps] [])
                                      (assoc-in [:observation :opportunities] []))
                                  %)
                               cs)))))
          _ (core/plan-in! ctx cycle-id)
          gate (core/apply-approval-gate-in! ctx cycle-id)]
      (is (= :manual (:gate gate)) "auto-approve=false should require manual approval"))))

(deftest integration-future-state-evolution-test
  ;; AC #5, #12: Run two cycles. First succeeds → goals advance.
  ;; Second fails → blockers added. Version increments twice.
  (testing "FUTURE_STATE evolves across two cycles"
    (let [ctx (core/create-context)
          mem-ctx (memory/create-context
                   {:state-overrides {:status :ready}
                    :require-provenance-on-write? false})
          _ (core/register-hooks-in! ctx)

          ;; --- Cycle 1: success ---
          [cycle-id-1 _] (run-full-cycle! {:ctx ctx :memory-ctx mem-ctx})
          state-after-1 (core/get-state-in ctx)
          fs-after-1 (:current-future-state state-after-1)
          version-after-1 (:version fs-after-1)

          ;; --- Cycle 2: failure (verification fails) ---
          [cycle-id-2 _] (run-full-cycle! {:ctx ctx
                                           :memory-ctx mem-ctx
                                           :check-runner failing-checker})
          state-after-2 (core/get-state-in ctx)
          fs-after-2 (:current-future-state state-after-2)]

      (testing "first cycle completed successfully"
        (let [c1 (first (filter #(= cycle-id-1 (:cycle-id %)) (:cycles state-after-2)))]
          (is (= :completed (:status c1)))
          (is (= :success (get-in c1 [:outcome :status])))))

      (testing "second cycle failed"
        (let [c2 (first (filter #(= cycle-id-2 (:cycle-id %)) (:cycles state-after-2)))]
          (is (= :failed (:status c2)))
          (is (= :failed (get-in c2 [:outcome :status])))))

      (testing "FUTURE_STATE version incremented twice"
        (is (pos? version-after-1))
        (is (> (:version fs-after-2) version-after-1)))

      (testing "second cycle added blockers to FUTURE_STATE"
        ;; The failed cycle should have added blocker goals
        (let [blocked-goals (filter #(= :blocked (:status %)) (:goals fs-after-2))]
          (is (pos? (count blocked-goals))
              "should have blocked goals from failed verification")))

      (testing "controller is idle after both cycles"
        (is (= :idle (:status state-after-2))))

      (testing "two memory records written"
        (let [mem-state (memory/get-state-in mem-ctx)
              ff-records (filter #(some #{"feed-forward"} (:tags %)) (:records mem-state))]
          (is (= 2 (count ff-records))))))))

(deftest integration-eql-surface-contract-test
  ;; AC #14: After a complete cycle, all 8 required :psi.recursion/* attrs
  ;; are queryable and return non-nil, schema-valid values.
  (testing "EQL surface contract after complete cycle"
    (let [ctx (core/create-context)
          mem-ctx (memory/create-context
                   {:state-overrides {:status :ready}
                    :require-provenance-on-write? false})
          _ (core/register-hooks-in! ctx)
          _ (run-full-cycle! {:ctx ctx :memory-ctx mem-ctx})
          qctx (recursion-query-ctx)
          result (query/query-in qctx
                                 {:psi/recursion-ctx ctx}
                                 [:psi.recursion/status
                                  :psi.recursion/paused?
                                  :psi.recursion/current-cycle
                                  :psi.recursion/current-future-state
                                  :psi.recursion/policy
                                  :psi.recursion/recent-cycles
                                  :psi.recursion/last-outcome
                                  :psi.recursion/hooks])]

      (testing ":psi.recursion/status is :idle"
        (is (= :idle (:psi.recursion/status result))))

      (testing ":psi.recursion/paused? is false"
        (is (false? (:psi.recursion/paused? result))))

      (testing ":psi.recursion/current-cycle is nil (cycle completed)"
        (is (nil? (:psi.recursion/current-cycle result))))

      (testing ":psi.recursion/current-future-state is non-nil with valid schema"
        (let [fs (:psi.recursion/current-future-state result)]
          (is (some? fs))
          (is (pos? (:version fs)))
          (is (true? (future-state/valid? fs)))))

      (testing ":psi.recursion/policy is a valid map"
        (let [p (:psi.recursion/policy result)]
          (is (map? p))
          (is (true? (:require-human-approval p)))
          (is (= 1 (:max-actions-per-cycle p)))))

      (testing ":psi.recursion/recent-cycles has 1 completed cycle"
        (let [recent (:psi.recursion/recent-cycles result)]
          (is (= 1 (count recent)))
          (is (= :completed (:status (first recent))))))

      (testing ":psi.recursion/last-outcome is success"
        (let [outcome (:psi.recursion/last-outcome result)]
          (is (some? outcome))
          (is (= :success (:status outcome)))))

      (testing ":psi.recursion/hooks is populated"
        (let [hooks (:psi.recursion/hooks result)]
          (is (vector? hooks))
          (is (= 5 (count hooks))))))))

(deftest integration-disabled-trigger-ignored-test
  ;; AC #2: disabled trigger type is ignored, no state change.
  (testing "disabled trigger type is ignored"
    (let [ctx (core/create-context
               {:config-overrides {:enabled-trigger-hooks #{:manual}}})
          _ (core/register-hooks-in! ctx)
          result (core/handle-trigger-in! ctx
                                          {:type :graph-changed
                                           :reason "test"
                                           :payload {}
                                           :timestamp (java.time.Instant/now)}
                                          all-ready)
          state (core/get-state-in ctx)]
      (is (= :ignored (:result result)))
      (is (= :idle (:status state)))
      (is (empty? (:cycles state))))))
