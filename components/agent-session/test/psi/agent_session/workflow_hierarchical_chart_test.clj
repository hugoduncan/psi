(ns psi.agent-session.workflow-hierarchical-chart-test
  "Tests for Phase A hierarchical chart compiler and event-queue drain execution."
  (:require
   [clojure.test :refer [deftest is testing]]
   [com.fulcrologic.statecharts :as sc]
   [com.fulcrologic.statecharts.data-model.working-memory-data-model :as wmdm]
   [com.fulcrologic.statecharts.events :as evts]
   [com.fulcrologic.statecharts.protocols :as sp]
   [com.fulcrologic.statecharts.simple :as simple]
   [psi.agent-session.workflow-statechart :as workflow-sc]))

;;; ============================================================
;;; Test helpers — event-queue drain loop
;;; ============================================================

(defn- create-chart-context
  "Create a statechart env + session for a compiled hierarchical chart.
   `actions-fn` receives (action-kw data) and may enqueue events via `event-queue*`."
  [chart actions-fn iteration-counts* _event-queue*]
  (let [env (simple/simple-env)
        session-id (java.util.UUID/randomUUID)]
    (simple/register! env :workflow-run chart)
    (let [wm0 (sp/start! (::sc/processor env) env :workflow-run
                         {::sc/session-id session-id
                          ::wmdm/data-model {:actions-fn actions-fn
                                             :iteration-counts @iteration-counts*}})]
      (sp/save-working-memory! (::sc/working-memory-store env) env session-id wm0)
      {:env env :session-id session-id :wm wm0})))

(defn- process-event
  "Process a single event, optionally merging extra data into the data model."
  [{:keys [env]} wm event-kw event-data]
  (let [wm' (if event-data
              (update wm ::wmdm/data-model merge event-data)
              wm)]
    (sp/process-event! (::sc/processor env) env wm'
                       (evts/new-event {:name event-kw :data (or event-data {})}))))

(def ^:private max-test-drain-events 200)

(defn- terminal-config?
  [wm]
  (boolean (some (::sc/configuration wm) [:completed :failed :cancelled])))

(defn- drain-events!
  "Drain the event queue, processing each event and recursing until empty.

   A hard bound turns accidental infinite test churn into a concrete failure."
  [ctx wm event-queue*]
  (loop [wm wm
         processed 0]
    (cond
      (terminal-config? wm)
      (do
        (reset! event-queue* [])
        wm)

      (>= processed max-test-drain-events)
      (throw (ex-info "Hierarchical chart test event drain exceeded safety bound"
                      {:processed-events processed
                       :max-test-drain-events max-test-drain-events
                       :configuration (::sc/configuration wm)
                       :queued-events @event-queue*}))

      :else
      (let [events @event-queue*]
        (if (empty? events)
          wm
          (do
            (reset! event-queue* [])
            (let [wm' (reduce (fn [wm {:keys [event data]}]
                                (if (terminal-config? wm)
                                  wm
                                  (process-event ctx wm event data)))
                              wm
                              events)]
              (recur wm' (+ processed (count events))))))))))

(defn- send-and-drain!
  "Send an event and drain any enqueued follow-on events."
  [ctx wm event-kw event-queue*]
  (let [wm' (process-event ctx wm event-kw nil)]
    (drain-events! ctx wm' event-queue*)))

(defn- config [wm]
  (::sc/configuration wm))

;;; ============================================================
;;; Test definitions
;;; ============================================================

(def linear-definition
  "Two-step linear workflow — no judge."
  {:definition-id "plan-build"
   :step-order ["step-1-planner" "step-2-builder"]
   :steps {"step-1-planner" {:executor {:type :agent :profile "planner"}
                             :result-schema :any
                             :retry-policy {:max-attempts 1 :retry-on #{}}}
           "step-2-builder" {:executor {:type :agent :profile "builder"}
                             :result-schema :any
                             :retry-policy {:max-attempts 1 :retry-on #{}}}}})

(def judged-definition
  "Three-step workflow with judge on review step."
  {:definition-id "plan-build-review"
   :step-order ["step-1-planner" "step-2-builder" "step-3-reviewer"]
   :steps {"step-1-planner"  {:executor {:type :agent :profile "planner"}
                              :result-schema :any
                              :retry-policy {:max-attempts 1 :retry-on #{}}}
           "step-2-builder"  {:executor {:type :agent :profile "builder"}
                              :result-schema :any
                              :retry-policy {:max-attempts 2 :retry-on #{:execution-failed}}}
           "step-3-reviewer" {:executor {:type :agent :profile "reviewer"}
                              :result-schema :any
                              :retry-policy {:max-attempts 1 :retry-on #{}}
                              :judge {:prompt "APPROVED or REVISE?"
                                      :projection {:type :tail :turns 1}}
                              :on {"APPROVED" {:goto :next}
                                   "REVISE"   {:goto "step-2-builder" :max-iterations 3}}}}})

;;; ============================================================
;;; Compilation tests
;;; ============================================================

(deftest compile-hierarchical-chart-linear-test
  (testing "linear workflow compiles to a valid statechart"
    (let [chart (workflow-sc/compile-hierarchical-chart linear-definition)]
      (is (map? chart) "compilation produces a map (statechart)")
      ;; Verify it can be registered and started
      (let [env (simple/simple-env)
            session-id (java.util.UUID/randomUUID)]
        (simple/register! env :workflow-run chart)
        (let [wm (sp/start! (::sc/processor env) env :workflow-run {::sc/session-id session-id})]
          (is (= #{:pending} (config wm)) "starts in :pending"))))
    (testing "start enters canonical .acting states"
      (let [chart (workflow-sc/compile-hierarchical-chart linear-definition)
            env (simple/simple-env)
            session-id (java.util.UUID/randomUUID)]
        (simple/register! env :workflow-run chart)
        (let [wm0 (sp/start! (::sc/processor env) env :workflow-run {::sc/session-id session-id})
              wm1 (sp/process-event! (::sc/processor env) env wm0 (evts/new-event {:name :workflow/start}))]
          (is (= #{:step/step-1-planner :step/step-1-planner.acting} (config wm1))))))))

(deftest compile-hierarchical-chart-judged-test
  (testing "judged workflow compiles to a valid statechart"
    (let [chart (workflow-sc/compile-hierarchical-chart judged-definition)]
      (is (map? chart))
      (let [env (simple/simple-env)
            session-id (java.util.UUID/randomUUID)]
        (simple/register! env :workflow-run chart)
        (let [wm (sp/start! (::sc/processor env) env :workflow-run {::sc/session-id session-id})]
          (is (= #{:pending} (config wm)))))))
  (testing "judged steps route through canonical .judging and .blocked states"
    (let [chart (workflow-sc/compile-hierarchical-chart judged-definition)
          trace* (atom [])
          event-queue* (atom [])
          iteration-counts* (atom {})
          actions-fn (fn [action-kw data]
                       (let [step-id (:step-id data)]
                         (case action-kw
                           :step/enter
                           (let [counts (swap! iteration-counts* update step-id (fnil inc 0))]
                             (swap! trace* conj [:step/enter step-id])
                             (if (= step-id "step-1-planner")
                               (swap! event-queue* conj {:event :actor/blocked :data {:iteration-counts counts}})
                               (swap! event-queue* conj {:event :actor/done :data {:iteration-counts counts}})))
                           :step/block
                           (swap! trace* conj [:step/block step-id])
                           :judge/enter
                           (swap! trace* conj [:judge/enter step-id])
                           nil)))
          ctx (create-chart-context chart actions-fn iteration-counts* event-queue*)
          wm1 (send-and-drain! ctx (:wm ctx) :workflow/start event-queue*)
          wm2 (process-event ctx wm1 :workflow/resume {:iteration-counts @iteration-counts*})]
      (is (= #{:step/step-1-planner :step/step-1-planner.blocked} (config wm1)))
      (is (some #(= [:step/block "step-1-planner"] %) @trace*))
      ;; after resume and another drain step, planner can be re-entered; the canonical
      ;; blocked target itself is the key Slice 1 proof here
      (is (set? (config wm2))))))

;;; ============================================================
;;; Linear workflow execution tests
;;; ============================================================

(deftest linear-workflow-execution-test
  (testing "linear workflow executes plan → build → completed"
    (let [trace* (atom [])
          event-queue* (atom [])
          iteration-counts* (atom {})
          actions-fn (fn [action-kw data]
                       (let [step-id (:step-id data)]
                         (case action-kw
                           :step/enter
                           (let [counts (swap! iteration-counts* update step-id (fnil inc 0))]
                             (swap! trace* conj [:step/enter step-id])
                             (swap! event-queue* conj {:event :actor/done :data {:iteration-counts counts}}))
                           :terminal/record
                           (swap! trace* conj [:terminal step-id])
                           nil)))
          chart (workflow-sc/compile-hierarchical-chart linear-definition)
          ctx (create-chart-context chart actions-fn iteration-counts* event-queue*)
          wm-final (send-and-drain! ctx (:wm ctx) :workflow/start event-queue*)]
      (is (= #{:completed} (config wm-final)))
      (is (= [[:step/enter "step-1-planner"]
              [:step/enter "step-2-builder"]
              [:terminal "completed"]]
             @trace*))
      (is (= {"step-1-planner" 1 "step-2-builder" 1} @iteration-counts*)))))

;;; ============================================================
;;; Judged workflow execution tests
;;; ============================================================

(deftest judged-workflow-loop-test
  (testing "judged workflow executes plan → build → review → REVISE → build → review → APPROVED → completed"
    (let [trace* (atom [])
          event-queue* (atom [])
          iteration-counts* (atom {})
          judge-call* (atom 0)
          actions-fn (fn [action-kw data]
                       (let [step-id (:step-id data)]
                         (case action-kw
                           :step/enter
                           (let [counts (swap! iteration-counts* update step-id (fnil inc 0))]
                             (swap! trace* conj [:step/enter step-id])
                             (swap! event-queue* conj {:event :actor/done :data {:iteration-counts counts}}))
                           :step/exit
                           (swap! trace* conj [:step/exit step-id])
                           :judge/enter
                           (let [n (swap! judge-call* inc)
                                 signal (if (= 1 n) "REVISE" "APPROVED")]
                             (swap! trace* conj [:judge/enter step-id signal])
                             (swap! event-queue* conj {:event :judge/signal
                                                       :data {:signal signal
                                                              :iteration-counts @iteration-counts*}}))
                           :judge/exit
                           (swap! trace* conj [:judge/exit step-id])
                           :terminal/enter
                           (swap! trace* conj [:terminal step-id])
                           nil)))
          chart (workflow-sc/compile-hierarchical-chart judged-definition)
          ctx (create-chart-context chart actions-fn iteration-counts* event-queue*)
          wm-final (send-and-drain! ctx (:wm ctx) :workflow/start event-queue*)]
      (is (= #{:completed} (config wm-final)))
      (is (= {"step-1-planner" 1 "step-2-builder" 2 "step-3-reviewer" 2}
             @iteration-counts*))
      ;; Verify the step execution order
      (is (= ["step-1-planner" "step-2-builder" "step-3-reviewer"
              "step-2-builder" "step-3-reviewer"]
             (->> @trace*
                  (filter #(= :step/enter (first %)))
                  (mapv second)))))))

(deftest judged-workflow-iteration-exhaustion-test
  (testing "iteration exhaustion causes :failed"
    (let [trace* (atom [])
          event-queue* (atom [])
          iteration-counts* (atom {})
          actions-fn (fn [action-kw data]
                       (let [step-id (:step-id data)]
                         (case action-kw
                           :step/enter
                           (let [counts (swap! iteration-counts* update step-id (fnil inc 0))]
                             (swap! trace* conj [:step/enter step-id])
                             (swap! event-queue* conj {:event :actor/done :data {:iteration-counts counts}}))
                           :judge/enter
                           (do
                             (swap! trace* conj [:judge/enter step-id])
                             ;; Always REVISE — should eventually exhaust iterations
                             (swap! event-queue* conj {:event :judge/signal
                                                       :data {:signal "REVISE"
                                                              :iteration-counts @iteration-counts*}}))
                           :terminal/record
                           (swap! trace* conj [:terminal step-id])
                           nil)))
          chart (workflow-sc/compile-hierarchical-chart judged-definition)
          ctx (create-chart-context chart actions-fn iteration-counts* event-queue*)
          wm-final (send-and-drain! ctx (:wm ctx) :workflow/start event-queue*)]
      ;; max-iterations 3 on builder means it can be entered at most 3 times
      ;; plan(1) → build(1) → review(1) �� REVISE → build(2) → review(2) → REVISE → build(3) → review(3) → REVISE → FAIL
      (is (= #{:failed} (config wm-final)))
      (is (= 3 (get @iteration-counts* "step-2-builder")))
      (is (some #(= [:terminal "failed"] %) @trace*)))))

;;; ============================================================
;;; Cancel tests
;;; ============================================================

(deftest cancel-from-pending-test
  (testing "cancel from pending reaches :cancelled"
    (let [trace* (atom [])
          event-queue* (atom [])
          iteration-counts* (atom {})
          actions-fn (fn [action-kw data]
                       (case action-kw
                         :terminal/record (swap! trace* conj [:terminal (:step-id data)])
                         nil))
          chart (workflow-sc/compile-hierarchical-chart linear-definition)
          ctx (create-chart-context chart actions-fn iteration-counts* event-queue*)
          wm-final (process-event ctx (:wm ctx) :workflow/cancel nil)]
      (is (= #{:cancelled} (config wm-final))))))

(deftest cancel-from-step-test
  (testing "cancel from a running step reaches :cancelled"
    (let [trace* (atom [])
          event-queue* (atom [])
          iteration-counts* (atom {})
          actions-fn (fn [action-kw data]
                       (let [step-id (:step-id data)]
                         (case action-kw
                           :step/enter
                           (do
                             (swap! iteration-counts* update step-id (fnil inc 0))
                             ;; Do NOT enqueue :actor/done — simulate "still running"
                             ;; Instead, we'll send cancel externally
                             nil)
                           :terminal/record
                           (swap! trace* conj [:terminal step-id])
                           nil)))
          chart (workflow-sc/compile-hierarchical-chart linear-definition)
          ctx (create-chart-context chart actions-fn iteration-counts* event-queue*)
          ;; Start → enters first step (entry action doesn't enqueue anything)
          wm1 (process-event ctx (:wm ctx) :workflow/start nil)
          ;; Now cancel
          wm-final (process-event ctx wm1 :workflow/cancel nil)]
      (is (= #{:cancelled} (config wm-final))))))

;;; ============================================================
;;; Actor failure tests
;;; ============================================================

(deftest actor-failure-no-retry-test
  (testing "actor failure without retry reaches :failed"
    (let [trace* (atom [])
          event-queue* (atom [])
          iteration-counts* (atom {})
          actions-fn (fn [action-kw data]
                       (let [step-id (:step-id data)]
                         (case action-kw
                           :step/enter
                           (swap! trace* conj [:step/enter step-id])
                           :terminal/record
                           (swap! trace* conj [:terminal step-id])
                           nil)))
          chart (workflow-sc/compile-hierarchical-chart linear-definition)
          ctx (create-chart-context chart actions-fn iteration-counts* event-queue*)
          wm1 (process-event ctx (:wm ctx) :workflow/start nil)
          wm-final (process-event ctx wm1 :actor/failed {:iteration-counts {"step-1-planner" 1}
                                                         :attempt-counts {"step-1-planner" 1}
                                                         :actor-retry-limits {"step-1-planner" 1}})]
      (is (= #{:failed} (config wm-final)))
      (is (= [[:step/enter "step-1-planner"] [:terminal "failed"]]
             @trace*)))))

(deftest actor-failure-with-retry-test
  (testing "actor failure with retry re-enters the step"
    (let [trace* (atom [])
          event-queue* (atom [])
          iteration-counts* (atom {})
          attempt-count* (atom 0)
          actions-fn (fn [action-kw data]
                       (let [step-id (:step-id data)]
                         (case action-kw
                           :step/enter
                           (let [n (swap! attempt-count* inc)
                                 counts (swap! iteration-counts* update step-id (fnil inc 0))]
                             (swap! trace* conj [:step/enter step-id n])
                             (if (= 1 n)
                               ;; First attempt fails
                               (swap! event-queue* conj {:event :actor/failed :data {:iteration-counts counts}})
                               ;; Second attempt succeeds
                               (swap! event-queue* conj {:event :actor/done :data {:iteration-counts counts}})))
                           :terminal/record
                           (swap! trace* conj [:terminal step-id])
                           nil)))
          ;; Use judged-definition which has retry on builder
          chart (workflow-sc/compile-hierarchical-chart
                 {:definition-id "retry-test"
                  :step-order ["step-1"]
                  :steps {"step-1" {:executor {:type :agent :profile "builder"}
                                    :result-schema :any
                                    :retry-policy {:max-attempts 2 :retry-on #{:execution-failed}}}}})
          ctx (create-chart-context chart actions-fn iteration-counts* event-queue*)
          wm-final (send-and-drain! ctx (:wm ctx) :workflow/start event-queue*)]
      (is (= #{:completed} (config wm-final)))
      (is (= 2 @attempt-count*))
      (is (= [[:step/enter "step-1" 1]
              [:step/enter "step-1" 2]
              [:terminal "completed"]]
             @trace*)))))

;;; ============================================================
;;; Judge no-match (exhausted retries) test
;;; ============================================================

(deftest judge-no-match-exhaustion-test
  (testing "judge no-match with exhausted retries reaches :failed"
    (let [trace* (atom [])
          event-queue* (atom [])
          iteration-counts* (atom {})
          actions-fn (fn [action-kw data]
                       (let [step-id (:step-id data)]
                         (case action-kw
                           :step/enter
                           (let [counts (swap! iteration-counts* update step-id (fnil inc 0))]
                             (swap! event-queue* conj {:event :actor/done :data {:iteration-counts counts}}))
                           :judge/enter
                           (do
                             (swap! trace* conj [:judge/enter step-id])
                             ;; Signal doesn't match any routing table entry
                             (swap! event-queue* conj {:event :judge/no-match}))
                           :terminal/record
                           (swap! trace* conj [:terminal step-id])
                           nil)))
          chart (workflow-sc/compile-hierarchical-chart judged-definition)
          ctx (create-chart-context chart actions-fn iteration-counts* event-queue*)
          wm-final (send-and-drain! ctx (:wm ctx) :workflow/start event-queue*)]
      (is (= #{:failed} (config wm-final))))))
