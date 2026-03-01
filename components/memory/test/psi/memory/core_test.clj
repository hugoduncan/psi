(ns psi.memory.core-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.engine.core :as engine]
   [psi.history.git :as git]
   [psi.memory.core :as memory]
   [psi.memory.graph-history :as graph-history]
   [psi.memory.ranking :as ranking]
   [psi.query.core :as query]))

(deftest create-context-initializes-required-state-keys
  (let [ctx   (memory/create-context)
        state (memory/get-state-in ctx)]
    (testing "status starts initializing"
      (is (= :initializing (:status state))))

    (testing "required state holders exist"
      (is (contains? state :sessions))
      (is (contains? state :records))
      (is (contains? state :graph-snapshots))
      (is (contains? state :graph-deltas))
      (is (contains? state :recoveries))
      (is (contains? state :index-stats)))

    (testing "retention defaults are scaffolded"
      (is (= graph-history/snapshot-retention-limit
             (get-in state [:retention :snapshots])))
      (is (= graph-history/delta-retention-limit
             (get-in state [:retention :deltas]))))

    (testing "ranking defaults are scaffolded"
      (is (= ranking/default-weights (:ranking-defaults state))))))

(deftest create-context-supports-overrides
  (let [ctx   (memory/create-context {:state-overrides {:status :ready}})
        state (memory/get-state-in ctx)]
    (is (= :ready (:status state)))))

(deftest swap-state-in-updates-isolated-context-only
  (let [ctx-a (memory/create-context)
        ctx-b (memory/create-context)]
    (memory/swap-state-in! ctx-a assoc :status :ready)
    (is (= :ready (:status (memory/get-state-in ctx-a))))
    (is (= :initializing (:status (memory/get-state-in ctx-b))))))

(deftest activation-success-sets-ready-status-and-readiness-flags
  (let [memory-ctx (memory/create-context)
        engine-ctx (engine/create-context)
        _          (engine/initialize-system-state-in! engine-ctx)
        query-ctx  (doto (query/create-query-context)
                     (query/rebuild-env-in!))
        git-ctx    (git/create-null-context)
        result     (memory/activate-in! memory-ctx
                                         {:engine-ctx engine-ctx
                                          :query-ctx query-ctx
                                          :git-ctx git-ctx
                                          :capability-graph-status :stable})
        state      (memory/get-state-in memory-ctx)
        sys        (engine/get-system-state-in engine-ctx)]
    (is (true? (:ready? result)))
    (is (= :ready (:status state)))
    (is (true? (:history-ready sys)))
    (is (true? (:knowledge-ready sys)))
    (is (true? (:memory-ready sys)))))

(deftest activation-failure-missing-query-env-enters-error-and-clears-memory-ready
  (let [memory-ctx (memory/create-context)
        engine-ctx (engine/create-context)
        _          (engine/initialize-system-state-in! engine-ctx)
        git-ctx    (git/create-null-context)
        result     (memory/activate-in! memory-ctx
                                         {:engine-ctx engine-ctx
                                          :query-ctx (query/create-query-context)
                                          :git-ctx git-ctx
                                          :capability-graph-status :stable})
        state      (memory/get-state-in memory-ctx)
        sys        (engine/get-system-state-in engine-ctx)]
    (is (false? (:ready? result)))
    (is (false? (:query-env-built? result)))
    (is (= :error (:status state)))
    (is (false? (:memory-ready sys)))
    (is (false? (:history-ready sys)))
    (is (false? (:knowledge-ready sys)))))

(deftest activation-failure-no-git-history-enters-error
  (let [memory-ctx (memory/create-context)
        engine-ctx (engine/create-context)
        _          (engine/initialize-system-state-in! engine-ctx)
        query-ctx  (doto (query/create-query-context)
                     (query/rebuild-env-in!))
        git-ctx    (git/create-null-context [])
        result     (memory/activate-in! memory-ctx
                                         {:engine-ctx engine-ctx
                                          :query-ctx query-ctx
                                          :git-ctx git-ctx
                                          :capability-graph-status :expanding})
        state      (memory/get-state-in memory-ctx)
        sys        (engine/get-system-state-in engine-ctx)]
    (is (false? (:ready? result)))
    (is (false? (:has-git-history? result)))
    (is (= :error (:status state)))
    (is (false? (:memory-ready sys)))))
