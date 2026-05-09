(ns psi.workflow-runtime.statechart-runtime.state-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.workflow-runtime.core :as workflow-runtime]
   [psi.workflow-runtime.statechart-runtime.state :as state]))

(def linear-definition
  {:steps [{:name "plan"
            :type :session
            :contributions [{:type :template
                             :text "Plan {{input}}"
                             :vars {"input" {:from :workflow-input :path [:input]}}}]}
           {:name "build"
            :type :session
            :contributions [{:type :template
                             :text "Build {{plan}}"
                             :vars {"plan" {:from {:step "plan" :yield :text}}}}]}]})

(defn- ctx-with-run
  [run-id workflow-input]
  (let [[state _ _] (workflow-runtime/create-run {:workflows {:runs {} :run-order []}}
                                                 {:definition linear-definition
                                                  :run-id run-id
                                                  :workflow-input workflow-input})]
    {:state* (atom state)}))

(deftest create-working-memory-test
  (let [ctx (ctx-with-run "run-1" {:input "ship it"
                                   :original {:ticket 123}})
        wm (state/create-working-memory ctx "parent-session-1" "run-1")]
    (testing "working memory seeds the workflow/run linkage"
      (is (= "run-1" (:workflow-run-id wm)))
      (is (= "parent-session-1" (:parent-session-id wm)))
      (is (= "plan" (:current-step-id wm))))

    (testing "working memory seeds empty step outputs"
      (is (= {} (:step-outputs wm))))

    (testing "working memory seeds zero iteration and attempt counts for each step"
      (is (= {"plan" 0 "build" 0} (:iteration-counts wm)))
      (is (= {"plan" 0 "build" 0} (:attempt-counts wm))))))
