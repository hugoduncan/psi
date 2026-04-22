(ns psi.recursion.core-learning-finalization-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.memory.core :as memory]
   [psi.recursion.core :as core]))

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
  ([ttype]
   (make-trigger ttype "test trigger"))
  ([ttype reason]
   (if (= :manual ttype)
     (core/manual-trigger-signal reason {:source :test})
     {:type      ttype
      :reason    reason
      :payload   {}
      :timestamp (java.time.Instant/now)})))

(defn- trigger-and-get-cycle-id
  [ctx]
  (core/register-hooks-in! ctx)
  (:cycle-id (core/handle-trigger-in! ctx (make-trigger :manual) all-ready)))

(defn setup-planned-cycle
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

(defn- setup-executed-cycle
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

(defn setup-verified-cycle
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

(defn setup-verified-failed-cycle
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

(deftest update-future-state-success-test
  (testing "success outcome advances goals"
    (let [[ctx cycle-id memory-ctx] (setup-verified-cycle)
          _ (core/learn-in! ctx cycle-id memory-ctx)
          state-before (core/get-state-in ctx)
          fs-version-before (:version (:current-future-state state-before))
          result (core/update-future-state-from-outcome-in! ctx cycle-id)
          state (core/get-state-in ctx)
          fs (:current-future-state state)]
      (is (true? (:ok? result)))
      (is (some? (:future-state result)))
      (is (> (:version fs) fs-version-before) "version should increment"))))

(deftest update-future-state-failed-test
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
      (let [new-goals (drop goal-count-before (:goals fs))]
        (is (every? #(= :blocked (:status %)) new-goals))))))

(deftest update-future-state-no-outcome-test
  (testing "update rejects when no outcome"
    (let [[ctx cycle-id] (setup-executed-cycle)
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

(deftest finalize-cycle-success-test
  (testing "finalize successful cycle"
    (let [[ctx cycle-id memory-ctx] (setup-verified-cycle)
          _ (core/learn-in! ctx cycle-id memory-ctx)
          _ (core/update-future-state-from-outcome-in! ctx cycle-id)
          result (core/finalize-cycle-in! ctx cycle-id)
          state (core/get-state-in ctx)
          cycle (first (filter #(= cycle-id (:cycle-id %)) (:cycles state)))]
      (is (true? (:ok? result)))
      (is (= :completed (:final-status result)))
      (is (inst? (:ended-at cycle)))
      (is (= :completed (:status cycle)))
      (is (= :idle (:status state)))
      (is (nil? (:paused-reason state)))
      (is (nil? (:paused-checkpoint state))))))

(deftest finalize-cycle-failed-test
  (testing "finalize failed cycle"
    (let [[ctx cycle-id memory-ctx] (setup-verified-failed-cycle)
          _ (core/learn-in! ctx cycle-id memory-ctx)
          _ (core/update-future-state-from-outcome-in! ctx cycle-id)
          result (core/finalize-cycle-in! ctx cycle-id)
          state (core/get-state-in ctx)
          cycle (first (filter #(= cycle-id (:cycle-id %)) (:cycles state)))]
      (is (true? (:ok? result)))
      (is (= :failed (:final-status result)))
      (is (= :failed (:status cycle)))
      (is (= :idle (:status state)))
      (is (inst? (:ended-at cycle))))))

(deftest finalize-clears-paused-reason-test
  (testing "finalize clears paused-reason and paused-checkpoint"
    (let [[ctx cycle-id memory-ctx] (setup-verified-cycle)
          _ (core/learn-in! ctx cycle-id memory-ctx)
          _ (core/update-future-state-from-outcome-in! ctx cycle-id)
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
