(ns psi.agent-session.workflow-statechart-runtime-call-start-cancellation-test
  (:require
   [clojure.test :refer [deftest is]]
   [psi.agent-session.core :as session]
   [psi.agent-session.test-support :as test-support]
   [psi.deterministic-operation-registry.registry :as operation-registry]
   [psi.deterministic-operation-runtime.core :as operation-runtime]
   [psi.workflow-registry.registry :as workflow-registry]
   [psi.workflow-runtime.attempts]
   [psi.workflow-runtime.core :as workflow-runtime]
   [psi.workflow-runtime.execution-adapter]
   [psi.workflow-runtime.statechart-runtime :as runtime]
   [psi.workflow-runtime.turn-execution-contract]))

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
  ([ctx definition run-id]
   (install-run! ctx definition run-id {}))
  ([ctx definition run-id run-opts]
   (swap! (:state* ctx)
          (fn [state]
            (let [[s _ _] (workflow-registry/register-definition state definition)
                  [s _ _] (workflow-runtime/create-run s (merge {:definition-id (:definition-id definition)
                                                                 :run-id run-id
                                                                 :workflow-input {:input "ship it"
                                                                                  :original {:ticket 123}}}
                                                                run-opts))]
              s)))))

(deftest ranked-model-fallback-rechecks-cancellation-before-next-candidate-test
  (let [[ctx session-id] (create-session-context)
        definition {:definition-id "ranked-fallback-cancel"
                    :steps [{:name "plan"
                             :type :session
                             :model {:type :model-query
                                     :require [{:criterion :supports-text :match :true}]}
                             :contributions [{:type :template
                                              :text "Plan {{input}}"
                                              :vars {"input" {:from :workflow-input :path [:input]}}}]}]}
        _ (install-run! ctx definition "run-ranked-fallback-cancel")
        wf-ctx (runtime/create-workflow-context ctx session-id "run-ranked-fallback-cancel")
        turn-count* (atom 0)
        model-calls* (atom [])]
    (with-redefs [psi.workflow-runtime.attempts/create-step-attempt-session!
                  (fn [_ctx _parent-session-id opts]
                    {:attempt {:attempt-id (:attempt-id opts)
                               :status :pending
                               :execution-session-id "plan-child"}
                     :execution-session {:session-id "plan-child"
                                         :model (:model opts)
                                         :model-fallback {:type :ranked-model-candidates
                                                          :candidates [{:provider "local" :id "first"}
                                                                       {:provider "openai" :id "second"}]}}})
                  psi.workflow-runtime.execution-adapter/set-session-model!
                  (fn [_ctx _sid model scope]
                    (swap! model-calls* conj {:model model :scope scope})
                    {:ok true})
                  psi.workflow-runtime.turn-execution-contract/execute-actor-turn!
                  (fn [& _]
                    (swap! turn-count* inc)
                    (swap! (:state* ctx)
                           (fn [state]
                             (-> state
                                 (assoc-in [:workflows :runs "run-ranked-fallback-cancel" :status] :cancelled)
                                 (assoc-in [:workflows :runs "run-ranked-fallback-cancel" :finished-at] (java.time.Instant/now))
                                 (assoc-in [:workflows :runs "run-ranked-fallback-cancel" :terminal-outcome]
                                           {:outcome :cancelled
                                            :reason "fallback race"
                                            :step-id "plan"}))))
                    {:status :error
                     :session-id "plan-child"
                     :assistant-message {:role "assistant"
                                         :error-message "Connection refused"
                                         :content [{:type :error :text "Connection refused"}]}
                     :assistant-text ""
                     :execution-result {}
                     :failure {:reason :provider-unavailable
                               :message "Connection refused"
                               :fallback-worthy? true}})]
      (runtime/send-and-drain! wf-ctx (:wm wf-ctx) :workflow/start nil))
    (let [run (workflow-runtime/workflow-run-in @(:state* ctx) "run-ranked-fallback-cancel")
          attempt (get-in run [:step-runs "plan" :attempts 0])]
      (is (= :cancelled (:status run)))
      (is (= 1 @turn-count*)
          "cancellation between ranked candidates must prevent the fallback turn")
      (is (= [] @model-calls*)
          "the next candidate model must not be selected after cancellation")
      (is (= :running (:status attempt))
          "late fallback failure must not be recorded after cancellation"))))

(deftest actor-turn-final-read-to-call-race-is-cancellation-safe-test
  ;; Regression for task 225 implementation review pass 10: cancellation after
  ;; the final workflow-state read but before the actor prompt adapter call must
  ;; not initiate the ordinary actor turn.
  (let [[ctx0 session-id] (create-session-context)
        prompt-calls* (atom 0)
        ctx (test-support/with-workflow-execution-adapter-overrides
              (assoc ctx0
                     :before-workflow-turn-start-fn
                     (fn [ctx _session-id {:keys [workflow-run-id workflow-step-id]}]
                       (swap! (:state* ctx)
                              (fn [state]
                                (-> state
                                    (assoc-in [:workflows :runs workflow-run-id :status] :cancelled)
                                    (assoc-in [:workflows :runs workflow-run-id :finished-at] (java.time.Instant/now))
                                    (assoc-in [:workflows :runs workflow-run-id :terminal-outcome]
                                              {:outcome :cancelled
                                               :reason "actor final-start race"
                                               :step-id workflow-step-id}))))))
              {:get-session-data (fn [_ctx session-id]
                                   {:session-id session-id
                                    :workflow-owned? true
                                    :workflow-run-id "run-actor-final-start-race"
                                    :workflow-step-id "plan"
                                    :workflow-attempt-id "attempt-plan"})
               :prompt-execution-result! (fn [& _]
                                           (swap! prompt-calls* inc)
                                           {:execution-result/assistant-message
                                            {:role "assistant"
                                             :content [{:type :text :text "must not start"}]
                                             :stop-reason :stop}})})
        _ (install-run! ctx linear-definition "run-actor-final-start-race")
        wf-ctx (runtime/create-workflow-context ctx session-id "run-actor-final-start-race")]
    (with-redefs [psi.workflow-runtime.attempts/create-step-attempt-session!
                  (fn [_ctx _parent-session-id _opts]
                    {:attempt {:attempt-id "attempt-plan"
                               :status :pending
                               :execution-session-id "plan-child"}
                     :execution-session {:session-id "plan-child"}})]
      (runtime/send-and-drain! wf-ctx (:wm wf-ctx) :workflow/start nil))
    (let [run (workflow-runtime/workflow-run-in @(:state* ctx) "run-actor-final-start-race")
          attempt (get-in run [:step-runs "plan" :attempts 0])]
      (is (= :cancelled (:status run)))
      (is (= 0 @prompt-calls*)
          "the prompt adapter must not be called after cancellation wins the final read->call window")
      (is (= :running (:status attempt))
          "the stopped turn result must not be recorded as ordinary actor output"))))

(deftest invoke-operation-final-read-to-call-race-is-cancellation-safe-test
  ;; Regression for task 225 implementation review pass 10: cancellation after
  ;; the deterministic-operation runtime's final workflow-state read but before
  ;; the operation handler call must not invoke the handler.
  (let [[ctx0 session-id] (create-session-context)
        operation-calls* (atom 0)
        op-reg (:deterministic-operation-registry ctx0)
        _ (operation-registry/register-operation-in!
           op-reg
           {:id "workflow/final-start-race"
            :handler (fn [_]
                       (swap! operation-calls* inc)
                       {:status :ok :data {:started? true}})})
        ctx (assoc ctx0
                   :before-workflow-operation-start-fn
                   (fn [ctx {:keys [workflow-run-id step-id]}]
                     (swap! (:state* ctx)
                            (fn [state]
                              (-> state
                                  (assoc-in [:workflows :runs workflow-run-id :status] :cancelled)
                                  (assoc-in [:workflows :runs workflow-run-id :finished-at] (java.time.Instant/now))
                                  (assoc-in [:workflows :runs workflow-run-id :terminal-outcome]
                                            {:outcome :cancelled
                                             :reason "invoke final-start race"
                                             :step-id step-id}))))))
        definition {:definition-id "invoke-final-start-race"
                    :steps [{:name "invoke"
                             :type :invoke
                             :operation "workflow/final-start-race"
                             :args {}}
                            {:name "next"
                             :type :session
                             :contributions [{:type :template
                                              :text "Next"
                                              :vars {}}]}]}
        _ (install-run! ctx definition "run-invoke-final-start-race")
        wf-ctx (runtime/create-workflow-context ctx session-id "run-invoke-final-start-race")]
    (runtime/send-and-drain! wf-ctx (:wm wf-ctx) :workflow/start nil)
    (let [run (workflow-runtime/workflow-run-in @(:state* ctx) "run-invoke-final-start-race")
          attempt (get-in run [:step-runs "invoke" :attempts 0])]
      (is (= :cancelled (:status run)))
      (is (= 0 @operation-calls*)
          "the operation handler must not be called after cancellation wins the final read->call window")
      (is (= :running (:status attempt))
          "the stopped operation result must not be recorded as ordinary invoke output")
      (is (empty? (get-in run [:step-runs "next" :attempts]))))))

(deftest actor-turn-post-reservation-to-call-race-is-cancellation-safe-test
  ;; Regression for task 225 implementation review pass 11: cancellation after a
  ;; successful actor turn-start reservation but before the prompt adapter call
  ;; must still prevent the ordinary actor turn from starting.
  (let [[ctx0 session-id] (create-session-context)
        prompt-calls* (atom 0)
        ctx (test-support/with-workflow-execution-adapter-overrides
              (assoc ctx0
                     :before-workflow-turn-start-fn
                     (fn [ctx _session-id {:keys [workflow-run-id workflow-step-id phase]}]
                       (when (= :after-reserve phase)
                         (swap! (:state* ctx)
                                (fn [state]
                                  (-> state
                                      (assoc-in [:workflows :runs workflow-run-id :status] :cancelled)
                                      (assoc-in [:workflows :runs workflow-run-id :finished-at] (java.time.Instant/now))
                                      (assoc-in [:workflows :runs workflow-run-id :terminal-outcome]
                                                {:outcome :cancelled
                                                 :reason "actor post-reservation race"
                                                 :step-id workflow-step-id})))))))
              {:get-session-data (fn [_ctx session-id]
                                   {:session-id session-id
                                    :workflow-owned? true
                                    :workflow-run-id "run-actor-post-reservation-race"
                                    :workflow-step-id "plan"
                                    :workflow-attempt-id "attempt-plan"})
               :prompt-execution-result! (fn [& _]
                                           (swap! prompt-calls* inc)
                                           {:execution-result/assistant-message
                                            {:role "assistant"
                                             :content [{:type :text :text "must not start"}]
                                             :stop-reason :stop}})})
        _ (install-run! ctx linear-definition "run-actor-post-reservation-race")
        wf-ctx (runtime/create-workflow-context ctx session-id "run-actor-post-reservation-race")]
    (with-redefs [psi.workflow-runtime.attempts/create-step-attempt-session!
                  (fn [_ctx _parent-session-id _opts]
                    {:attempt {:attempt-id "attempt-plan"
                               :status :pending
                               :execution-session-id "plan-child"}
                     :execution-session {:session-id "plan-child"}})]
      (runtime/send-and-drain! wf-ctx (:wm wf-ctx) :workflow/start nil))
    (let [run (workflow-runtime/workflow-run-in @(:state* ctx) "run-actor-post-reservation-race")
          attempt (get-in run [:step-runs "plan" :attempts 0])]
      (is (= :cancelled (:status run)))
      (is (= 0 @prompt-calls*)
          "the prompt adapter must not be called after cancellation wins the post-reservation window")
      (is (= :reserved (:turn-start-state attempt))
          "the race is after successful reservation but before committed ordinary turn start")
      (is (nil? (:turn-started-at attempt))))))

(deftest invoke-operation-post-reservation-to-call-race-is-cancellation-safe-test
  ;; Regression for task 225 implementation review pass 11: cancellation after a
  ;; successful deterministic-operation reservation but before the handler call
  ;; must still prevent the ordinary operation from starting.
  (let [[ctx0 session-id] (create-session-context)
        operation-calls* (atom 0)
        op-reg (:deterministic-operation-registry ctx0)
        _ (operation-registry/register-operation-in!
           op-reg
           {:id "workflow/post-reservation-race"
            :handler (fn [_]
                       (swap! operation-calls* inc)
                       {:status :ok :data {:started? true}})})
        ctx (assoc ctx0
                   :before-workflow-operation-start-fn
                   (fn [ctx {:keys [workflow-run-id step-id phase]}]
                     (when (= :after-reserve phase)
                       (swap! (:state* ctx)
                              (fn [state]
                                (-> state
                                    (assoc-in [:workflows :runs workflow-run-id :status] :cancelled)
                                    (assoc-in [:workflows :runs workflow-run-id :finished-at] (java.time.Instant/now))
                                    (assoc-in [:workflows :runs workflow-run-id :terminal-outcome]
                                              {:outcome :cancelled
                                               :reason "invoke post-reservation race"
                                               :step-id step-id})))))))
        definition {:definition-id "invoke-post-reservation-race"
                    :steps [{:name "invoke"
                             :type :invoke
                             :operation "workflow/post-reservation-race"
                             :args {}}
                            {:name "next"
                             :type :session
                             :contributions [{:type :template
                                              :text "Next"
                                              :vars {}}]}]}
        _ (install-run! ctx definition "run-invoke-post-reservation-race")
        wf-ctx (runtime/create-workflow-context ctx session-id "run-invoke-post-reservation-race")]
    (runtime/send-and-drain! wf-ctx (:wm wf-ctx) :workflow/start nil)
    (let [run (workflow-runtime/workflow-run-in @(:state* ctx) "run-invoke-post-reservation-race")
          attempt (get-in run [:step-runs "invoke" :attempts 0])]
      (is (= :cancelled (:status run)))
      (is (= 0 @operation-calls*)
          "the operation handler must not be called after cancellation wins the post-reservation window")
      (is (= :reserved (:operation-start-state attempt))
          "the race is after successful reservation but before committed ordinary operation start")
      (is (nil? (:operation-started-at attempt)))
      (is (empty? (get-in run [:step-runs "next" :attempts]))))))

(deftest actor-turn-start-commit-to-call-race-is-cancellation-safe-test
  ;; Regression for task 225 implementation review pass 12: cancellation after a
  ;; successful actor :started commit but before the prompt adapter call must
  ;; still prevent the ordinary actor turn from starting.
  (let [[ctx0 session-id] (create-session-context)
        prompt-calls* (atom 0)
        ctx (test-support/with-workflow-execution-adapter-overrides
              (assoc ctx0
                     :before-workflow-turn-start-fn
                     (fn [ctx _session-id {:keys [workflow-run-id workflow-step-id phase]}]
                       (when (= :after-commit phase)
                         (swap! (:state* ctx)
                                (fn [state]
                                  (-> state
                                      (assoc-in [:workflows :runs workflow-run-id :status] :cancelled)
                                      (assoc-in [:workflows :runs workflow-run-id :finished-at] (java.time.Instant/now))
                                      (assoc-in [:workflows :runs workflow-run-id :terminal-outcome]
                                                {:outcome :cancelled
                                                 :reason "actor start-commit race"
                                                 :step-id workflow-step-id})))))))
              {:get-session-data (fn [_ctx session-id]
                                   {:session-id session-id
                                    :workflow-owned? true
                                    :workflow-run-id "run-actor-start-commit-race"
                                    :workflow-step-id "plan"
                                    :workflow-attempt-id "attempt-plan"})
               :prompt-execution-result! (fn [& _]
                                           (swap! prompt-calls* inc)
                                           {:execution-result/assistant-message
                                            {:role "assistant"
                                             :content [{:type :text :text "must not start"}]
                                             :stop-reason :stop}})})
        _ (install-run! ctx linear-definition "run-actor-start-commit-race")
        wf-ctx (runtime/create-workflow-context ctx session-id "run-actor-start-commit-race")]
    (with-redefs [psi.workflow-runtime.attempts/create-step-attempt-session!
                  (fn [_ctx _parent-session-id _opts]
                    {:attempt {:attempt-id "attempt-plan"
                               :status :pending
                               :execution-session-id "plan-child"}
                     :execution-session {:session-id "plan-child"}})]
      (runtime/send-and-drain! wf-ctx (:wm wf-ctx) :workflow/start nil))
    (let [run (workflow-runtime/workflow-run-in @(:state* ctx) "run-actor-start-commit-race")
          attempt (get-in run [:step-runs "plan" :attempts 0])]
      (is (= :cancelled (:status run)))
      (is (= 0 @prompt-calls*)
          "the prompt adapter must not be called after cancellation wins the start-commit window")
      (is (= :started (:turn-start-state attempt))
          "the race is after successful start commit")
      (is (nil? (:turn-call-state attempt)))
      (is (= :running (:status attempt))))))

(deftest actor-turn-call-begin-to-call-race-is-cancellation-safe-test
  ;; Regression for task 225 implementation review pass 13: cancellation after
  ;; successful call-begin but before the prompt adapter call must still prevent
  ;; ordinary actor work from starting.
  (let [[ctx0 session-id] (create-session-context)
        prompt-calls* (atom 0)
        ctx (test-support/with-workflow-execution-adapter-overrides
              (assoc ctx0
                     :before-workflow-turn-start-fn
                     (fn [ctx _session-id {:keys [workflow-run-id workflow-step-id phase]}]
                       (when (= :after-call-begin phase)
                         (swap! (:state* ctx)
                                (fn [state]
                                  (-> state
                                      (assoc-in [:workflows :runs workflow-run-id :status] :cancelled)
                                      (assoc-in [:workflows :runs workflow-run-id :finished-at] (java.time.Instant/now))
                                      (assoc-in [:workflows :runs workflow-run-id :terminal-outcome]
                                                {:outcome :cancelled
                                                 :reason "actor call-begin race"
                                                 :step-id workflow-step-id})))))))
              {:get-session-data (fn [_ctx session-id]
                                   {:session-id session-id
                                    :workflow-owned? true
                                    :workflow-run-id "run-actor-call-begin-race"
                                    :workflow-step-id "plan"
                                    :workflow-attempt-id "attempt-plan"})
               :prompt-execution-result! (fn [& _]
                                           (swap! prompt-calls* inc)
                                           {:execution-result/assistant-message
                                            {:role "assistant"
                                             :content [{:type :text :text "must not start"}]
                                             :stop-reason :stop}})})
        _ (install-run! ctx linear-definition "run-actor-call-begin-race")
        wf-ctx (runtime/create-workflow-context ctx session-id "run-actor-call-begin-race")]
    (with-redefs [psi.workflow-runtime.attempts/create-step-attempt-session!
                  (fn [_ctx _parent-session-id _opts]
                    {:attempt {:attempt-id "attempt-plan"
                               :status :pending
                               :execution-session-id "plan-child"}
                     :execution-session {:session-id "plan-child"}})]
      (runtime/send-and-drain! wf-ctx (:wm wf-ctx) :workflow/start nil))
    (let [run (workflow-runtime/workflow-run-in @(:state* ctx) "run-actor-call-begin-race")
          attempt (get-in run [:step-runs "plan" :attempts 0])]
      (is (= :cancelled (:status run)))
      (is (= 0 @prompt-calls*)
          "the prompt adapter must not be called after cancellation wins the call-begin window")
      (is (= :begun (:turn-call-state attempt))
          "the race is after successful actor call-begin")
      (is (nil? (:turn-call-committed-at attempt)))
      (is (= :running (:status attempt))))))

(deftest invoke-operation-start-commit-to-call-race-is-cancellation-safe-test
  ;; Regression for task 225 implementation review pass 12: cancellation after a
  ;; successful deterministic-operation :started commit but before the handler
  ;; call must still prevent the ordinary operation from starting.
  (let [[ctx0 session-id] (create-session-context)
        operation-calls* (atom 0)
        op-reg (:deterministic-operation-registry ctx0)
        _ (operation-registry/register-operation-in!
           op-reg
           {:id "workflow/start-commit-race"
            :handler (fn [_]
                       (swap! operation-calls* inc)
                       {:status :ok :data {:started? true}})})
        ctx (assoc ctx0
                   :before-workflow-operation-start-fn
                   (fn [ctx {:keys [workflow-run-id step-id phase]}]
                     (when (= :after-commit phase)
                       (swap! (:state* ctx)
                              (fn [state]
                                (-> state
                                    (assoc-in [:workflows :runs workflow-run-id :status] :cancelled)
                                    (assoc-in [:workflows :runs workflow-run-id :finished-at] (java.time.Instant/now))
                                    (assoc-in [:workflows :runs workflow-run-id :terminal-outcome]
                                              {:outcome :cancelled
                                               :reason "invoke start-commit race"
                                               :step-id step-id})))))))
        definition {:definition-id "invoke-start-commit-race"
                    :steps [{:name "invoke"
                             :type :invoke
                             :operation "workflow/start-commit-race"
                             :args {}}
                            {:name "next"
                             :type :session
                             :contributions [{:type :template
                                              :text "Next"
                                              :vars {}}]}]}
        _ (install-run! ctx definition "run-invoke-start-commit-race")
        wf-ctx (runtime/create-workflow-context ctx session-id "run-invoke-start-commit-race")]
    (runtime/send-and-drain! wf-ctx (:wm wf-ctx) :workflow/start nil)
    (let [run (workflow-runtime/workflow-run-in @(:state* ctx) "run-invoke-start-commit-race")
          attempt (get-in run [:step-runs "invoke" :attempts 0])]
      (is (= :cancelled (:status run)))
      (is (= 0 @operation-calls*)
          "the operation handler must not be called after cancellation wins the start-commit window")
      (is (= :started (:operation-start-state attempt))
          "the race is after successful operation start commit")
      (is (nil? (:operation-call-state attempt)))
      (is (empty? (get-in run [:step-runs "next" :attempts]))))))

(deftest invoke-operation-call-begin-to-call-race-is-cancellation-safe-test
  ;; Regression for task 225 implementation review pass 13: cancellation after
  ;; successful operation call-begin but before the handler call must still
  ;; prevent the ordinary deterministic operation from starting.
  (let [[ctx0 session-id] (create-session-context)
        operation-calls* (atom 0)
        op-reg (:deterministic-operation-registry ctx0)
        _ (operation-registry/register-operation-in!
           op-reg
           {:id "workflow/call-begin-race"
            :handler (fn [_]
                       (swap! operation-calls* inc)
                       {:status :ok :data {:started? true}})})
        ctx (assoc ctx0
                   :before-workflow-operation-start-fn
                   (fn [ctx {:keys [workflow-run-id step-id phase]}]
                     (when (= :after-call-begin phase)
                       (swap! (:state* ctx)
                              (fn [state]
                                (-> state
                                    (assoc-in [:workflows :runs workflow-run-id :status] :cancelled)
                                    (assoc-in [:workflows :runs workflow-run-id :finished-at] (java.time.Instant/now))
                                    (assoc-in [:workflows :runs workflow-run-id :terminal-outcome]
                                              {:outcome :cancelled
                                               :reason "invoke call-begin race"
                                               :step-id step-id})))))))
        definition {:definition-id "invoke-call-begin-race"
                    :steps [{:name "invoke"
                             :type :invoke
                             :operation "workflow/call-begin-race"
                             :args {}}
                            {:name "next"
                             :type :session
                             :contributions [{:type :template
                                              :text "Next"
                                              :vars {}}]}]}
        _ (install-run! ctx definition "run-invoke-call-begin-race")
        wf-ctx (runtime/create-workflow-context ctx session-id "run-invoke-call-begin-race")]
    (runtime/send-and-drain! wf-ctx (:wm wf-ctx) :workflow/start nil)
    (let [run (workflow-runtime/workflow-run-in @(:state* ctx) "run-invoke-call-begin-race")
          attempt (get-in run [:step-runs "invoke" :attempts 0])]
      (is (= :cancelled (:status run)))
      (is (= 0 @operation-calls*)
          "the operation handler must not be called after cancellation wins the call-begin window")
      (is (= :begun (:operation-call-state attempt))
          "the race is after successful operation call-begin")
      (is (nil? (:operation-call-committed-at attempt)))
      (is (empty? (get-in run [:step-runs "next" :attempts]))))))

(deftest actor-turn-call-commit-to-call-race-is-cancellation-safe-test
  ;; Regression for task 225 implementation review pass 14: cancellation after
  ;; successful actor call commit but before prompt adapter entry must still
  ;; prevent ordinary actor work from starting.
  (let [[ctx0 session-id] (create-session-context)
        prompt-calls* (atom 0)
        ctx (test-support/with-workflow-execution-adapter-overrides
              (assoc ctx0
                     :before-workflow-turn-start-fn
                     (fn [ctx _session-id {:keys [workflow-run-id workflow-step-id phase]}]
                       (when (= :after-call-commit phase)
                         (swap! (:state* ctx)
                                (fn [state]
                                  (-> state
                                      (assoc-in [:workflows :runs workflow-run-id :status] :cancelled)
                                      (assoc-in [:workflows :runs workflow-run-id :finished-at] (java.time.Instant/now))
                                      (assoc-in [:workflows :runs workflow-run-id :terminal-outcome]
                                                {:outcome :cancelled
                                                 :reason "actor call-commit race"
                                                 :step-id workflow-step-id})))))))
              {:get-session-data (fn [_ctx session-id]
                                   {:session-id session-id
                                    :workflow-owned? true
                                    :workflow-run-id "run-actor-call-commit-race"
                                    :workflow-step-id "plan"
                                    :workflow-attempt-id "attempt-plan"})
               :prompt-execution-result! (fn [& _]
                                           (swap! prompt-calls* inc)
                                           {:execution-result/assistant-message
                                            {:role "assistant"
                                             :content [{:type :text :text "must not start"}]
                                             :stop-reason :stop}})})
        _ (install-run! ctx linear-definition "run-actor-call-commit-race")
        wf-ctx (runtime/create-workflow-context ctx session-id "run-actor-call-commit-race")]
    (with-redefs [psi.workflow-runtime.attempts/create-step-attempt-session!
                  (fn [_ctx _parent-session-id _opts]
                    {:attempt {:attempt-id "attempt-plan"
                               :status :pending
                               :execution-session-id "plan-child"}
                     :execution-session {:session-id "plan-child"}})]
      (runtime/send-and-drain! wf-ctx (:wm wf-ctx) :workflow/start nil))
    (let [run (workflow-runtime/workflow-run-in @(:state* ctx) "run-actor-call-commit-race")
          attempt (get-in run [:step-runs "plan" :attempts 0])]
      (is (= :cancelled (:status run)))
      (is (= 0 @prompt-calls*)
          "the prompt adapter must not be called after cancellation wins the call-commit window")
      (is (= :committed (:turn-call-state attempt))
          "the race is after successful actor call commit")
      (is (:turn-call-committed-at attempt))
      (is (= :running (:status attempt))))))

(deftest invoke-operation-call-commit-to-call-race-is-cancellation-safe-test
  ;; Regression for task 225 implementation review pass 14: cancellation after
  ;; successful deterministic-operation call commit but before handler entry must
  ;; still prevent ordinary operation work from starting.
  (let [[ctx0 session-id] (create-session-context)
        operation-calls* (atom 0)
        op-reg (:deterministic-operation-registry ctx0)
        _ (operation-registry/register-operation-in!
           op-reg
           {:id "workflow/call-commit-race"
            :handler (fn [_]
                       (swap! operation-calls* inc)
                       {:status :ok :data {:started? true}})})
        ctx (assoc ctx0
                   :before-workflow-operation-start-fn
                   (fn [ctx {:keys [workflow-run-id step-id phase]}]
                     (when (= :after-call-commit phase)
                       (swap! (:state* ctx)
                              (fn [state]
                                (-> state
                                    (assoc-in [:workflows :runs workflow-run-id :status] :cancelled)
                                    (assoc-in [:workflows :runs workflow-run-id :finished-at] (java.time.Instant/now))
                                    (assoc-in [:workflows :runs workflow-run-id :terminal-outcome]
                                              {:outcome :cancelled
                                               :reason "invoke call-commit race"
                                               :step-id step-id})))))))
        definition {:definition-id "invoke-call-commit-race"
                    :steps [{:name "invoke"
                             :type :invoke
                             :operation "workflow/call-commit-race"
                             :args {}}
                            {:name "next"
                             :type :session
                             :contributions [{:type :template
                                              :text "Next"
                                              :vars {}}]}]}
        _ (install-run! ctx definition "run-invoke-call-commit-race")
        wf-ctx (runtime/create-workflow-context ctx session-id "run-invoke-call-commit-race")]
    (runtime/send-and-drain! wf-ctx (:wm wf-ctx) :workflow/start nil)
    (let [run (workflow-runtime/workflow-run-in @(:state* ctx) "run-invoke-call-commit-race")
          attempt (get-in run [:step-runs "invoke" :attempts 0])]
      (is (= :cancelled (:status run)))
      (is (= 0 @operation-calls*)
          "the operation handler must not be called after cancellation wins the call-commit window")
      (is (= :committed (:operation-call-state attempt))
          "the race is after successful operation call commit")
      (is (:operation-call-committed-at attempt))
      (is (empty? (get-in run [:step-runs "next" :attempts]))))))

(deftest actor-turn-dispatch-cancel-cannot-land-between-final-read-and-prompt-submit-test
  ;; Regression for task 225 implementation review pass 15: canonical cancel
  ;; dispatch must be mutually ordered with actor prompt-submit entry, so a cancel
  ;; racing in the final read->call window cannot land D31 before the ordinary call.
  (let [[ctx0 _session-id] (create-session-context)
        prompt-calls* (atom 0)
        cancel-future* (atom nil)
        run-id "run-actor-final-entry-lock-race"
        ctx (test-support/with-workflow-execution-adapter-overrides
              (assoc ctx0
                     :before-workflow-turn-start-fn
                     (fn [ctx _session-id {:keys [phase]}]
                       (when (= :after-call-commit phase)
                         (reset! cancel-future*
                                 (future
                                   (session/dispatch-in! ctx :psi.workflow/cancel-run
                                                         {:run-id run-id
                                                          :reason "actor final entry race"}))))))
              {:get-session-data (fn [_ctx session-id]
                                   {:session-id session-id
                                    :workflow-owned? true
                                    :workflow-run-id run-id
                                    :workflow-step-id "plan"
                                    :workflow-attempt-id "attempt-plan"})
               :prompt-execution-result! (fn [& _]
                                           (swap! prompt-calls* inc)
                                           {:execution-result/assistant-message
                                            {:role "assistant"
                                             :content [{:type :text :text "started before D31"}]
                                             :stop-reason :stop}})})]
    (install-run! ctx linear-definition run-id)
    (swap! (:state* ctx)
           assoc-in [:workflows :runs run-id :current-step-id] "plan")
    (swap! (:state* ctx)
           assoc-in [:workflows :runs run-id :step-runs "plan" :attempts]
           [{:attempt-id "attempt-plan"
             :status :running
             :execution-session-id "plan-child"}])
    (psi.workflow-runtime.turn-execution-contract/execute-actor-turn! ctx "plan-child" "Plan")
    (is (some? @cancel-future*)
        "the regression forces a canonical cancel dispatch in the final entry window")
    (deref @cancel-future* 5000 ::timeout)
    (is (= 1 @prompt-calls*)
        "the actor prompt entry is ordered before the D31 cancel checkpoint, not after it")
    (is (= :cancelled (get-in @(:state* ctx) [:workflows :runs run-id :status])))))

(deftest invoke-operation-dispatch-cancel-cannot-land-between-final-read-and-handler-entry-test
  ;; Regression for task 225 implementation review pass 15: canonical cancel
  ;; dispatch must be mutually ordered with deterministic-operation handler entry.
  (let [[ctx0 _session-id] (create-session-context)
        operation-calls* (atom 0)
        handler-entry-status* (atom nil)
        cancel-future* (atom nil)
        run-id "run-invoke-final-entry-lock-race"
        op-reg (:deterministic-operation-registry ctx0)
        _ (operation-registry/register-operation-in!
           op-reg
           {:id "workflow/final-entry-lock-race"
            :handler (fn [_]
                       (swap! operation-calls* inc)
                       (reset! handler-entry-status*
                               (get-in @(:state* ctx0) [:workflows :runs run-id :status]))
                       {:status :ok :data {:started-before-d31? true}})})
        operation (operation-registry/get-operation-in
                   op-reg "workflow/final-entry-lock-race")
        ctx (assoc ctx0
                   :before-workflow-operation-start-fn
                   (fn [ctx {:keys [phase]}]
                     (when (= :before-handler-entry phase)
                       (reset! cancel-future*
                               (future
                                 (session/dispatch-in! ctx :psi.workflow/cancel-run
                                                       {:run-id run-id
                                                        :reason "invoke final entry race"}))))))]
    (install-run! ctx {:definition-id "invoke-final-entry-lock-race"
                       :steps [{:name "invoke"
                                :type :invoke
                                :operation "workflow/final-entry-lock-race"
                                :args {}}]}
                  run-id)
    (swap! (:state* ctx)
           assoc-in [:workflows :runs run-id :current-step-id] "invoke")
    (swap! (:state* ctx)
           assoc-in [:workflows :runs run-id :step-runs "invoke" :attempts]
           [{:attempt-id "attempt-invoke"
             :status :running}])
    (operation-runtime/invoke-operation
     operation
     {:ctx ctx
      :workflow-run-id run-id
      :workflow-attempt-id "attempt-invoke"
      :step-id "invoke"
      :args {}})
    (is (some? @cancel-future*)
        "the regression forces a canonical cancel dispatch in the final entry window")
    (deref @cancel-future* 5000 ::timeout)
    (is (<= @operation-calls* 1)
        "the operation handler may enter at most once")
    (when (= 1 @operation-calls*)
      (is (not= :cancelled @handler-entry-status*)
          "if the handler entered, it did so before the D31 checkpoint"))
    (is (= :cancelled (get-in @(:state* ctx) [:workflows :runs run-id :status])))))

(deftest invoke-operation-cancel-between-prepared-entry-and-handler-entry-stops-handler-test
  ;; Regression for task 225 implementation review pass 17: deterministic
  ;; operation entry is linearized without holding the cancellation-entry lock
  ;; across the full handler. A cancel after the final stop read/pre-entry
  ;; marker but before handler entry must stop the handler rather than start
  ;; ordinary work after D31.
  (let [[ctx0 _session-id] (create-session-context)
        operation-calls* (atom 0)
        cancel-result* (atom nil)
        run-id "run-invoke-pre-entry-cancel"
        op-reg (:deterministic-operation-registry ctx0)
        _ (operation-registry/register-operation-in!
           op-reg
           {:id "workflow/pre-entry-cancel"
            :handler (fn [_]
                       (swap! operation-calls* inc)
                       {:status :ok :data {:started-after-d31? true}})})
        operation (operation-registry/get-operation-in op-reg "workflow/pre-entry-cancel")
        ctx (assoc ctx0
                   :before-workflow-operation-start-fn
                   (fn [ctx {:keys [phase]}]
                     (when (= :before-handler-entry phase)
                       (reset! cancel-result*
                               (session/dispatch-in! ctx :psi.workflow/cancel-run
                                                     {:run-id run-id
                                                      :reason "invoke pre-entry race"})))))
        result (do
                 (install-run! ctx {:definition-id "invoke-pre-entry-cancel"
                                    :steps [{:name "invoke"
                                             :type :invoke
                                             :operation "workflow/pre-entry-cancel"
                                             :args {}}]}
                               run-id)
                 (swap! (:state* ctx) assoc-in [:workflows :runs run-id :current-step-id] "invoke")
                 (swap! (:state* ctx) assoc-in [:workflows :runs run-id :step-runs "invoke" :attempts]
                        [{:attempt-id "attempt-invoke"
                          :status :running}])
                 (operation-runtime/invoke-operation
                  operation
                  {:ctx ctx
                   :workflow-run-id run-id
                   :workflow-attempt-id "attempt-invoke"
                   :step-id "invoke"
                   :args {}}))]
    (is (= :cancelled (:psi.workflow/status @cancel-result*))
        "the regression commits D31 in the prepared-entry → handler-entry window")
    (is (= :error (:status result)))
    (is (= :workflow-stopped (:reason result)))
    (is (= 0 @operation-calls*)
        "the operation handler must not enter after the D31 checkpoint")
    (let [attempt (get-in @(:state* ctx)
                          [:workflows :runs run-id :step-runs "invoke" :attempts 0])]
      (is (= :committed (:operation-call-state attempt)))
      (is (= :pending (:operation-handler-entry-state attempt))
          "the prepared entry marker records the cancelled-before-entry window")
      (is (nil? (:operation-handler-entered-at attempt))))))

