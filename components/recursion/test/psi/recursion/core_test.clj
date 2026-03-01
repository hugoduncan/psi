(ns psi.recursion.core-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [malli.core :as m]
   [psi.memory.core :as memory]
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

      (testing "no paused reason/checkpoint or error"
        (is (nil? (:paused-reason state)))
        (is (nil? (:paused-checkpoint state)))
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
   (if (= :manual ttype)
     (core/manual-trigger-signal reason {:source :test})
     {:type      ttype
      :reason    reason
      :payload   {}
      :timestamp (java.time.Instant/now)})))

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

(deftest orchestrate-manual-trigger-awaits-explicit-approval-test
  (testing "orchestration stops at awaiting-approval without explicit decision"
    (let [ctx (core/create-context)
          _ (core/register-hooks-in! ctx)
          trigger (core/manual-trigger-signal "manual run" {:source :test})
          result (core/orchestrate-manual-trigger-in!
                  ctx
                  trigger
                  {:system-state all-ready
                   :graph-state {:node-count 5 :capability-count 3 :status :stable}
                   :memory-state {:entry-count 2 :status :ready :recovery-count 0}})
          state (core/get-state-in ctx)
          cycle (some->> (:cycles state) last)]
      (is (true? (:ok? result)))
      (is (= :awaiting-approval (:phase result)))
      (is (= :accepted (get-in result [:trigger-result :result])))
      (is (= :awaiting-approval (:status state)))
      (is (= :awaiting-approval (:status cycle)))
      (is (= :manual (get-in result [:gate-result :gate]))))))

(deftest orchestrate-manual-trigger-approve-completes-test
  (testing "orchestration with explicit :approve runs full cycle to finalize"
    (let [ctx (core/create-context)
          _ (core/register-hooks-in! ctx)
          mem-ctx (memory/create-context
                   {:state-overrides {:status :ready}
                    :require-provenance-on-write? false})
          trigger (core/manual-trigger-signal "manual approve" {:source :test})
          result (core/orchestrate-manual-trigger-in!
                  ctx
                  trigger
                  {:system-state all-ready
                   :graph-state {:node-count 5 :capability-count 3 :status :stable}
                   :memory-state {:entry-count 2 :status :ready :recovery-count 0}
                   :memory-ctx mem-ctx
                   :approval-decision :approve
                   :approver "reviewer"
                   :approval-notes "ship it"})
          state (core/get-state-in ctx)
          cycle (first (filter #(= (:cycle-id result) (:cycle-id %)) (:cycles state)))]
      (is (true? (:ok? result)))
      (is (= :completed (:phase result)))
      (is (= :idle (:status state)))
      (is (= :completed (:status cycle)))
      (is (= true (get-in cycle [:proposal :approved])))
      (is (= :success (get-in cycle [:outcome :status]))))))

(deftest orchestrate-manual-trigger-reject-completes-with-aborted-outcome-test
  (testing "orchestration with explicit :reject skips execution and finalizes failed"
    (let [ctx (core/create-context)
          _ (core/register-hooks-in! ctx)
          mem-ctx (memory/create-context
                   {:state-overrides {:status :ready}
                    :require-provenance-on-write? false})
          trigger (core/manual-trigger-signal "manual reject" {:source :test})
          result (core/orchestrate-manual-trigger-in!
                  ctx
                  trigger
                  {:system-state all-ready
                   :graph-state {:node-count 5 :capability-count 3 :status :stable}
                   :memory-state {:entry-count 2 :status :ready :recovery-count 0}
                   :memory-ctx mem-ctx
                   :approval-decision :reject
                   :approver "reviewer"
                   :approval-notes "not safe"})
          state (core/get-state-in ctx)
          cycle (first (filter #(= (:cycle-id result) (:cycle-id %)) (:cycles state)))]
      (is (true? (:ok? result)))
      (is (= :completed (:phase result)))
      (is (= :idle (:status state)))
      (is (= :failed (:status cycle)))
      (is (= false (get-in cycle [:proposal :approved])))
      (is (= :aborted (get-in cycle [:outcome :status])))
      (is (empty? (:execution-attempts cycle))))))

;;; --- Observation tests ---

(def ^:private sample-graph-state
  {:node-count 12
   :capability-count 5
   :status :stable})

(def ^:private sample-memory-state
  {:entry-count 3
   :status :ready
   :recovery-count 1})

(defn- trigger-and-get-cycle-id
  "Helper: fire a manual trigger and return the accepted cycle-id."
  [ctx]
  (core/register-hooks-in! ctx)
  (:cycle-id (core/handle-trigger-in! ctx (make-trigger :manual) all-ready)))

(deftest observe-in-test
  ;; Tests observation capture, signal extraction, and phase transition
  (testing "observation captures correct readiness map and signals"
    (let [ctx      (core/create-context)
          cycle-id (trigger-and-get-cycle-id ctx)
          result   (core/observe-in! ctx cycle-id all-ready sample-graph-state sample-memory-state)]
      (is (true? (:ok? result)))
      (let [obs (:observation result)]
        (is (inst? (:captured-at obs)))

        (testing "readiness map"
          (is (= {:query true :graph true :introspection true :memory true}
                 (:readiness obs))))

        (testing "graph signals extracted"
          (is (contains? (:graph-signals obs) "node-count=12"))
          (is (contains? (:graph-signals obs) "capability-count=5"))
          (is (contains? (:graph-signals obs) "status=stable")))

        (testing "memory signals extracted"
          (is (contains? (:memory-signals obs) "entry-count=3"))
          (is (contains? (:memory-signals obs) "status=ready"))
          (is (contains? (:memory-signals obs) "recovery-count=1")))

        (testing "opportunities include system ready"
          (is (some #(= "system ready for evolution" %) (:opportunities obs))))

        (testing "opportunities include stable graph"
          (is (some #(= "stable graph available" %) (:opportunities obs))))

        (testing "opportunities include memory available"
          (is (some #(= "memory entries available for learning" %) (:opportunities obs))))

        (testing "no gaps when all ready with good counts"
          (is (empty? (:gaps obs)))))))

  (testing "observation detects gaps"
    (let [ctx      (core/create-context)
          cycle-id (trigger-and-get-cycle-id ctx)
          ;; Graph with low capability count, memory with zero entries
          result   (core/observe-in! ctx cycle-id all-ready
                                     {:capability-count 1 :status :initializing}
                                     {:entry-count 0 :status :ready})]
      (is (true? (:ok? result)))
      (let [obs (:observation result)]
        (is (some #(= "low capability count" %) (:gaps obs)))
        (is (some #(= "no memory entries" %) (:gaps obs))))))

  (testing "observation transitions cycle and controller to planning"
    (let [ctx      (core/create-context)
          cycle-id (trigger-and-get-cycle-id ctx)
          _        (core/observe-in! ctx cycle-id all-ready sample-graph-state sample-memory-state)
          state    (core/get-state-in ctx)
          cycle    (first (filter #(= cycle-id (:cycle-id %)) (:cycles state)))]
      (is (= :planning (:status state)) "controller should be planning")
      (is (= :planning (:status cycle)) "cycle should be planning")
      (is (some? (:observation cycle)) "observation should be attached")))

  (testing "observe rejects wrong cycle status"
    (let [ctx      (core/create-context)
          cycle-id (trigger-and-get-cycle-id ctx)
          ;; Observe once to move to planning
          _        (core/observe-in! ctx cycle-id all-ready sample-graph-state sample-memory-state)
          ;; Try to observe again
          result   (core/observe-in! ctx cycle-id all-ready sample-graph-state sample-memory-state)]
      (is (false? (:ok? result)))
      (is (= :wrong-cycle-status (:error result)))))

  (testing "observe rejects unknown cycle-id"
    (let [ctx    (core/create-context)
          result (core/observe-in! ctx "nonexistent" all-ready sample-graph-state sample-memory-state)]
      (is (false? (:ok? result)))
      (is (= :cycle-not-found (:error result))))))

;;; --- FUTURE_STATE synthesis tests ---

(deftest synthesize-future-state-test
  ;; Tests FUTURE_STATE synthesis from observation
  (testing "version increments from nil (→1)"
    (let [obs {:captured-at (java.time.Instant/now)
               :readiness {:query true :graph true :introspection true :memory true}
               :graph-signals #{"node-count=10"}
               :memory-signals #{"entry-count=5"}
               :gaps []
               :opportunities ["system ready for evolution"]}
          fs (future-state/synthesize-future-state nil obs)]
      (is (= 1 (:version fs)))
      (is (inst? (:generated-at fs)))
      (is (true? (future-state/valid? fs)))))

  (testing "version increments from existing (→N+1)"
    (let [existing (future-state/initial-future-state)
          obs {:captured-at (java.time.Instant/now)
               :readiness {:query true :graph true :introspection true :memory true}
               :graph-signals #{}
               :memory-signals #{}
               :gaps []
               :opportunities []}
          fs (future-state/synthesize-future-state existing obs)]
      (is (= 1 (:version fs)) "0→1")))

  (testing "goals generated from gaps (high priority)"
    (let [obs {:captured-at (java.time.Instant/now)
               :readiness {:query true :graph true :introspection true :memory true}
               :graph-signals #{}
               :memory-signals #{}
               :gaps ["query not ready" "low capability count"]
               :opportunities []}
          fs (future-state/synthesize-future-state nil obs)]
      (is (= 2 (count (:goals fs))))
      (is (every? #(= :high (:priority %)) (:goals fs)))
      (is (every? #(= :proposed (:status %)) (:goals fs)))))

  (testing "goals generated from opportunities (medium priority)"
    (let [obs {:captured-at (java.time.Instant/now)
               :readiness {:query true :graph true :introspection true :memory true}
               :graph-signals #{}
               :memory-signals #{}
               :gaps []
               :opportunities ["system ready for evolution" "stable graph available"]}
          fs (future-state/synthesize-future-state nil obs)]
      (is (= 2 (count (:goals fs))))
      (is (every? #(= :medium (:priority %)) (:goals fs)))
      (is (every? #(= :proposed (:status %)) (:goals fs)))))

  (testing "mixed gaps and opportunities produce correctly prioritized goals"
    (let [obs {:captured-at (java.time.Instant/now)
               :readiness {:query true :graph true :introspection true :memory true}
               :graph-signals #{}
               :memory-signals #{}
               :gaps ["a gap"]
               :opportunities ["an opportunity"]}
          fs (future-state/synthesize-future-state nil obs)
          gap-goal (first (filter #(= :high (:priority %)) (:goals fs)))
          opp-goal (first (filter #(= :medium (:priority %)) (:goals fs)))]
      (is (= 2 (count (:goals fs))))
      (is (some? gap-goal))
      (is (some? opp-goal))))

  (testing "assumptions derived from readiness"
    (let [obs {:captured-at (java.time.Instant/now)
               :readiness {:query true :graph false :introspection true :memory true}
               :graph-signals #{}
               :memory-signals #{}
               :gaps []
               :opportunities []}
          fs (future-state/synthesize-future-state nil obs)]
      (is (contains? (:assumptions fs) "graph=false"))
      (is (contains? (:assumptions fs) "query=true"))))

  (testing "deterministic goal IDs"
    (let [obs {:captured-at (java.time.Instant/now)
               :readiness {:query true :graph true :introspection true :memory true}
               :graph-signals #{}
               :memory-signals #{}
               :gaps ["same gap"]
               :opportunities []}
          fs1 (future-state/synthesize-future-state nil obs)
          fs2 (future-state/synthesize-future-state nil obs)]
      (is (= (map :id (:goals fs1))
             (map :id (:goals fs2)))))))

;;; --- Plan proposal generation tests ---

(deftest plan-in-test
  ;; Tests plan proposal generation with policy constraints
  (testing "proposal bounded by max-actions-per-cycle=1"
    (let [ctx      (core/create-context)
          cycle-id (trigger-and-get-cycle-id ctx)
          _        (core/observe-in! ctx cycle-id all-ready
                                     sample-graph-state sample-memory-state)
          result   (core/plan-in! ctx cycle-id)]
      (is (true? (:ok? result)))
      (let [proposal (:proposal result)]
        (is (<= (count (:actions proposal)) 1)
            "should respect max-actions-per-cycle=1")
        (is (inst? (:generated-at proposal)))
        (is (true? (:requires-approval proposal))
            "default policy requires human approval")
        (is (nil? (:approved proposal)))
        (is (nil? (:approval-by proposal))))))

  (testing "proposal risk is aggregate of action risks"
    (let [ctx      (core/create-context)
          cycle-id (trigger-and-get-cycle-id ctx)
          ;; Create observation with a gap (high priority → high risk action)
          _        (core/observe-in! ctx cycle-id all-ready
                                     {:capability-count 1 :status :initializing}
                                     {:entry-count 0 :status :ready})
          result   (core/plan-in! ctx cycle-id)
          proposal (:proposal result)]
      (is (= :high (:risk proposal))
          "gap goals have high priority → high risk action")))

  (testing "proposal with no gaps has lower risk"
    (let [ctx      (core/create-context)
          cycle-id (trigger-and-get-cycle-id ctx)
          ;; All ready, good counts → only opportunities (medium priority)
          _        (core/observe-in! ctx cycle-id all-ready
                                     sample-graph-state sample-memory-state)
          result   (core/plan-in! ctx cycle-id)
          proposal (:proposal result)]
      (is (= :medium (:risk proposal))
          "opportunity goals have medium priority → medium risk")))

  (testing "atomic-only policy enforced on generated actions"
    (let [ctx      (core/create-context)
          cycle-id (trigger-and-get-cycle-id ctx)
          _        (core/observe-in! ctx cycle-id all-ready
                                     sample-graph-state sample-memory-state)
          result   (core/plan-in! ctx cycle-id)
          proposal (:proposal result)]
      (is (every? :atomic (:actions proposal))
          "all actions must be atomic when atomic-only=true")))

  (testing "FUTURE_STATE attached to controller after planning"
    (let [ctx      (core/create-context)
          cycle-id (trigger-and-get-cycle-id ctx)
          _        (core/observe-in! ctx cycle-id all-ready
                                     sample-graph-state sample-memory-state)
          _        (core/plan-in! ctx cycle-id)
          state    (core/get-state-in ctx)]
      (is (some? (:current-future-state state)))
      (is (= 1 (:version (:current-future-state state))))
      (is (true? (future-state/valid? (:current-future-state state))))))

  (testing "proposal attached to cycle"
    (let [ctx      (core/create-context)
          cycle-id (trigger-and-get-cycle-id ctx)
          _        (core/observe-in! ctx cycle-id all-ready
                                     sample-graph-state sample-memory-state)
          _        (core/plan-in! ctx cycle-id)
          state    (core/get-state-in ctx)
          cycle    (first (filter #(= cycle-id (:cycle-id %)) (:cycles state)))]
      (is (some? (:proposal cycle)))))

  (testing "plan with higher max-actions generates more actions"
    (let [ctx      (core/create-context
                    {:state-overrides {:policy (assoc (policy/default-policy)
                                                      :max-actions-per-cycle 5)}})
          cycle-id (trigger-and-get-cycle-id ctx)
          ;; Create observation with multiple gaps + opportunities
          _        (core/observe-in! ctx cycle-id all-ready
                                     {:capability-count 1 :status :initializing}
                                     {:entry-count 0 :status :ready})
          result   (core/plan-in! ctx cycle-id)
          proposal (:proposal result)]
      ;; Should have more than 1 action (gaps + opportunities)
      (is (pos? (count (:actions proposal))))
      (is (<= (count (:actions proposal)) 5))))

  (testing "plan rejects wrong cycle status"
    (let [ctx      (core/create-context)
          cycle-id (trigger-and-get-cycle-id ctx)
          ;; Cycle is in :observing, not :planning
          result   (core/plan-in! ctx cycle-id)]
      (is (false? (:ok? result)))
      (is (= :wrong-cycle-status (:error result)))))

  (testing "plan rejects if no observation"
    (let [ctx (core/create-context)
          cycle-id (trigger-and-get-cycle-id ctx)]
      ;; Manually set cycle to planning without observation
      (core/swap-state-in! ctx
                           (fn [s]
                             (update s :cycles
                                     (fn [cs]
                                       (mapv #(if (= cycle-id (:cycle-id %))
                                                (assoc % :status :planning)
                                                %)
                                             cs)))))
      (let [result (core/plan-in! ctx cycle-id)]
        (is (false? (:ok? result)))
        (is (= :no-observation (:error result))))))

  (testing "empty proposal when no goals"
    (let [ctx      (core/create-context)
          cycle-id (trigger-and-get-cycle-id ctx)
          ;; Manually attach an observation with no gaps and no opportunities
          _        (core/observe-in! ctx cycle-id all-ready
                                     sample-graph-state sample-memory-state)
          ;; Override the observation to have empty gaps and opportunities
          _        (core/swap-state-in!
                    ctx
                    (fn [s]
                      (update s :cycles
                              (fn [cs]
                                (mapv #(if (= cycle-id (:cycle-id %))
                                         (assoc-in % [:observation :gaps] [])
                                         %)
                                      (mapv #(if (= cycle-id (:cycle-id %))
                                               (assoc-in % [:observation :opportunities] [])
                                               %)
                                            cs))))))
          result   (core/plan-in! ctx cycle-id)
          proposal (:proposal result)]
      (is (= :low (:risk proposal)) "empty actions → low risk")
      (is (empty? (:actions proposal))))))

;;; --- Approval policy tests ---

(defn- setup-planned-cycle
  "Helper: create a context, trigger, observe, and plan a cycle.
   Returns [ctx cycle-id]."
  ([]
   (setup-planned-cycle {}))
  ([{:keys [config-overrides state-overrides graph-state memory-state]
     :or {config-overrides {}
          state-overrides {}
          graph-state sample-graph-state
          memory-state sample-memory-state}}]
   (let [ctx (core/create-context {:config-overrides config-overrides
                                   :state-overrides state-overrides})
         cycle-id (trigger-and-get-cycle-id ctx)
         _ (core/observe-in! ctx cycle-id all-ready graph-state memory-state)
         _ (core/plan-in! ctx cycle-id)]
     [ctx cycle-id])))

(deftest requires-manual-approval-test
  ;; Test the pure approval policy function
  (let [default-config (policy/default-config)]

    (testing "medium-risk proposal requires manual approval regardless of trusted-local"
      (is (true? (policy/requires-manual-approval?
                  {:risk :medium} default-config)))
      (is (true? (policy/requires-manual-approval?
                  {:risk :medium}
                  (assoc default-config
                         :trusted-local-mode-enabled true
                         :auto-approve-low-risk-in-trusted-local-mode true)))))

    (testing "high-risk proposal requires manual approval"
      (is (true? (policy/requires-manual-approval?
                  {:risk :high} default-config)))
      (is (true? (policy/requires-manual-approval?
                  {:risk :high}
                  (assoc default-config :trusted-local-mode-enabled true)))))

    (testing "low-risk with default config (trusted-local=false) requires manual approval"
      (is (true? (policy/requires-manual-approval?
                  {:risk :low :requires-approval true} default-config))))

    (testing "low-risk with trusted-local=true AND auto-approve=true auto-approves (AC #8)"
      (is (false? (policy/requires-manual-approval?
                   {:risk :low :requires-approval true}
                   (assoc default-config
                          :trusted-local-mode-enabled true
                          :auto-approve-low-risk-in-trusted-local-mode true)))))

    (testing "low-risk with trusted-local=true AND auto-approve=false requires manual"
      (is (true? (policy/requires-manual-approval?
                  {:risk :low :requires-approval true}
                  (assoc default-config
                         :trusted-local-mode-enabled true
                         :auto-approve-low-risk-in-trusted-local-mode false)))))

    (testing "auto-approve? is inverse of requires-manual-approval?"
      (is (true? (policy/auto-approve?
                  {:risk :low}
                  (assoc default-config
                         :trusted-local-mode-enabled true
                         :auto-approve-low-risk-in-trusted-local-mode true))))
      (is (false? (policy/auto-approve?
                   {:risk :high} default-config))))))

(deftest apply-approval-gate-manual-test
  ;; AC #7: medium/high risk enters awaiting-approval
  (testing "medium-risk proposal gets manual gate"
    (let [[ctx cycle-id] (setup-planned-cycle
                          {:graph-state {:capability-count 1 :status :initializing}
                           :memory-state {:entry-count 0 :status :ready}})
          ;; The gap-based observation produces high-risk actions
          result (core/apply-approval-gate-in! ctx cycle-id)
          state (core/get-state-in ctx)
          cycle (first (filter #(= cycle-id (:cycle-id %)) (:cycles state)))]
      (is (= :manual (:gate result)))
      (is (= :awaiting-approval (:status state)))
      (is (= :awaiting-approval (:status cycle)))))

  (testing "default config (trusted-local=false) with any risk gets manual gate"
    (let [[ctx cycle-id] (setup-planned-cycle)
          result (core/apply-approval-gate-in! ctx cycle-id)
          state (core/get-state-in ctx)]
      (is (= :manual (:gate result)))
      (is (= :awaiting-approval (:status state))))))

(deftest apply-approval-gate-auto-approve-test
  ;; AC #8: trusted-local low-risk auto-approve
  (testing "low-risk with trusted-local auto-approve gets auto-approved"
    (let [;; Create context with trusted-local mode and an observation that produces
          ;; only opportunities (medium priority → medium risk... but we need low risk)
          ;; We need to produce a low-risk proposal. Empty actions → low risk.
          ctx (core/create-context
               {:config-overrides {:trusted-local-mode-enabled true
                                   :auto-approve-low-risk-in-trusted-local-mode true}})
          cycle-id (trigger-and-get-cycle-id ctx)
          _ (core/observe-in! ctx cycle-id all-ready sample-graph-state sample-memory-state)
          ;; Override observation to have no gaps/opportunities → empty actions → low risk
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
          state-before (core/get-state-in ctx)
          cycle-before (first (filter #(= cycle-id (:cycle-id %)) (:cycles state-before)))
          _ (assert (= :low (:risk (:proposal cycle-before))) "precondition: low risk")
          result (core/apply-approval-gate-in! ctx cycle-id)
          state (core/get-state-in ctx)
          cycle (first (filter #(= cycle-id (:cycle-id %)) (:cycles state)))]
      (is (= :auto-approved (:gate result)))
      (is (= :executing (:status state)))
      (is (= :executing (:status cycle)))
      (is (true? (get-in cycle [:proposal :approved])))
      (is (false? (get-in cycle [:proposal :requires-approval]))))))

(deftest apply-approval-gate-rejects-invalid-state-test
  ;; Verification hardening for transition guard behavior around approval gate.
  (testing "apply-approval-gate rejects unknown cycle-id"
    (let [ctx (core/create-context)
          result (core/apply-approval-gate-in! ctx "nonexistent")]
      (is (false? (:ok? result)))
      (is (= :cycle-not-found (:error result)))))

  (testing "apply-approval-gate rejects wrong cycle status"
    (let [ctx (core/create-context)
          cycle-id (trigger-and-get-cycle-id ctx)
          ;; cycle is still :observing
          result (core/apply-approval-gate-in! ctx cycle-id)]
      (is (false? (:ok? result)))
      (is (= :wrong-cycle-status (:error result)))
      (is (= :observing (:status result)))))

  (testing "apply-approval-gate rejects planning cycle missing proposal"
    (let [ctx (core/create-context)
          cycle-id (trigger-and-get-cycle-id ctx)
          _ (core/observe-in! ctx cycle-id all-ready sample-graph-state sample-memory-state)
          ;; cycle now in :planning, but no proposal attached
          result (core/apply-approval-gate-in! ctx cycle-id)]
      (is (false? (:ok? result)))
      (is (= :no-proposal (:error result))))))

(deftest approve-proposal-in-test
  ;; Approve transitions to executing
  (testing "approve transitions to executing"
    (let [[ctx cycle-id] (setup-planned-cycle)
          _ (core/apply-approval-gate-in! ctx cycle-id)
          result (core/approve-proposal-in! ctx cycle-id "user@test" "looks good")
          state (core/get-state-in ctx)
          cycle (first (filter #(= cycle-id (:cycle-id %)) (:cycles state)))]
      (is (true? (:ok? result)))
      (is (= :executing (:status state)))
      (is (= :executing (:status cycle)))
      (is (true? (get-in cycle [:proposal :approved])))
      (is (= "user@test" (get-in cycle [:proposal :approval-by])))
      (is (= "looks good" (get-in cycle [:proposal :approval-notes])))))

  (testing "approve rejects wrong cycle status"
    (let [[ctx cycle-id] (setup-planned-cycle)
          ;; Cycle is in :planning, not :awaiting-approval
          result (core/approve-proposal-in! ctx cycle-id "user" "notes")]
      (is (false? (:ok? result)))
      (is (= :wrong-cycle-status (:error result))))))

(deftest reject-proposal-in-test
  ;; Reject transitions to learning
  (testing "reject transitions to learning"
    (let [[ctx cycle-id] (setup-planned-cycle)
          _ (core/apply-approval-gate-in! ctx cycle-id)
          result (core/reject-proposal-in! ctx cycle-id "user@test" "too risky")
          state (core/get-state-in ctx)
          cycle (first (filter #(= cycle-id (:cycle-id %)) (:cycles state)))]
      (is (true? (:ok? result)))
      (is (= :learning (:status state)))
      (is (= :learning (:status cycle)))
      (is (false? (get-in cycle [:proposal :approved])))
      (is (= "user@test" (get-in cycle [:proposal :approval-by])))
      (is (= "too risky" (get-in cycle [:proposal :approval-notes])))
      (is (= :aborted (get-in cycle [:outcome :status])))
      (is (= "proposal_rejected" (get-in cycle [:outcome :summary])))
      (is (= #{"too risky"} (get-in cycle [:outcome :evidence])))))

  (testing "reject sets default rejection evidence when notes are blank"
    (let [[ctx cycle-id] (setup-planned-cycle)
          _ (core/apply-approval-gate-in! ctx cycle-id)
          _ (core/reject-proposal-in! ctx cycle-id "user@test" "")
          state (core/get-state-in ctx)
          cycle (first (filter #(= cycle-id (:cycle-id %)) (:cycles state)))]
      (is (= #{"proposal_rejected"} (get-in cycle [:outcome :evidence])))))

  (testing "reject rejects wrong cycle status"
    (let [[ctx cycle-id] (setup-planned-cycle)
          result (core/reject-proposal-in! ctx cycle-id "user" "notes")]
      (is (false? (:ok? result)))
      (is (= :wrong-cycle-status (:error result))))))

;;; --- Execution tests ---

(deftest execute-in-test
  ;; Execute creates attempt records and transitions to verifying
  (testing "execute creates attempt records and transitions to verifying"
    (let [[ctx cycle-id] (setup-planned-cycle)
          _ (core/apply-approval-gate-in! ctx cycle-id)
          _ (core/approve-proposal-in! ctx cycle-id "user" "approved")
          executor (fn [action]
                     {:status :success
                      :output-summary (str "executed " (:id action))})
          result (core/execute-in! ctx cycle-id executor)
          state (core/get-state-in ctx)
          cycle (first (filter #(= cycle-id (:cycle-id %)) (:cycles state)))]
      (is (true? (:ok? result)))
      (is (vector? (:attempts result)))
      (is (= :verifying (:status state)))
      (is (= :verifying (:status cycle)))
      ;; Check attempt records
      (let [attempts (:execution-attempts cycle)]
        (is (pos? (count attempts)))
        (doseq [attempt attempts]
          (is (string? (:action-id attempt)))
          (is (inst? (:started-at attempt)))
          (is (inst? (:ended-at attempt)))
          (is (= :success (:status attempt)))
          (is (string? (:output-summary attempt)))))))

  (testing "execute with default hook-executor"
    (let [[ctx cycle-id] (setup-planned-cycle)
          _ (core/apply-approval-gate-in! ctx cycle-id)
          _ (core/approve-proposal-in! ctx cycle-id "user" "ok")
          result (core/execute-in! ctx cycle-id)
          state (core/get-state-in ctx)]
      (is (true? (:ok? result)))
      (is (= :verifying (:status state)))
      (doseq [attempt (:attempts result)]
        (is (= :success (:status attempt)))
        (is (= "hook-not-implemented" (:output-summary attempt))))))

  (testing "execute with failed hook"
    (let [[ctx cycle-id] (setup-planned-cycle)
          _ (core/apply-approval-gate-in! ctx cycle-id)
          _ (core/approve-proposal-in! ctx cycle-id "user" "ok")
          executor (fn [_action]
                     {:status :failed
                      :output-summary "hook error"})
          result (core/execute-in! ctx cycle-id executor)]
      (is (true? (:ok? result)))
      (doseq [attempt (:attempts result)]
        (is (= :failed (:status attempt))))))

  (testing "execute rejects if proposal not approved"
    (let [[ctx cycle-id] (setup-planned-cycle)
          ;; Manually set cycle to executing without approval
          _ (core/swap-state-in!
             ctx
             (fn [s]
               (-> s
                   (assoc :status :executing)
                   (update :cycles
                           (fn [cs]
                             (mapv #(if (= cycle-id (:cycle-id %))
                                      (assoc % :status :executing)
                                      %)
                                   cs))))))
          result (core/execute-in! ctx cycle-id)]
      (is (false? (:ok? result)))
      (is (= :proposal-not-approved (:error result)))))

  (testing "execute rejects wrong cycle status"
    (let [[ctx cycle-id] (setup-planned-cycle)
          ;; Cycle is in :planning
          result (core/execute-in! ctx cycle-id)]
      (is (false? (:ok? result)))
      (is (= :wrong-cycle-status (:error result))))))

;;; --- Verification and rollback tests ---

(defn- setup-executed-cycle
  "Helper: create a context, trigger, observe, plan, approve, and execute a cycle.
   Returns [ctx cycle-id]. Optionally accepts config-overrides and hook-executor."
  ([]
   (setup-executed-cycle {}))
  ([{:keys [config-overrides state-overrides hook-executor]
     :or {config-overrides {}
          state-overrides {}
          hook-executor (fn [_action] {:status :success :output-summary "test-ok"})}}]
   (let [ctx (core/create-context {:config-overrides config-overrides
                                   :state-overrides state-overrides})
         cycle-id (trigger-and-get-cycle-id ctx)
         _ (core/observe-in! ctx cycle-id all-ready sample-graph-state sample-memory-state)
         _ (core/plan-in! ctx cycle-id)
         _ (core/apply-approval-gate-in! ctx cycle-id)
         _ (core/approve-proposal-in! ctx cycle-id "user" "approved")
         _ (core/execute-in! ctx cycle-id hook-executor)]
     [ctx cycle-id])))

(deftest verify-in-all-pass-test
  ;; AC #10: All checks pass → verification report has passed-all=true,
  ;; cycle transitions to learning, no rollback
  (testing "all checks pass → learning, no rollback, no outcome set"
    (let [[ctx cycle-id] (setup-executed-cycle)
          check-runner (fn [_check-name]
                         {:passed true :details "all good"})
          result (core/verify-in! ctx cycle-id check-runner)
          state (core/get-state-in ctx)
          cycle (first (filter #(= cycle-id (:cycle-id %)) (:cycles state)))]
      (is (true? (:ok? result)))

      (testing "report structure"
        (let [report (:report result)]
          (is (true? (:passed-all report)))
          (is (inst? (:completed-at report)))
          (is (= 3 (count (:checks report))) "should have 3 required checks")
          (is (every? :passed (:checks report)))))

      (testing "cycle transitions to learning"
        (is (= :learning (:status state)))
        (is (= :learning (:status cycle))))

      (testing "verification report attached to cycle"
        (is (some? (:verification cycle)))
        (is (true? (get-in cycle [:verification :passed-all]))))

      (testing "no outcome set yet (success outcome set by learn phase)"
        (is (nil? (:outcome cycle))))

      (testing "no rollback evidence"
        (is (not-any? #(= :rollback (:type %)) (:execution-attempts cycle)))))))

(deftest verify-in-fail-with-rollback-test
  ;; AC #10: One check fails with rollback enabled → outcome is failed with
  ;; "verification_failed_rolled_back", rollback evidence recorded
  (testing "check fails with rollback-on-verification-failure=true"
    (let [[ctx cycle-id] (setup-executed-cycle)
          check-runner (fn [check-name]
                         (if (= check-name "tests")
                           {:passed false :details "2 failures"}
                           {:passed true :details "ok"}))
          result (core/verify-in! ctx cycle-id check-runner)
          state (core/get-state-in ctx)
          cycle (first (filter #(= cycle-id (:cycle-id %)) (:cycles state)))]
      (is (true? (:ok? result)))

      (testing "report shows not all passed"
        (is (false? (get-in result [:report :passed-all]))))

      (testing "cycle transitions to learning"
        (is (= :learning (:status state)))
        (is (= :learning (:status cycle))))

      (testing "outcome is failed with rollback summary"
        (let [outcome (:outcome cycle)]
          (is (= :failed (:status outcome)))
          (is (= "verification_failed_rolled_back" (:summary outcome)))
          (is (contains? (:evidence outcome) "tests"))
          (is (= #{} (:changed-goals outcome)))))

      (testing "rollback evidence recorded in execution-attempts"
        (let [rollback-records (filter #(= :rollback (:type %))
                                       (:execution-attempts cycle))]
          (is (= 1 (count rollback-records)))
          (let [rb (first rollback-records)]
            (is (= cycle-id (:cycle-id rb)))
            (is (inst? (:timestamp rb)))
            (is (= "verification-failure" (:reason rb))))))

      (testing "verification report attached to cycle"
        (is (some? (:verification cycle)))
        (is (= 3 (count (get-in cycle [:verification :checks]))))))))

(deftest verify-in-fail-without-rollback-test
  ;; Check fails with rollback disabled → outcome is failed, no rollback
  (testing "check fails with rollback-on-verification-failure=false"
    (let [[ctx cycle-id] (setup-executed-cycle
                          {:state-overrides
                           {:policy (assoc (policy/default-policy)
                                           :rollback-on-verification-failure false)}})
          check-runner (fn [check-name]
                         (if (= check-name "lint")
                           {:passed false :details "lint errors"}
                           {:passed true :details "ok"}))
          result (core/verify-in! ctx cycle-id check-runner)
          state (core/get-state-in ctx)
          cycle (first (filter #(= cycle-id (:cycle-id %)) (:cycles state)))]
      (is (true? (:ok? result)))

      (testing "cycle transitions to learning"
        (is (= :learning (:status state)))
        (is (= :learning (:status cycle))))

      (testing "outcome is failed without rollback"
        (let [outcome (:outcome cycle)]
          (is (= :failed (:status outcome)))
          (is (= "verification_failed" (:summary outcome)))
          (is (contains? (:evidence outcome) "lint"))))

      (testing "no rollback evidence"
        (is (not-any? #(= :rollback (:type %)) (:execution-attempts cycle)))))))

(deftest verify-in-report-contains-all-checks-test
  ;; Verification report contains all required check names
  (testing "report contains all required check names from config"
    (let [[ctx cycle-id] (setup-executed-cycle)
          check-runner (fn [check-name]
                         {:passed true :details (str check-name " ok")})
          result (core/verify-in! ctx cycle-id check-runner)
          check-names (set (map :name (get-in result [:report :checks])))]
      (is (= #{"tests" "lint" "eql-health"} check-names)))))

(deftest verify-in-multiple-failures-test
  ;; Multiple checks fail — all failed names in evidence
  (testing "multiple check failures captured in evidence"
    (let [[ctx cycle-id] (setup-executed-cycle)
          check-runner (fn [check-name]
                         (if (= check-name "lint")
                           {:passed true :details "ok"}
                           {:passed false :details "failed"}))
          _ (core/verify-in! ctx cycle-id check-runner)
          state (core/get-state-in ctx)
          cycle (first (filter #(= cycle-id (:cycle-id %)) (:cycles state)))
          outcome (:outcome cycle)]
      (is (= :failed (:status outcome)))
      (is (contains? (:evidence outcome) "tests"))
      (is (contains? (:evidence outcome) "eql-health"))
      (is (not (contains? (:evidence outcome) "lint"))))))

(deftest verify-in-default-check-runner-test
  ;; Default check-runner passes all checks
  (testing "default check-runner passes all checks"
    (let [[ctx cycle-id] (setup-executed-cycle)
          result (core/verify-in! ctx cycle-id)
          state (core/get-state-in ctx)]
      (is (true? (:ok? result)))
      (is (true? (get-in result [:report :passed-all])))
      (is (= :learning (:status state))))))

(deftest verify-in-rejects-wrong-status-test
  ;; Verify rejects if cycle is not in :verifying status
  (testing "verify rejects wrong cycle status"
    (let [[ctx cycle-id] (setup-planned-cycle)
          ;; Cycle is in :planning, not :verifying
          result (core/verify-in! ctx cycle-id)]
      (is (false? (:ok? result)))
      (is (= :wrong-cycle-status (:error result)))))

  (testing "verify rejects unknown cycle-id"
    (let [ctx (core/create-context)
          result (core/verify-in! ctx "nonexistent")]
      (is (false? (:ok? result)))
      (is (= :cycle-not-found (:error result))))))

;;; --- Learn phase + memory writeback tests ---

(defn- setup-verified-cycle
  "Helper: create a context, trigger, observe, plan, approve, execute, and verify
   a cycle (all checks pass). Returns [ctx cycle-id memory-ctx].
   Optionally accepts config-overrides and state-overrides."
  ([]
   (setup-verified-cycle {}))
  ([{:keys [config-overrides state-overrides check-runner]
     :or {config-overrides {}
          state-overrides {}
          check-runner (fn [_] {:passed true :details "ok"})}}]
   (let [[ctx cycle-id] (setup-executed-cycle {:config-overrides config-overrides
                                               :state-overrides state-overrides})
         _ (core/verify-in! ctx cycle-id check-runner)
         memory-ctx (memory/create-context
                     {:state-overrides {:status :ready}
                      :require-provenance-on-write? false})]
     [ctx cycle-id memory-ctx])))

(defn- setup-verified-failed-cycle
  "Helper: create a cycle that fails verification. Returns [ctx cycle-id memory-ctx]."
  ([]
   (setup-verified-failed-cycle {}))
  ([{:keys [config-overrides state-overrides]
     :or {config-overrides {} state-overrides {}}}]
   (let [[ctx cycle-id] (setup-executed-cycle {:config-overrides config-overrides
                                               :state-overrides state-overrides})
         check-runner (fn [check-name]
                        (if (= check-name "tests")
                          {:passed false :details "1 failure"}
                          {:passed true :details "ok"}))
         _ (core/verify-in! ctx cycle-id check-runner)
         memory-ctx (memory/create-context
                     {:state-overrides {:status :ready}
                      :require-provenance-on-write? false})]
     [ctx cycle-id memory-ctx])))

(deftest learn-in-success-path-test
  ;; AC #11: Learn writes memory record with correct tags and provenance
  (testing "learn writes memory record on success path"
    (let [[ctx cycle-id memory-ctx] (setup-verified-cycle)
          result (core/learn-in! ctx cycle-id memory-ctx)]
      (is (true? (:ok? result)))
      (is (set? (:memory-ids result)))
      (is (= 1 (count (:memory-ids result))))

      (testing "memory record has correct tags"
        (let [mem-state (memory/get-state-in memory-ctx)
              record (first (:records mem-state))]
          (is (some? record))
          (is (= :discovery (:content-type record)))
          (is (some #{"feed-forward"} (:tags record)))
          (is (some #{"cycle"} (:tags record)))
          (is (some #{"step-11"} (:tags record)))))

      (testing "memory record has correct provenance"
        (let [mem-state (memory/get-state-in memory-ctx)
              record (first (:records mem-state))
              prov (:provenance record)]
          (is (= :feed-forward (:source prov)))
          (is (= cycle-id (:cycle-id prov)))
          (is (= :manual (:trigger-type prov)))
          (is (= :success (:outcome-status prov)))))

      (testing "memory content mentions cycle-id and outcome"
        (let [mem-state (memory/get-state-in memory-ctx)
              record (first (:records mem-state))]
          (is (str/includes? (:content record) cycle-id))
          (is (str/includes? (:content record) "success")))))))

(deftest learn-in-stores-memory-id-test
  ;; AC #11: Learn stores memory record-id in cycle's learning-memory-ids
  (testing "learn stores memory record-id in cycle"
    (let [[ctx cycle-id memory-ctx] (setup-verified-cycle)
          result (core/learn-in! ctx cycle-id memory-ctx)
          state (core/get-state-in ctx)
          cycle (first (filter #(= cycle-id (:cycle-id %)) (:cycles state)))]
      (is (= (:memory-ids result) (:learning-memory-ids cycle)))
      (is (= 1 (count (:learning-memory-ids cycle)))))))

(deftest learn-in-sets-success-outcome-test
  ;; When cycle has no outcome (all checks passed), learn sets success outcome
  (testing "learn sets success outcome when none exists"
    (let [[ctx cycle-id memory-ctx] (setup-verified-cycle)
          ;; Before learn, cycle should have no outcome (all checks passed)
          state-before (core/get-state-in ctx)
          cycle-before (first (filter #(= cycle-id (:cycle-id %)) (:cycles state-before)))
          _ (is (nil? (:outcome cycle-before)) "precondition: no outcome before learn")
          _ (core/learn-in! ctx cycle-id memory-ctx)
          state (core/get-state-in ctx)
          cycle (first (filter #(= cycle-id (:cycle-id %)) (:cycles state)))]
      (is (= :success (get-in cycle [:outcome :status])))
      (is (= "cycle_completed_successfully" (get-in cycle [:outcome :summary])))
      (is (set? (get-in cycle [:outcome :evidence])))
      (is (set? (get-in cycle [:outcome :changed-goals]))))))

(deftest learn-in-failed-outcome-test
  ;; When cycle has a failed outcome (verification failed), learn preserves it
  (testing "learn preserves existing failed outcome"
    (let [[ctx cycle-id memory-ctx] (setup-verified-failed-cycle)
          ;; Before learn, cycle should have failed outcome from verify
          state-before (core/get-state-in ctx)
          cycle-before (first (filter #(= cycle-id (:cycle-id %)) (:cycles state-before)))
          _ (is (= :failed (get-in cycle-before [:outcome :status])) "precondition: failed outcome")
          result (core/learn-in! ctx cycle-id memory-ctx)]
      (is (true? (:ok? result)))

      (testing "memory record reflects failed outcome"
        (let [mem-state (memory/get-state-in memory-ctx)
              record (first (:records mem-state))
              prov (:provenance record)]
          (is (= :failed (:outcome-status prov))))))))

(deftest learn-in-preserves-aborted-outcome-test
  (testing "learn preserves rejected/aborted outcome"
    (let [[ctx cycle-id] (setup-planned-cycle)
          memory-ctx (memory/create-context
                      {:state-overrides {:status :ready}
                       :require-provenance-on-write? false})
          _ (core/apply-approval-gate-in! ctx cycle-id)
          _ (core/reject-proposal-in! ctx cycle-id "reviewer" "too risky")
          result (core/learn-in! ctx cycle-id memory-ctx)
          state (core/get-state-in ctx)
          cycle (first (filter #(= cycle-id (:cycle-id %)) (:cycles state)))
          record (first (:records (memory/get-state-in memory-ctx)))]
      (is (true? (:ok? result)))
      (is (= :aborted (get-in cycle [:outcome :status])))
      (is (= :aborted (get-in record [:provenance :outcome-status]))))))

(deftest learn-in-rejects-wrong-status-test
  (testing "learn rejects wrong cycle status"
    (let [[ctx cycle-id] (setup-planned-cycle)
          memory-ctx (memory/create-context {:require-provenance-on-write? false})
          result (core/learn-in! ctx cycle-id memory-ctx)]
      (is (false? (:ok? result)))
      (is (= :wrong-cycle-status (:error result)))))

  (testing "learn rejects unknown cycle-id"
    (let [ctx (core/create-context)
          memory-ctx (memory/create-context {:require-provenance-on-write? false})
          result (core/learn-in! ctx "nonexistent" memory-ctx)]
      (is (false? (:ok? result)))
      (is (= :cycle-not-found (:error result))))))

;;; --- FUTURE_STATE update from outcome tests ---

(deftest update-future-state-success-test
  ;; AC #12: Success outcome advances goals in FUTURE_STATE
  (testing "success outcome advances goals"
    (let [[ctx cycle-id memory-ctx] (setup-verified-cycle)
          _ (core/learn-in! ctx cycle-id memory-ctx)
          ;; Get the current future state version before update
          state-before (core/get-state-in ctx)
          fs-version-before (:version (:current-future-state state-before))
          result (core/update-future-state-from-outcome-in! ctx cycle-id)
          state (core/get-state-in ctx)
          fs (:current-future-state state)]
      (is (true? (:ok? result)))
      (is (some? (:future-state result)))
      (is (> (:version fs) fs-version-before) "version should increment"))))

(deftest update-future-state-failed-test
  ;; AC #12: Failed outcome adds blockers to FUTURE_STATE
  (testing "failed outcome adds blockers"
    (let [[ctx cycle-id memory-ctx] (setup-verified-failed-cycle)
          _ (core/learn-in! ctx cycle-id memory-ctx)
          state-before (core/get-state-in ctx)
          fs-version-before (:version (:current-future-state state-before))
          goal-count-before (count (:goals (:current-future-state state-before)))
          result (core/update-future-state-from-outcome-in! ctx cycle-id)
          state (core/get-state-in ctx)
          fs (:current-future-state state)]
      (is (true? (:ok? result)))
      (is (> (:version fs) fs-version-before) "version should increment")
      (is (> (count (:goals fs)) goal-count-before) "should have new blocker goals")
      ;; The new goals should be :blocked status
      (let [new-goals (drop goal-count-before (:goals fs))]
        (is (every? #(= :blocked (:status %)) new-goals))))))

(deftest update-future-state-no-outcome-test
  (testing "update rejects when no outcome"
    (let [[ctx cycle-id] (setup-executed-cycle)
          ;; Manually set cycle to learning without outcome
          _ (core/swap-state-in!
             ctx
             (fn [s]
               (-> s
                   (assoc :status :learning)
                   (update :cycles
                           (fn [cs]
                             (mapv #(if (= cycle-id (:cycle-id %))
                                      (assoc % :status :learning)
                                      %)
                                   cs))))))
          result (core/update-future-state-from-outcome-in! ctx cycle-id)]
      (is (false? (:ok? result)))
      (is (= :no-outcome (:error result)))))

  (testing "update rejects unknown cycle-id"
    (let [ctx (core/create-context)
          result (core/update-future-state-from-outcome-in! ctx "nonexistent")]
      (is (false? (:ok? result)))
      (is (= :cycle-not-found (:error result))))))

;;; --- Cycle finalization tests ---

(deftest finalize-cycle-success-test
  ;; AC #13: Finalize sets ended-at, correct terminal status, returns controller to idle
  (testing "finalize successful cycle"
    (let [[ctx cycle-id memory-ctx] (setup-verified-cycle)
          _ (core/learn-in! ctx cycle-id memory-ctx)
          _ (core/update-future-state-from-outcome-in! ctx cycle-id)
          result (core/finalize-cycle-in! ctx cycle-id)
          state (core/get-state-in ctx)
          cycle (first (filter #(= cycle-id (:cycle-id %)) (:cycles state)))]
      (is (true? (:ok? result)))
      (is (= :completed (:final-status result)))

      (testing "cycle has ended-at timestamp"
        (is (inst? (:ended-at cycle))))

      (testing "cycle status is completed"
        (is (= :completed (:status cycle))))

      (testing "controller returns to idle"
        (is (= :idle (:status state))))

      (testing "paused pause metadata is cleared"
        (is (nil? (:paused-reason state)))
        (is (nil? (:paused-checkpoint state)))))))

(deftest finalize-cycle-failed-test
  ;; AC #13: Failed cycle finalization
  (testing "finalize failed cycle"
    (let [[ctx cycle-id memory-ctx] (setup-verified-failed-cycle)
          _ (core/learn-in! ctx cycle-id memory-ctx)
          _ (core/update-future-state-from-outcome-in! ctx cycle-id)
          result (core/finalize-cycle-in! ctx cycle-id)
          state (core/get-state-in ctx)
          cycle (first (filter #(= cycle-id (:cycle-id %)) (:cycles state)))]
      (is (true? (:ok? result)))
      (is (= :failed (:final-status result)))

      (testing "cycle status is failed"
        (is (= :failed (:status cycle))))

      (testing "controller returns to idle"
        (is (= :idle (:status state))))

      (testing "ended-at is set"
        (is (inst? (:ended-at cycle)))))))

(deftest finalize-clears-paused-reason-test
  ;; AC #13: Finalize clears pause metadata
  (testing "finalize clears paused-reason and paused-checkpoint"
    (let [[ctx cycle-id memory-ctx] (setup-verified-cycle)
          _ (core/learn-in! ctx cycle-id memory-ctx)
          _ (core/update-future-state-from-outcome-in! ctx cycle-id)
          ;; Manually set pause metadata
          _ (core/swap-state-in! ctx assoc
                                 :paused-reason "some-reason"
                                 :paused-checkpoint {:status :planning
                                                     :at (java.time.Instant/now)})
          _ (core/finalize-cycle-in! ctx cycle-id)
          state (core/get-state-in ctx)]
      (is (nil? (:paused-reason state)))
      (is (nil? (:paused-checkpoint state))))))

(deftest finalize-rejects-no-outcome-test
  (testing "finalize rejects when no outcome"
    (let [[ctx cycle-id] (setup-executed-cycle)
          ;; Manually set cycle to learning without outcome
          _ (core/swap-state-in!
             ctx
             (fn [s]
               (-> s
                   (assoc :status :learning)
                   (update :cycles
                           (fn [cs]
                             (mapv #(if (= cycle-id (:cycle-id %))
                                      (assoc % :status :learning)
                                      %)
                                   cs))))))
          result (core/finalize-cycle-in! ctx cycle-id)]
      (is (false? (:ok? result)))
      (is (= :no-outcome (:error result)))))

  (testing "finalize rejects unknown cycle-id"
    (let [ctx (core/create-context)
          result (core/finalize-cycle-in! ctx "nonexistent")]
      (is (false? (:ok? result)))
      (is (= :cycle-not-found (:error result))))))
