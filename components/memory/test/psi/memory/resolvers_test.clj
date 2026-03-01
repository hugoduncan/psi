(ns psi.memory.resolvers-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.memory.core :as memory]
   [psi.memory.resolvers :as resolvers]
   [psi.query.core :as query]))

(defn- memory-query-ctx
  []
  (let [qctx (query/create-query-context)]
    (doseq [r resolvers/all-resolvers]
      (query/register-resolver-in! qctx r))
    (query/rebuild-env-in! qctx)
    qctx))

(deftest memory-attrs-are-queryable-from-scaffold-context
  (let [memory-ctx (memory/create-context)
        qctx       (memory-query-ctx)
        result     (query/query-in qctx
                                   {:psi/memory-ctx memory-ctx}
                                   [:psi.memory/status
                                    :psi.memory/entry-count
                                    :psi.memory/entries
                                    :psi.memory/search-results
                                    :psi.memory/recovery-count
                                    :psi.memory/recoveries
                                    :psi.memory/graph-snapshots
                                    :psi.memory/graph-deltas
                                    :psi.memory/by-tag
                                    :psi.memory/capability-history
                                    :psi.memory/index-stats])]
    (testing "status and counters are present"
      (is (= :initializing (:psi.memory/status result)))
      (is (= 0 (:psi.memory/entry-count result)))
      (is (= 0 (:psi.memory/recovery-count result))))

    (testing "structured values are returned"
      (is (vector? (:psi.memory/entries result)))
      (is (vector? (:psi.memory/search-results result)))
      (is (vector? (:psi.memory/recoveries result)))
      (is (vector? (:psi.memory/graph-snapshots result)))
      (is (vector? (:psi.memory/graph-deltas result)))
      (is (vector? (:psi.memory/capability-history result)))
      (is (map? (:psi.memory/by-tag result)))
      (is (map? (:psi.memory/index-stats result))))))
