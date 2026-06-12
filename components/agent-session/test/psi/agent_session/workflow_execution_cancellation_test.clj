(ns psi.agent-session.workflow-execution-cancellation-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.deterministic-operation-registry.registry]
   [psi.agent-session.workflow-execution-test-support :as support]
   [psi.agent-session.workflow-execution :as workflow-execution]
   [psi.workflow-registry.registry :as workflow-registry]
   [psi.workflow-runtime.core :as workflow-runtime]
   [psi.workflow-runtime.statechart-runtime.delegate :as workflow-delegate]
   [psi.workflow-runtime.statechart-runtime.lifecycle :as workflow-lifecycle]))

(deftest execute-run-stops-at-canonical-cancel-checkpoint-test
  ;; Tests that canonical cancellation observed during a step prevents the
  ;; workflow from recording that step result or starting the next attempt.
  (testing "cancelled run does not advance to later workflow steps"
    (let [[ctx session-id] (support/create-session-context {:persist? false})
          _ (swap! (:state* ctx)
                   (fn [state]
                     (let [[s _ _] (workflow-registry/register-definition state support/multi-step-definition-with-meta)
                           [s _ _] (workflow-runtime/create-run s {:definition-id "plan-build"
                                                                   :run-id "run-cancel-checkpoint"
                                                                   :workflow-input {:input "ship it"
                                                                                    :original "build this feature"}})]
                       s)))
          created* (atom [])
          ctx (assoc ctx
                     :workflow-create-step-attempt-session-fn
                     (fn [_ctx _parent-session-id opts]
                       (let [sid (str (:workflow-step-id opts) "-child")]
                         (swap! created* conj (:workflow-step-id opts))
                         {:attempt {:attempt-id (str sid "-attempt")
                                    :status :pending
                                    :execution-session-id sid}
                          :execution-session (support/valid-child-session sid)}))
                     :workflow-execute-actor-turn-fn
                     (fn [ctx _child-session-id _prompt & _]
                       (swap! (:state* ctx) assoc-in [:workflows :runs "run-cancel-checkpoint" :status] :cancelled)
                       {:status :ok
                        :assistant-message {:role "assistant"
                                            :content [{:type :text :text "late output"}]}
                        :assistant-text "late output"
                        :execution-result {}}))
          result (workflow-execution/execute-run! ctx session-id "run-cancel-checkpoint")
          run (workflow-runtime/workflow-run-in @(:state* ctx) "run-cancel-checkpoint")]
      (is (= :cancelled (:status result)))
      (is (= :cancelled (:status run)))
      (is (= ["step-1-planner"] @created*)
          "the second step must not start after the cancel checkpoint")
      (is (nil? (get-in run [:step-runs "step-1-planner" :accepted-result]))
          "a result returning after cancellation is not recorded as ordinary advancement"))))

(deftest lifecycle-stop-checkpoint-treats-removed-run-as-stop-test
  ;; Tests the D10 pull-stop rule directly: run absence is a cooperative stop
  ;; signal and pending events are discarded rather than processed.
  (testing "missing workflow run cancels the statechart working memory"
    (let [processed* (atom [])
          wf-ctx {:ctx {:state* (atom {:workflows {:runs {}}})}
                  :run-id "removed-run"
                  :event-queue* (atom [{:event :actor/done :data {}}])
                  :working-memory* (atom {:updated-at (java.time.Instant/now)})
                  :process-event-fn (fn [_wf-ctx wm event _data]
                                      (swap! processed* conj event)
                                      wm)}
          wm (workflow-lifecycle/drain-events! wf-ctx {})]
      (is (= #{:cancelled} (:com.fulcrologic.statecharts/configuration wm)))
      (is (empty? @(:event-queue* wf-ctx)))
      (is (= [] @processed*)
          "ordinary queued events are not processed after run removal"))))

(deftest invoke-step-stops-after-cancelled-operation-result-test
  ;; Tests the post-actor stop checkpoint for invoke steps without mocks: the
  ;; real deterministic operation registry cancels canonical run state before
  ;; returning, and the workflow must not record its ordinary result.
  (testing "invoke result returning after cancellation is not recorded"
    (let [[ctx session-id] (support/create-session-context {:persist? false})
          definition {:steps [{:name "discover"
                               :type :invoke
                               :operation "demo/cancel-during-invoke"
                               :args {}
                               :outputs {:data {:source :invoke/data}}}
                              {:name "report"
                               :type :session
                               :contributions [{:type :template
                                                :text "Report {{data}}"
                                                :vars {"data" {:from {:step "discover" :output :data}}}}]}]}
          created* (atom [])]
      (psi.deterministic-operation-registry.registry/register-operation-in!
       (:deterministic-operation-registry ctx)
       {:id "demo/cancel-during-invoke"
        :handler (fn [_invocation]
                   (swap! (:state* ctx) assoc-in [:workflows :runs "run-cancel-invoke" :status] :cancelled)
                   {:status :ok
                    :data {:late? true}})})
      (swap! (:state* ctx)
             (fn [state]
               (let [[s _ _] (workflow-runtime/create-run state {:definition definition
                                                                 :run-id "run-cancel-invoke"})]
                 s)))
      (let [ctx (assoc ctx
                       :workflow-create-step-attempt-session-fn
                       (fn [_ctx _parent-session-id opts]
                         (swap! created* conj (:workflow-step-id opts))
                         (let [sid (str (:workflow-step-id opts) "-child")]
                           {:attempt {:attempt-id (str sid "-attempt")
                                      :status :pending
                                      :execution-session-id sid}
                            :execution-session (support/valid-child-session sid)})))
            result (workflow-execution/execute-run! ctx session-id "run-cancel-invoke")
            run (workflow-runtime/workflow-run-in @(:state* ctx) "run-cancel-invoke")]
        (is (= :cancelled (:status result)))
        (is (nil? (get-in run [:step-runs "discover" :attempts 0 :effective-args])))
        (is (nil? (get-in run [:step-runs "discover" :accepted-result])))
        (is (= [] @created*)
            "the session step after the cancelled invoke must not spawn")))))

(deftest invoke-step-attempt-data-write-is-cancellation-safe-test
  ;; Tests the post-invoke attempt-data CAS guard: cancellation between the
  ;; post-invoke stop check and the write must not record ordinary metadata.
  (testing "invoke effective args are not recorded when cancellation wins the write race"
    (let [[ctx session-id] (support/create-session-context {:persist? false})
          definition {:steps [{:name "discover"
                               :type :invoke
                               :operation "demo/cancel-at-attempt-data-write"
                               :args {:repo {:from :workflow-input :path [:repo]}}
                               :outputs {:data {:source :invoke/data}}}
                              {:name "report"
                               :type :session
                               :contributions [{:type :template
                                                :text "Report {{data}}"
                                                :vars {"data" {:from {:step "discover" :output :data}}}}]}]}
          merge-called? (atom false)
          created* (atom [])]
      (psi.deterministic-operation-registry.registry/register-operation-in!
       (:deterministic-operation-registry ctx)
       {:id "demo/cancel-at-attempt-data-write"
        :handler (fn [_invocation]
                   {:status :ok
                    :data {:late? true}})})
      (swap! (:state* ctx)
             (fn [state]
               (let [[s _ _] (workflow-runtime/create-run state {:definition definition
                                                                 :run-id "run-cancel-at-attempt-data-write"
                                                                 :workflow-input {:repo "psi"}})]
                 s)))
      (let [ctx (assoc ctx
                       :before-workflow-live-state-update-fn
                       (fn [ctx {:keys [run-id kind]}]
                         (when (= :invoke-attempt-data kind)
                           (reset! merge-called? true)
                           (swap! (:state* ctx) assoc-in [:workflows :runs run-id :status] :cancelled)))
                       :workflow-create-step-attempt-session-fn
                       (fn [_ctx _parent-session-id opts]
                         (swap! created* conj (:workflow-step-id opts))
                         (let [sid (str (:workflow-step-id opts) "-child")]
                           {:attempt {:attempt-id (str sid "-attempt")
                                      :status :pending
                                      :execution-session-id sid}
                            :execution-session (support/valid-child-session sid)})))
            result (workflow-execution/execute-run! ctx session-id "run-cancel-at-attempt-data-write")
            run (workflow-runtime/workflow-run-in @(:state* ctx) "run-cancel-at-attempt-data-write")]
        (is (= :cancelled (:status result)))
        (is (= :cancelled (:status run)))
        (is (true? @merge-called?)
            "the regression must exercise the attempt-data write path")
        (is (nil? (get-in run [:step-runs "discover" :attempts 0 :effective-args]))
            "effective args must not be recorded after the cancellation checkpoint")
        (is (nil? (get-in run [:step-runs "discover" :accepted-result]))
            "the invoke result must not advance as ordinary workflow output")
        (is (= [] @created*)
            "the workflow must not spawn downstream sessions after the cancelled write race")))))

(deftest delegate-step-stops-before-creating-sub-run-when-parent-is-cancelled-test
  ;; Tests the pre-sub-run stop checkpoint directly with real registry and state:
  ;; if the parent run is cancelled before child creation, no delegate run is
  ;; created as ordinary workflow advancement.
  (testing "cancelled parent does not create a delegate sub-run"
    (let [[ctx session-id] (support/create-session-context {:persist? false})
          parent-run {:run-id "parent-cancelled"
                      :status :cancelled
                      :workflow-input {}
                      :effective-definition {:definition-id "parent-flow"}}
          child-definition {:definition-id "child-flow"
                            :steps [{:name "child-step"
                                     :type :session
                                     :contributions [{:type :template
                                                      :text "child"
                                                      :vars {}}]}]}
          create-context-called? (atom false)]
      (swap! (:state* ctx)
             (fn [state]
               (let [[state' _ _] (workflow-registry/register-definition state child-definition)]
                 (assoc-in state' [:workflows :runs "parent-cancelled"] parent-run))))
      (let [result (workflow-delegate/delegate-step-runtime-result
                    (fn [& _]
                      (reset! create-context-called? true)
                      {})
                    (fn [& _] nil)
                    nil
                    ctx
                    session-id
                    "delegate-child"
                    {:delegate {:target "child-flow"
                                :prompt-string "go"}}
                    parent-run)]
        (is (= :failure (:pending-kind result)))
        (is (false? @create-context-called?))
        (is (= ["parent-cancelled"]
               (-> @(:state* ctx) :workflows :runs keys sort vec))
            "no delegate sub-run should be created after the parent cancel checkpoint")))))

