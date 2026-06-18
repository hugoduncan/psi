(ns psi.workflow-runtime.statechart-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [com.fulcrologic.statecharts :as sc]
   [com.fulcrologic.statecharts.data-model.working-memory-data-model :as wmdm]
   [com.fulcrologic.statecharts.events :as evts]
   [com.fulcrologic.statecharts.protocols :as sp]
   [com.fulcrologic.statecharts.simple :as simple]
   [psi.workflow-runtime.statechart :as workflow-sc]))

(def sample-definition
  {:definition-id "plan-build-review"
   :step-order ["plan" "build" "review"]
   :steps {"plan" {:executor {:type :agent :profile "planner" :mode :sync}
                   :result-schema [:map [:outcome [:= :ok]] [:outputs :map]]
                   :retry-policy {:max-attempts 2 :retry-on #{:execution-failed :validation-failed}}}
           "build" {:executor {:type :agent :profile "builder" :mode :async}
                    :result-schema [:map [:outcome [:= :ok]] [:outputs :map]]
                    :retry-policy {:max-attempts 2 :retry-on #{:execution-failed}}}
           "review" {:executor {:type :agent :profile "reviewer" :mode :sync}
                     :result-schema [:map [:outcome [:= :ok]] [:outputs :map]]
                     :retry-policy {:max-attempts 1 :retry-on #{:validation-failed}}}}})

(defn- run-chart-phase-after
  [events]
  (let [env        (simple/simple-env)
        session-id (java.util.UUID/randomUUID)]
    (simple/register! env :workflow-run workflow-sc/workflow-run-chart)
    (let [wm0 (sp/start! (::sc/processor env) env :workflow-run {::sc/session-id session-id})
          wmN (reduce (fn [wm event]
                        (sp/process-event! (::sc/processor env)
                                           env
                                           wm
                                           {:name event :data {}}))
                      wm0
                      events)]
      (first (::sc/configuration wmN)))))

(deftest workflow-definition-compilation-test
  (testing "canonical initial-step-id follows workflow definition order"
    (is (= "plan" (workflow-sc/initial-step-id sample-definition))))

  (testing "next-step-id follows workflow definition order"
    (is (= "build" (workflow-sc/next-step-id sample-definition "plan")))
    (is (= "review" (workflow-sc/next-step-id sample-definition "build")))
    (is (nil? (workflow-sc/next-step-id sample-definition "review")))))

(deftest workflow-run-statechart-test
  (testing "happy path phases"
    (is (= :pending (run-chart-phase-after [])))
    (is (= :running (run-chart-phase-after [:workflow/start])))
    (is (= :validating (run-chart-phase-after [:workflow/start :workflow/result-received])))
    (is (= :completed (run-chart-phase-after [:workflow/start :workflow/result-received :workflow/complete]))))

  (testing "retry and blocked paths return to legal phases"
    (is (= :running (run-chart-phase-after [:workflow/start :workflow/result-received :workflow/retry])))
    (is (= :blocked (run-chart-phase-after [:workflow/start :workflow/result-received :workflow/block])))
    (is (= :running (run-chart-phase-after [:workflow/start :workflow/result-received :workflow/block :workflow/resume]))))

  (testing "cancel and fail reach terminal phases"
    (is (= :cancelled (run-chart-phase-after [:workflow/cancel])))
    (is (= :failed (run-chart-phase-after [:workflow/start :workflow/fail])))))

(deftest workflow-run-event-surface-test
  (testing "supported run events are indexed and terminal statuses are explicit"
    (is (workflow-sc/supported-run-event? :workflow/start))
    (is (workflow-sc/supported-run-event? :workflow/complete))
    (is (not (workflow-sc/supported-run-event? :workflow/unknown)))
    (is (workflow-sc/terminal-run-status? :completed))
    (is (workflow-sc/terminal-run-status? :failed))
    (is (workflow-sc/terminal-run-status? :cancelled))
    (is (not (workflow-sc/terminal-run-status? :running)))))

;; --- Hierarchical chart iteration-exhaustion tests ---

(def judged-looping-definition
  "A minimal definition with a judged step that loops up to 2 iterations."
  {:definition-id "loop-test"
   :step-order ["produce" "check"]
   :steps {"produce" {:name "produce"
                      :type :session
                      :executor {:type :agent}
                      :result-schema [:map]
                      :retry-policy {:max-attempts 1 :retry-on #{}}}
           "check"   {:name "check"
                      :type :session
                      :judge {:type :llm}
                      :on {"DONE"    {:goto :done}
                           "CHANGED" {:goto "produce" :max-iterations 2}}
                      :executor {:type :agent}
                      :result-schema [:map]
                      :retry-policy {:max-attempts 1 :retry-on #{}}}}})

(def judged-looping-on-max-iterations-definition
  "A judged loop whose CHANGED route names an `:on-max-iterations` author target
   (the `handback` step) instead of hard-failing on exhaustion."
  {:definition-id "loop-test-on-max"
   :step-order ["produce" "check" "handback"]
   :steps {"produce"  {:name "produce"
                       :type :session
                       :executor {:type :agent}
                       :result-schema [:map]
                       :retry-policy {:max-attempts 1 :retry-on #{}}}
           "check"    {:name "check"
                       :type :session
                       :judge {:type :llm}
                       :on {"DONE"    {:goto :done}
                            "CHANGED" {:goto "produce" :max-iterations 2
                                       :on-max-iterations "handback"}}
                       :executor {:type :agent}
                       :result-schema [:map]
                       :retry-policy {:max-attempts 1 :retry-on #{}}}
           "handback" {:name "handback"
                       :type :session
                       :executor {:type :agent}
                       :result-schema [:map]
                       :retry-policy {:max-attempts 1 :retry-on #{}}}}})

(defn- hierarchical-chart-run
  "Run a compiled hierarchical chart through a sequence of events, collecting
   action dispatches. Returns {:configuration ... :actions [...]}."
  [definition events]
  (let [actions* (atom [])
        actions-fn (fn [action-kw data]
                     (swap! actions* conj {:action action-kw
                                           :step-id (:step-id data)})
                     nil)
        chart (workflow-sc/compile-hierarchical-chart definition)
        env (simple/simple-env)
        session-id (java.util.UUID/randomUUID)]
    (simple/register! env :workflow-run chart)
    (let [initial-data {:actions-fn actions-fn
                        :iteration-counts {}
                        :attempt-counts {}
                        :actor-retry-limits {"produce" 1 "check" 1}}
          wm0 (sp/start! (::sc/processor env) env :workflow-run
                         {::sc/session-id session-id
                          ::wmdm/data-model initial-data})
          wmN (reduce (fn [wm {:keys [event data]}]
                        (let [merged (assoc wm ::wmdm/data-model
                                            (merge (::wmdm/data-model wm) data {:actions-fn actions-fn}))]
                          (sp/process-event! (::sc/processor env) env merged
                                             (evts/new-event {:name event :data (or data {})}))))
                      wm0
                      events)]
      {:configuration (::sc/configuration wmN)
       :actions @actions*})))

(deftest iteration-exhaustion-fires-action-test
  (testing "judge signal CHANGED when iteration count >= max-iterations transitions to :failed with :iteration/exhausted action"
    (let [{:keys [configuration actions]}
          (hierarchical-chart-run
           judged-looping-definition
           [{:event :workflow/start :data {}}
            ;; produce step completes
            {:event :actor/done :data {:iteration-counts {"produce" 1} :attempt-counts {"produce" 1}}}
            ;; check step completes, enters judging
            {:event :actor/done :data {:iteration-counts {"check" 1} :attempt-counts {"check" 1}}}
            ;; judge says CHANGED, iteration count for "produce" is already at max
            {:event :judge/signal :data {:signal "CHANGED"
                                         :iteration-counts {"produce" 2 "check" 1}
                                         :attempt-counts {"produce" 2 "check" 1}}}])]
      (is (contains? configuration :failed)
          "Chart should be in :failed state after iteration exhaustion")
      (is (some #(= {:action :iteration/exhausted :step-id "check"} %)
                actions)
          ":iteration/exhausted action should fire for the judging step")))

  (testing "judge signal CHANGED when iteration count < max-iterations loops back normally"
    (let [{:keys [configuration actions]}
          (hierarchical-chart-run
           judged-looping-definition
           [{:event :workflow/start :data {}}
            {:event :actor/done :data {:iteration-counts {"produce" 1} :attempt-counts {"produce" 1}}}
            {:event :actor/done :data {:iteration-counts {"check" 1} :attempt-counts {"check" 1}}}
            ;; judge says CHANGED, iteration count for "produce" is below max
            {:event :judge/signal :data {:signal "CHANGED"
                                         :iteration-counts {"produce" 1 "check" 1}
                                         :attempt-counts {"produce" 1 "check" 1}}}])]
      (is (not (contains? configuration :failed))
          "Chart should NOT be in :failed state when iterations remain")
      (is (not (some #(= :iteration/exhausted (:action %)) actions))
          ":iteration/exhausted action should NOT fire when iterations remain")))

  (testing "judge signal DONE always goes to :completed regardless of iteration count"
    (let [{:keys [configuration actions]}
          (hierarchical-chart-run
           judged-looping-definition
           [{:event :workflow/start :data {}}
            {:event :actor/done :data {:iteration-counts {"produce" 1} :attempt-counts {"produce" 1}}}
            {:event :actor/done :data {:iteration-counts {"check" 1} :attempt-counts {"check" 1}}}
            {:event :judge/signal :data {:signal "DONE"
                                         :iteration-counts {"produce" 10 "check" 1}}}])]
      (is (contains? configuration :completed)
          "DONE signal should reach :completed")
      (is (not (some #(= :iteration/exhausted (:action %)) actions))
          ":iteration/exhausted should not fire for DONE signal"))))

(deftest iteration-exhaustion-routes-to-on-max-iterations-target-test
  (testing "exhausted CHANGED with :on-max-iterations routes to the author target, not :failed"
    (let [{:keys [configuration actions]}
          (hierarchical-chart-run
           judged-looping-on-max-iterations-definition
           [{:event :workflow/start :data {}}
            {:event :actor/done :data {:iteration-counts {"produce" 1} :attempt-counts {"produce" 1}}}
            {:event :actor/done :data {:iteration-counts {"check" 1} :attempt-counts {"check" 1}}}
            ;; judge says CHANGED, iteration count for "produce" is already at max
            {:event :judge/signal :data {:signal "CHANGED"
                                         :iteration-counts {"produce" 2 "check" 1}
                                         :attempt-counts {"produce" 2 "check" 1}}}])]
      (is (contains? configuration :step/handback.acting)
          "Exhaustion should route to the :on-max-iterations author target's acting state")
      (is (not (contains? configuration :failed))
          "Author-routed exhaustion should NOT mark the run :failed")
      (is (some #(= {:action :judge/record :step-id "check"} %) actions)
          "Author-routed exhaustion should dispatch :judge/record, not :iteration/exhausted")
      (is (not (some #(= :iteration/exhausted (:action %)) actions))
          ":iteration/exhausted should not fire when an author target is routed"))))

(deftest judged-routing-transition-vector-failed-target-test
  (testing "both :failed and [:failed] are normalized onto the failed transition path"
    (let [jrt #'workflow-sc/judged-routing-transition
          keyword-result (jrt {:target :failed :cond (fn [_ _] true)} "step-a")
          vector-result (jrt {:target [:failed] :cond (fn [_ _] true)} "step-a")]
      (is (= [:failed] (:target keyword-result))
          "Keyword :failed should compile to the canonical vector target form")
      (is (= [:failed] (:target vector-result))
          "Vector [:failed] should remain on the canonical vector target form")
      (is (= :judge/signal (:event keyword-result) (:event vector-result))
          "Both forms should produce the same judge event")
      (is (= 1 (count (:children keyword-result)) (count (:children vector-result)))
          "Both forms should attach exactly one script action")))

  (testing "non-failed target preserves its target shape and still carries one script action"
    (let [jrt #'workflow-sc/judged-routing-transition
          result (jrt {:target :step.produce.acting :cond (fn [_ _] true)} "step-a")]
      (is (= [:step.produce.acting] (:target result))
          "Non-failed target should be preserved in canonical vector target form")
      (is (= :judge/signal (:event result)))
      (is (= 1 (count (:children result)))
          "Non-failed route should still carry exactly one script action"))))
