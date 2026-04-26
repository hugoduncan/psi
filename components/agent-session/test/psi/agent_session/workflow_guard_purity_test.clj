(ns psi.agent-session.workflow-guard-purity-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [com.fulcrologic.statecharts :as sc]
   [com.fulcrologic.statecharts.data-model.working-memory-data-model :as wmdm]
   [com.fulcrologic.statecharts.events :as evts]
   [com.fulcrologic.statecharts.protocols :as sp]
   [com.fulcrologic.statecharts.simple :as simple]
   [psi.agent-session.workflow-statechart :as workflow-sc]))

(def retry-definition
  {:definition-id "retry-test"
   :step-order ["build"]
   :steps {"build" {:executor {:type :agent :profile "builder"}
                    :result-schema :any
                    :retry-policy {:max-attempts 2 :retry-on #{:execution-failed}}}}})

(def revise-definition
  {:definition-id "review-loop"
   :step-order ["build" "review"]
   :steps {"build" {:executor {:type :agent :profile "builder"}
                    :result-schema :any
                    :retry-policy {:max-attempts 2 :retry-on #{:execution-failed}}}
           "review" {:executor {:type :agent :profile "reviewer"}
                     :result-schema :any
                     :retry-policy {:max-attempts 1 :retry-on #{}}
                     :judge {:prompt "APPROVED or REVISE?"}
                     :on {"REVISE" {:goto "build" :max-iterations 2}
                          "APPROVED" {:goto :next}}}}})

(defn- start-wm
  [chart data]
  (let [env (simple/simple-env)
        session-id (java.util.UUID/randomUUID)]
    (simple/register! env :workflow-run chart)
    {:env env
     :wm (sp/start! (::sc/processor env) env :workflow-run
                    {::sc/session-id session-id
                     ::wmdm/data-model data})}))

(defn- process
  [{:keys [env]} wm event data]
  (let [wm' (update wm ::wmdm/data-model merge data)]
    (sp/process-event! (::sc/processor env) env wm' (evts/new-event {:name event :data data}))))

(deftest actor-retry-guard-uses-working-memory-snapshot-test
  (testing "actor retry path is determined by snapshot attempt-count/max-attempts, not callback state"
    (let [chart (workflow-sc/compile-hierarchical-chart retry-definition)
          {:keys [env wm]} (start-wm chart {:attempt-counts {"build" 1}
                                            :actor-retry-limits {"build" 2}})
          wm1 (process {:env env} wm :workflow/start {})
          wm2 (process {:env env} wm1 :actor/failed {})]
      (is (= #{:step/build :step/build.acting} (::sc/configuration wm2))))

    (let [chart (workflow-sc/compile-hierarchical-chart retry-definition)
          {:keys [env wm]} (start-wm chart {:attempt-counts {"build" 2}
                                            :actor-retry-limits {"build" 2}})
          wm1 (process {:env env} wm :workflow/start {})
          wm2 (process {:env env} wm1 :actor/failed {})]
      (is (= #{:failed} (::sc/configuration wm2))))))

(deftest judge-routing-guard-uses-working-memory-iteration-snapshot-test
  (testing "judge REVISE routing uses iteration-counts from snapshot to choose goto vs fail"
    (let [review-only-definition
          {:definition-id "review-only"
           :step-order ["review"]
           :steps {"review" {:executor {:type :agent :profile "reviewer"}
                             :result-schema :any
                             :retry-policy {:max-attempts 1 :retry-on #{}}
                             :judge {:prompt "APPROVED or REVISE?"}
                             :on {"REVISE" {:goto "review" :max-iterations 2}
                                  "APPROVED" {:goto :next}}}}}
          chart (workflow-sc/compile-hierarchical-chart review-only-definition)
          {:keys [env wm]} (start-wm chart {:iteration-counts {"review" 1}})
          wm1 (process {:env env} wm :workflow/start {})
          wm2 (process {:env env} wm1 :actor/done {})
          wm3 (process {:env env} wm2 :judge/signal {:signal "REVISE"})]
      (is (= #{:step/review :step/review.acting} (::sc/configuration wm3))))

    (let [review-only-definition
          {:definition-id "review-only"
           :step-order ["review"]
           :steps {"review" {:executor {:type :agent :profile "reviewer"}
                             :result-schema :any
                             :retry-policy {:max-attempts 1 :retry-on #{}}
                             :judge {:prompt "APPROVED or REVISE?"}
                             :on {"REVISE" {:goto "review" :max-iterations 2}
                                  "APPROVED" {:goto :next}}}}}
          chart (workflow-sc/compile-hierarchical-chart review-only-definition)
          {:keys [env wm]} (start-wm chart {:iteration-counts {"review" 2}})
          wm1 (process {:env env} wm :workflow/start {})
          wm2 (process {:env env} wm1 :actor/done {})
          wm3 (process {:env env} wm2 :judge/signal {:signal "REVISE"})]
      (is (= #{:failed} (::sc/configuration wm3))))))
