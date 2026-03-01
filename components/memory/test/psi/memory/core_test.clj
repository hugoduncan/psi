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

(deftest remember-in-success-persists-record-and-index-stats
  (let [memory-ctx (memory/create-context)
        before     (memory/get-state-in memory-ctx)
        result     (memory/remember-in! memory-ctx
                                        {:content-type :note
                                         :content "Persist this"
                                         :tags [:step10 :memory]
                                         :provenance {:source :session}})
        after      (memory/get-state-in memory-ctx)
        record     (:record result)]
    (is (true? (:ok? result)))
    (is (= 0 (count (:records before))))
    (is (= 1 (count (:records after))))
    (is (= :note (:content-type record)))
    (is (= "Persist this" (:content record)))
    (is (= [:step10 :memory] (:tags record)))
    (is (= 1 (get-in after [:index-stats :entry-count])))
    (is (= 1 (get-in after [:index-stats :by-type :note])))
    (is (= 1 (get-in after [:index-stats :by-source :session])))
    (is (= 1 (get-in after [:index-stats :by-tag :step10])))
    (is (= 1 (get-in after [:index-stats :by-tag :memory])))))

(deftest remember-in-missing-provenance-is-rejected-and-side-effect-free
  (let [memory-ctx (memory/create-context {:require-provenance-on-write? true})
        before     (memory/get-state-in memory-ctx)
        result     (memory/remember-in! memory-ctx
                                        {:content-type :note
                                         :content "No provenance"
                                         :tags [:invalid]})
        after      (memory/get-state-in memory-ctx)]
    (is (false? (:ok? result)))
    (is (= :missing-provenance (:error result)))
    (is (= (:records before) (:records after)))
    (is (= (:index-stats before) (:index-stats after)))))

(deftest remember-in-augments-provenance-from-capability-graph
  (let [memory-ctx (memory/create-context)
        result     (memory/remember-in! memory-ctx
                                        {:content-type :learning
                                         :content "Linked to graph"
                                         :tags [:graph]
                                         :provenance {:source :session}
                                         :capability-graph {:fingerprint "fp-123"
                                                            :capability-ids [:cap/a :cap/b]}})
        record     (:record result)]
    (is (true? (:ok? result)))
    (is (= "fp-123" (get-in record [:provenance :graphFingerprint])))
    (is (= [:cap/a :cap/b] (get-in record [:provenance :capabilityIds])))))

(deftest capture-graph-change-creates-snapshot-and-delta-on-fingerprint-change
  (let [memory-ctx (memory/create-context)
        first      (memory/capture-graph-change-in! memory-ctx
                                                    {:fingerprint "fp-1"
                                                     :nodes [1 2]
                                                     :edges [[:a :b]]
                                                     :capability-ids [:cap/a]
                                                     :operations [:op/read]})
        second     (memory/capture-graph-change-in! memory-ctx
                                                    {:fingerprint "fp-2"
                                                     :nodes [1 2 3]
                                                     :edges [[:a :b] [:b :c]]
                                                     :capability-ids [:cap/a :cap/b]
                                                     :operations [:op/read :op/write]})
        state      (memory/get-state-in memory-ctx)]
    (is (true? (:ok? first)))
    (is (true? (:changed? first)))
    (is (false? (:delta-added? first)))
    (is (true? (:ok? second)))
    (is (true? (:changed? second)))
    (is (true? (:delta-added? second)))
    (is (= 2 (count (:graph-snapshots state))))
    (is (= 1 (count (:graph-deltas state))))
    (is (= [:cap/b]
           (get-in state [:graph-deltas 0 :added-capability-ids])))))

(deftest capture-graph-change-noop-when-fingerprint-unchanged
  (let [memory-ctx (memory/create-context)
        _          (memory/capture-graph-change-in! memory-ctx
                                                    {:fingerprint "same-fp"
                                                     :capability-ids [:cap/a]})
        result     (memory/capture-graph-change-in! memory-ctx
                                                    {:fingerprint "same-fp"
                                                     :capability-ids [:cap/a :cap/b]})
        state      (memory/get-state-in memory-ctx)]
    (is (true? (:ok? result)))
    (is (false? (:changed? result)))
    (is (= :unchanged-fingerprint (:reason result)))
    (is (= 1 (count (:graph-snapshots state))))
    (is (= 0 (count (:graph-deltas state))))))

(deftest capture-graph-change-enforces-fixed-window-retention
  (let [memory-ctx (memory/create-context)]
    (doseq [n (range 1205)]
      (memory/capture-graph-change-in! memory-ctx
                                       {:fingerprint (str "fp-" n)
                                        :capability-ids [(keyword (str "cap/" n))]
                                        :operations [(keyword (str "op/" n))]}))
    (let [state (:state-atom memory-ctx)
          snapshots (:graph-snapshots @state)
          deltas (:graph-deltas @state)]
      (is (= graph-history/snapshot-retention-limit (count snapshots)))
      (is (= graph-history/delta-retention-limit (count deltas)))
      (is (= "fp-1005" (:fingerprint (first snapshots))))
      (is (= "fp-1204" (:fingerprint (last snapshots)))))))

(deftest recover-composes-required-sources-and-truncates-results
  (let [memory-ctx (memory/create-context)
        _         (memory/remember-in! memory-ctx
                                       {:content-type :note
                                        :content "session note"
                                        :tags [:recover]
                                        :provenance {:source :session
                                                     :capabilityIds [:cap/a]}
                                        :timestamp (java.time.Instant/parse "2026-02-25T00:00:00Z")})
        _         (memory/remember-in! memory-ctx
                                       {:content-type :commit
                                        :content "history note"
                                        :tags [:recover]
                                        :provenance {:source :history
                                                     :capabilityIds [:cap/b]}
                                        :timestamp (java.time.Instant/parse "2026-02-26T00:00:00Z")})
        _         (memory/capture-graph-change-in! memory-ctx
                                                   {:fingerprint "fp-recover"
                                                    :capability-ids [:cap/a :cap/c]
                                                    :operations [:op/read]}
                                                   (java.time.Instant/parse "2026-02-27T00:00:00Z"))
        result    (memory/recover-in! memory-ctx
                                      {:sources [:session :history :graph]
                                       :limit 1
                                       :now (java.time.Instant/parse "2026-02-28T00:00:00Z")})
        state     (memory/get-state-in memory-ctx)]
    (is (true? (:ok? result)))
    (is (= [:session :history :graph] (:sources result)))
    (is (= 1 (:result-count result)))
    (is (true? (:resultsTruncated result)))
    (is (= 1 (count (:search-results state))))
    (is (= 1 (count (:recoveries state))))))

(deftest recover-applies-filters-dedup-and-default-limit
  (let [memory-ctx (memory/create-context)
        ts        (java.time.Instant/parse "2026-02-28T00:00:00Z")
        _         (memory/remember-in! memory-ctx
                                       {:content-type :note
                                        :content "alpha"
                                        :tags [:t1]
                                        :provenance {:source :session
                                                     :capabilityIds [:cap/a]}
                                        :timestamp ts})
        _         (memory/remember-in! memory-ctx
                                       {:content-type :note
                                        :content "alpha"
                                        :tags [:t1]
                                        :provenance {:source :session
                                                     :capabilityIds [:cap/a]}
                                        :timestamp ts})
        _         (memory/remember-in! memory-ctx
                                       {:content-type :task
                                        :content "beta"
                                        :tags [:t2]
                                        :provenance {:source :history
                                                     :capabilityIds [:cap/b]}
                                        :timestamp ts})
        result    (memory/recover-in! memory-ctx
                                      {:sources [:session :history :graph]
                                       :tags [:t1]
                                       :content-types [:note]
                                       :capability-ids [:cap/a]
                                       :since (java.time.Instant/parse "2026-02-27T00:00:00Z")
                                       :query-text "alpha"
                                       :now (java.time.Instant/parse "2026-03-01T00:00:00Z")})]
    (is (true? (:ok? result)))
    (is (= 2 (:result-count result)))
    (is (false? (:resultsTruncated result)))
    (is (every? #(= :note (:content-type %)) (:results result)))
    (is (every? #(some #{:t1} (:tags %)) (:results result)))))

(deftest recover-rejects-missing-required-sources
  (let [memory-ctx (memory/create-context)
        result    (memory/recover-in! memory-ctx {:sources [:session :history]})]
    (is (false? (:ok? result)))
    (is (= :required-sources-missing (:error result)))))

(deftest recover-rejects-invalid-ranking-weights
  (let [memory-ctx (memory/create-context)
        result    (memory/recover-in! memory-ctx {:ranking-weights {:text-relevance 50
                                                                    :recency 25
                                                                    :capability-proximity 20}})]
    (is (false? (:ok? result)))
    (is (= :invalid-ranking-weights (:error result)))))
