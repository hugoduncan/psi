(ns psi.memory.core-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.memory.core :as memory]
   [psi.memory.graph-history :as graph-history]
   [psi.memory.ranking :as ranking]))

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
