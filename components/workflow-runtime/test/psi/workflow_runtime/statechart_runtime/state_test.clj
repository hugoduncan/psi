(ns psi.workflow-runtime.statechart-runtime.state-test
  (:require
   [clojure.test :refer [deftest is]]
   [psi.agent-session.core :as session]
   [psi.agent-session.test-support :as test-support]
   [psi.workflow-runtime.core :as workflow-runtime]
   [psi.workflow-runtime.statechart-runtime.state :as state]
   [psi.workflow-registry.registry :as workflow-registry]))

(defn- create-session-context
  []
  (let [ctx (session/create-context (test-support/safe-context-opts {:persist? false}))
        sd (session/new-session-in! ctx nil {})]
    [ctx (:session-id sd)]))

(def linear-definition
  {:definition-id "linear"
   :steps [{:name "plan"
            :type :session
            :contributions [{:type :template
                             :text "Plan {{input}}"
                             :vars {"input" {:from :workflow-input :path [:input]}}}]}
           {:name "build"
            :type :session
            :contributions [{:type :template
                             :text "Build {{plan}}"
                             :vars {"plan" {:from {:step "plan" :yield :text}}}}]}]})

(defn- install-run!
  [ctx definition run-id]
  (swap! (:state* ctx)
         (fn [state-map]
           (let [[s _ _] (workflow-registry/register-definition state-map definition)
                 [s _ _] (workflow-runtime/create-run s {:definition-id (:definition-id definition)
                                                         :run-id run-id
                                                         :workflow-input {:input "ship it"
                                                                          :original {:ticket 123}}})]
             s))))

(deftest create-working-memory-test
  (let [[ctx session-id] (create-session-context)
        _ (install-run! ctx linear-definition "run-1")
        wm (state/create-working-memory ctx session-id "run-1")]
    (is (= "run-1" (:workflow-run-id wm)))
    (is (= session-id (:parent-session-id wm)))
    (is (= {} (:step-outputs wm)))
    (is (= {"plan" 0 "build" 0} (:iteration-counts wm)))
    (is (= {"plan" 0 "build" 0} (:attempt-counts wm)))
    (is (= "plan" (:current-step-id wm)))))
