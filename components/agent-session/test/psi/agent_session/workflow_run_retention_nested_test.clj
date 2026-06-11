(ns psi.agent-session.workflow-run-retention-nested-test
  "Retention behaviour for nested :delegate-step sub-runs.

   A single user delegation of a multi-step workflow with a :delegate step
   produces a top-level run plus nested sub-run(s) that share the same
   originating :parent-session-id. Nested sub-runs (tagged with
   :delegating-run-id) must not count against the originating session's
   per-session retention budget, and must be removed transitively with their
   top-level run."
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.core :as session]
   [psi.agent-session.test-support :as test-support]
   [psi.agent-session.workflow-run-retention :as workflow-run-retention]
   [psi.session-state.state :as ss]
   [psi.workflow-runtime.cancellation-entry :as cancellation-entry]
   [psi.workflow-runtime.model :as workflow-model]))

(defn- make-test-ctx
  "Create a minimal ctx with a state atom seeded with empty workflow state."
  []
  (let [ctx (session/create-context
             (test-support/safe-context-opts {:persist? false}))]
    (swap! (:state* ctx) merge {:workflows (workflow-model/initial-workflow-state)})
    ctx))

(deftest workflow-run-retention-nested-delegation-test
  (testing "nested :delegate-step sub-runs do not count against the originating session's retention budget"
    ;; The nested sub-run (tagged with :delegating-run-id) must NOT count toward
    ;; the per-session retention count, so the user's just-delegated top-level
    ;; run and its sessions are retained, and the nested sub-run + its sessions
    ;; are retained as part of that delegation rather than evicted as if they
    ;; were a second delegation.
    (let [ctx (make-test-ctx)
          parent (session/new-session-in! ctx nil {})
          parent-id (:session-id parent)
          top-child (session/new-session-in! ctx parent-id {:session-name "wf-top"})
          top-child-id (:session-id top-child)
          nested-child (session/new-session-in! ctx parent-id {:session-name "wf-nested"})
          nested-child-id (:session-id nested-child)
          finished-nested (java.time.Instant/parse "2026-05-29T12:00:00Z")
          finished-top (java.time.Instant/parse "2026-05-29T12:01:00Z")]
      (swap! (:state* ctx) assoc-in [:agent-session :sessions top-child-id :data :workflow-owned?] true)
      (swap! (:state* ctx) assoc-in [:agent-session :sessions nested-child-id :data :workflow-owned?] true)
      (swap! (:state* ctx) assoc-in [:workflows :runs]
             {"run-top" {:run-id "run-top"
                         :parent-session-id parent-id
                         :status :completed
                         :finished-at finished-top
                         :step-runs {"plan" {:attempts [{:execution-session-id top-child-id}]}}}
              "run-nested" {:run-id "run-nested"
                            :parent-session-id parent-id
                            :delegating-run-id "run-top"
                            :status :completed
                            :finished-at finished-nested
                            :step-runs {"work" {:attempts [{:execution-session-id nested-child-id}]}}}})
      ;; Nested run is created after the top-level run during execution.
      (swap! (:state* ctx) assoc-in [:workflows :run-order] ["run-top" "run-nested"])
      (workflow-run-retention/apply-retention-cleanup! ctx "run-top")
      (is (some? (get-in @(:state* ctx) [:workflows :runs "run-top"]))
          "the user's top-level delegated run is retained")
      (is (some? (get-in @(:state* ctx) [:workflows :runs "run-nested"]))
          "the nested sub-run is retained (not counted as a competing delegation)")
      (is (some? (ss/get-session-data-in ctx top-child-id))
          "the top-level run's session survives")
      (is (some? (ss/get-session-data-in ctx nested-child-id))
          "the nested sub-run's session survives")))

  (testing "removing a top-level run transitively removes its nested :delegate sub-runs and their sessions"
    (let [ctx (make-test-ctx)
          parent (session/new-session-in! ctx nil {})
          parent-id (:session-id parent)
          old-top-child (session/new-session-in! ctx parent-id {:session-name "old-top"})
          old-top-child-id (:session-id old-top-child)
          old-nested-child (session/new-session-in! ctx parent-id {:session-name "old-nested"})
          old-nested-child-id (:session-id old-nested-child)
          new-top-child (session/new-session-in! ctx parent-id {:session-name "new-top"})
          new-top-child-id (:session-id new-top-child)
          t0 (java.time.Instant/parse "2026-05-29T12:00:00Z")
          t1 (java.time.Instant/parse "2026-05-29T12:01:00Z")
          t2 (java.time.Instant/parse "2026-05-29T12:02:00Z")]
      (doseq [sid [old-top-child-id old-nested-child-id new-top-child-id]]
        (swap! (:state* ctx) assoc-in [:agent-session :sessions sid :data :workflow-owned?] true))
      (swap! (:state* ctx) assoc-in [:workflows :runs]
             {"old-top" {:run-id "old-top"
                         :parent-session-id parent-id
                         :status :completed
                         :finished-at t0
                         :step-runs {"plan" {:attempts [{:execution-session-id old-top-child-id}]}}}
              "old-nested" {:run-id "old-nested"
                            :parent-session-id parent-id
                            :delegating-run-id "old-top"
                            :status :completed
                            :finished-at t1
                            :step-runs {"work" {:attempts [{:execution-session-id old-nested-child-id}]}}}
              "new-top" {:run-id "new-top"
                         :parent-session-id parent-id
                         :status :completed
                         :finished-at t2
                         :step-runs {"plan" {:attempts [{:execution-session-id new-top-child-id}]}}}})
      (swap! (:state* ctx) assoc-in [:workflows :run-order] ["old-top" "old-nested" "new-top"])
      (workflow-run-retention/apply-retention-cleanup! ctx "new-top")
      (is (some? (get-in @(:state* ctx) [:workflows :runs "new-top"]))
          "the newest top-level run is retained")
      (is (nil? (get-in @(:state* ctx) [:workflows :runs "old-top"]))
          "the older top-level run is removed")
      (is (nil? (get-in @(:state* ctx) [:workflows :runs "old-nested"]))
          "the older run's nested sub-run is transitively removed")
      (is (nil? (ss/get-session-data-in ctx old-top-child-id))
          "the older top-level run's session is removed")
      (is (nil? (ss/get-session-data-in ctx old-nested-child-id))
          "the older run's nested sub-run session is removed")
      (is (some? (ss/get-session-data-in ctx new-top-child-id))
          "the newest top-level run's session survives"))))

(deftest workflow-run-retention-drops-cancellation-entry-locks-test
  ;; Retention cleanup forgets removed workflow runs; it must also forget the
  ;; corresponding runtime-only cancellation-entry locks so the lock handle is
  ;; bounded to retained canonical run records.
  (let [ctx (make-test-ctx)
        parent (session/new-session-in! ctx nil {})
        parent-id (:session-id parent)
        t0 (java.time.Instant/parse "2026-05-29T12:00:00Z")
        t1 (java.time.Instant/parse "2026-05-29T12:01:00Z")]
    (swap! (:state* ctx) assoc-in [:workflows :runs]
           {"old-top" {:run-id "old-top"
                       :parent-session-id parent-id
                       :status :completed
                       :finished-at t0
                       :step-runs {}}
            "old-nested" {:run-id "old-nested"
                          :parent-session-id parent-id
                          :delegating-run-id "old-top"
                          :status :completed
                          :finished-at t0
                          :step-runs {}}
            "new-top" {:run-id "new-top"
                       :parent-session-id parent-id
                       :status :completed
                       :finished-at t1
                       :step-runs {}}})
    (swap! (:state* ctx) assoc-in [:workflows :run-order] ["old-top" "old-nested" "new-top"])
    (cancellation-entry/lock-for ctx "old-top")
    (cancellation-entry/lock-for ctx "old-nested")
    (cancellation-entry/lock-for ctx "new-top")
    (workflow-run-retention/apply-retention-cleanup! ctx "new-top")
    (is (nil? (get @(:workflow-cancellation-entry-locks-handle ctx) "old-top"))
        "retention cleanup drops the removed top-level run lock")
    (is (nil? (get @(:workflow-cancellation-entry-locks-handle ctx) "old-nested"))
        "retention cleanup drops the removed nested run lock")
    (is (some? (get @(:workflow-cancellation-entry-locks-handle ctx) "new-top"))
        "retained canonical run lock remains available")))
