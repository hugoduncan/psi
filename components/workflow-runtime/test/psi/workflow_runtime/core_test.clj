(ns psi.workflow-runtime.core-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.workflow-runtime.model :as workflow-model]
   [psi.workflow-runtime.core :as workflow-runtime]
   [psi.workflow-registry.registry :as workflow-registry]))

(def registered-definition
  {:definition-id "plan-build-review"
   :name "Plan Build Review"
   :steps [{:name "plan"
            :type :session
            :contributions [{:type :template
                             :text "Plan {{task}}"
                             :vars {"task" {:from :workflow-input :path [:task]}}}]}
           {:name "build"
            :type :session
            :contributions [{:type :template
                             :text "Build {{plan}}"
                             :vars {"plan" {:from {:step "plan" :yield :text}}}}]}
           {:name "review"
            :type :session
            :contributions [{:type :template
                             :text "Review {{build}}"
                             :vars {"build" {:from {:step "build" :yield :text}}}}]}]})

(def inline-definition
  {:name "Inline"
   :steps [{:name "only-step"
            :type :session
            :contributions [{:type :template
                             :text "{{task}}"
                             :vars {"task" {:from :workflow-input :path [:task]}}}]}]})

(deftest create-run-from-registered-definition-test
  (testing "create-run captures immutable effective definition snapshot and initializes per-step runs"
    (let [[state1 definition-id _]
          (workflow-registry/register-definition {:workflows (workflow-model/initial-workflow-state)}
                                                 registered-definition)
          [state2 run-id run]
          (workflow-runtime/create-run state1 {:definition-id definition-id
                                               :run-id "run-1"
                                               :workflow-input {:task "ship it"}})]
      (is (= "run-1" run-id))
      (is (= :pending (:status run)))
      (is (= "plan" (:current-step-id run)))
      (is (= definition-id (:source-definition-id run)))
      (is (= ["plan" "build" "review"]
             (get-in run [:effective-definition :step-order])))
      (is (= [:session :session :session]
             (->> (get-in run [:effective-definition :steps])
                  vals
                  (sort-by :name)
                  (mapv :type))))
      (is (= :workflow-ir/v1 (get-in run [:effective-definition :canonical-ir :version])))
      (is (= #{"plan" "build" "review"}
             (set (keys (:step-runs run)))))
      (is (= run (workflow-runtime/workflow-run-in state2 run-id)))
      (is (= [run-id] (get-in state2 [:workflows :run-order]))))))

(deftest create-run-from-inline-definition-test
  (testing "create-run accepts inline definitions and persists nil source-definition-id"
    (let [state {:workflows (workflow-model/initial-workflow-state)}
          [_ run-id run]
          (workflow-runtime/create-run state {:definition inline-definition
                                              :run-id "inline-run"
                                              :workflow-input {:task "inline"}})]
      (is (= "inline-run" run-id))
      (is (nil? (:source-definition-id run)))
      (is (= "only-step" (:current-step-id run)))
      (is (= "only-step" (-> run :effective-definition :step-order first))))))

(deftest create-run-preserves-parent-session-id-test
  (testing "create-run persists the authoritative delegating session id when provided"
    (let [[state1 definition-id _]
          (workflow-registry/register-definition {:workflows (workflow-model/initial-workflow-state)}
                                                 registered-definition)
          [_ _ run]
          (workflow-runtime/create-run state1 {:definition-id definition-id
                                               :run-id "run-with-parent"
                                               :parent-session-id "session-delegator"
                                               :workflow-input {:task "ship it"}})]
      (is (= "session-delegator" (:parent-session-id run)))
      (is (= "session-delegator"
             (get-in run [:history 0 :data :parent-session-id]))))))

(deftest create-run-persists-inherited-defaults-snapshot-test
  (testing "create-run records the inherited-defaults snapshot verbatim and the
            run validates against workflow-run-schema (task 207)"
    (let [[state1 definition-id _]
          (workflow-registry/register-definition {:workflows (workflow-model/initial-workflow-state)}
                                                 registered-definition)
          snapshot {:model {:provider "anthropic" :id "claude-test"}
                    :prompt-mode :concise
                    :tool-defs [{:name "read"}]
                    :skills [{:name "skill-a"}]
                    :thinking-level :high
                    :speed-mode :fast
                    :effort-override :xhigh}
          [_ _ run]
          (workflow-runtime/create-run state1 {:definition-id definition-id
                                               :run-id "run-with-snapshot"
                                               :parent-session-id "session-delegator"
                                               :inherited-defaults snapshot
                                               :workflow-input {:task "ship it"}})]
      (is (= snapshot (:inherited-defaults run))
          "snapshot persisted verbatim, no resolution")
      (is (workflow-model/valid-workflow-run? run)
          "run with inherited-defaults validates against the schema"))))

(deftest create-run-without-inherited-defaults-omits-key-test
  (testing "create-run without :inherited-defaults omits the key (back-compat)"
    (let [[state1 definition-id _]
          (workflow-registry/register-definition {:workflows (workflow-model/initial-workflow-state)}
                                                 registered-definition)
          [_ _ run]
          (workflow-runtime/create-run state1 {:definition-id definition-id
                                               :run-id "run-no-snapshot"
                                               :workflow-input {:task "ship it"}})]
      (is (not (contains? run :inherited-defaults)))
      (is (workflow-model/valid-workflow-run? run)))))

(deftest update-run-workflow-input-test
  (testing "update-run-workflow-input replaces workflow input and records history"
    (let [[state1 definition-id _]
          (workflow-registry/register-definition {:workflows (workflow-model/initial-workflow-state)}
                                                 registered-definition)
          [state2 run-id _]
          (workflow-runtime/create-run state1 {:definition-id definition-id
                                               :run-id "run-1"
                                               :workflow-input {:input "old" :original "old"}})
          [state3 updated-run]
          (workflow-runtime/update-run-workflow-input state2 run-id {:input "new" :original "new"})]
      (is (= {:input "new" :original "new"} (:workflow-input updated-run)))
      (is (= updated-run (workflow-runtime/workflow-run-in state3 run-id)))
      (is (= :workflow/input-updated (-> updated-run :history last :event))))))

(deftest resume-run-test
  (testing "resume-run clears blocked payload and returns the run to :running"
    (let [[state1 definition-id _]
          (workflow-registry/register-definition {:workflows (workflow-model/initial-workflow-state)}
                                                 registered-definition)
          [state2 run-id _]
          (workflow-runtime/create-run state1 {:definition-id definition-id
                                               :run-id "run-1"
                                               :workflow-input {:input "x"}})
          state3 (-> state2
                     (assoc-in [:workflows :runs run-id :status] :blocked)
                     (assoc-in [:workflows :runs run-id :blocked] {:question "ship it?"}))
          [state4 resumed-run]
          (workflow-runtime/resume-run state3 run-id)]
      (is (= :running (:status resumed-run)))
      (is (nil? (:blocked resumed-run)))
      (is (= resumed-run (workflow-runtime/workflow-run-in state4 run-id)))
      (is (= :workflow/resume (-> resumed-run :history last :event))))))

(deftest cancel-run-test
  (testing "cancel-run marks a run cancelled and records terminal outcome/history"
    (let [[state1 definition-id _]
          (workflow-registry/register-definition {:workflows (workflow-model/initial-workflow-state)}
                                                 registered-definition)
          [state2 run-id _]
          (workflow-runtime/create-run state1 {:definition-id definition-id
                                               :run-id "run-1"
                                               :workflow-input {:input "x"}})
          [state3 cancelled-run]
          (workflow-runtime/cancel-run state2 run-id "operator request")]
      (is (= :cancelled (:status cancelled-run)))
      (is (= "operator request" (get-in cancelled-run [:terminal-outcome :reason])))
      (is (= cancelled-run (workflow-runtime/workflow-run-in state3 run-id)))
      (is (= :workflow/cancel (-> cancelled-run :history last :event))))))

(deftest remove-run-test
  (testing "remove-run removes the run from runs and run-order"
    (let [[state1 definition-id _]
          (workflow-registry/register-definition {:workflows (workflow-model/initial-workflow-state)}
                                                 registered-definition)
          [state2 run-id run]
          (workflow-runtime/create-run state1 {:definition-id definition-id
                                               :run-id "run-1"
                                               :workflow-input {:input "x"}})
          [state3 removed-run]
          (workflow-runtime/remove-run state2 run-id)]
      (is (= run removed-run))
      (is (nil? (workflow-runtime/workflow-run-in state3 run-id)))
      (is (= [] (get-in state3 [:workflows :run-order]))))))
