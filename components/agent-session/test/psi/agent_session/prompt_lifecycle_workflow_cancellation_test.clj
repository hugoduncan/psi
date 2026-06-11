(ns psi.agent-session.prompt-lifecycle-workflow-cancellation-test
  (:require
   [clojure.test :refer [deftest is]]
   [psi.agent-session.core :as session]
   [psi.agent-session.dispatch-effects :as dispatch-effects]
   [psi.agent-session.dispatch-schema :as dispatch-schema]
   [psi.agent-session.test-support :as test-support]
   [psi.agent-session.turn.handlers :as turn-handlers]
   [psi.memory.runtime :as memory-runtime]
   [psi.session-state.state :as ss]
   [psi.workflow-registry.registry :as workflow-registry]
   [psi.workflow-runtime.core :as workflow-runtime]))

(defn- create-session-context
  ([] (create-session-context {}))
  ([opts]
   (let [ctx (session/create-context (test-support/safe-context-opts opts))
         sd  (session/new-session-in! ctx nil {})]
     [ctx (:session-id sd)])))

(defn- install-workflow-run!
  [ctx run-id]
  (swap! (:state* ctx)
         (fn [state]
           (let [[s _ _] (workflow-registry/register-definition
                          state
                          {:definition-id "prompt-lifecycle-cancel-test"
                           :steps [{:name "plan" :type :session}]})
                 [s _ _] (workflow-runtime/create-run
                          s
                          {:definition-id "prompt-lifecycle-cancel-test"
                           :run-id run-id
                           :workflow-input {:input "ship it"}})]
             s))))

(deftest workflow-cancel-between-prompt-submit-and-prompt-blocks-streaming-transition-test
  ;; Regression for task 225 pass 19: a workflow-owned prompt lifecycle must not
  ;; transition the child session to streaming when cancellation lands after
  ;; prompt-submit but before :session/prompt.
  (let [[ctx session-id] (create-session-context {:persist? false})
        run-id "run-prompt-submit-to-prompt-cancel"]
    (install-workflow-run! ctx run-id)
    (swap! (:state* ctx)
           update-in (ss/session-data-path session-id)
           assoc
           :workflow-owned? true
           :workflow-run-id run-id
           :workflow-step-id "plan"
           :workflow-attempt-id "attempt-plan")
    (session/dispatch-in! ctx :session/prompt-submit
                          {:session-id session-id
                           :user-msg {:role "user"
                                      :content [{:type :text :text "plan"}]
                                      :timestamp (java.time.Instant/now)}}
                          {:origin :core})
    (session/dispatch-in! ctx :psi.workflow/cancel-run
                          {:run-id run-id
                           :reason "prompt lifecycle race"}
                          {:origin :core})
    (let [result (session/dispatch-in! ctx :session/prompt
                                       {:session-id session-id}
                                       {:origin :core})]
      (is (= {:workflow-stopped? true
              :reason :cancelled
              :session-id session-id}
             result))
      (is (= :idle (ss/sc-phase-in ctx session-id))
          "cancelled workflow-owned child sessions must not enter streaming after prompt-submit"))))

(deftest workflow-cancel-between-prompt-and-prepare-blocks-request-preparation-test
  ;; Regression for task 225 pass 19: after a workflow-owned session has entered
  ;; streaming, cancellation before :session/prompt-prepare-request must prevent
  ;; request preparation, memory recovery, and provider execution effects.
  (let [[ctx0 session-id] (create-session-context {:persist? false})
        run-id "run-prompt-to-prepare-cancel"
        build-calls* (atom 0)
        execute-calls* (atom 0)
        ctx (assoc ctx0
                   :build-prepared-request-fn
                   (fn [& _]
                     (swap! build-calls* inc)
                     {:prepared-request/id "turn-1"
                      :prepared-request/messages []})
                   :execute-prepared-request-fn
                   (fn [& _]
                     (swap! execute-calls* inc)
                     {:execution-result/turn-id "turn-1"
                      :execution-result/session-id session-id
                      :execution-result/assistant-message {:role "assistant"
                                                           :content [{:type :text :text "must not execute"}]
                                                           :stop-reason :stop}
                      :execution-result/turn-outcome :turn.outcome/stop}))]
    (install-workflow-run! ctx run-id)
    (swap! (:state* ctx)
           update-in (ss/session-data-path session-id)
           assoc
           :workflow-owned? true
           :workflow-run-id run-id
           :workflow-step-id "plan"
           :workflow-attempt-id "attempt-plan")
    (let [submit-result (session/dispatch-in! ctx :session/prompt-submit
                                              {:session-id session-id
                                               :user-msg {:role "user"
                                                          :content [{:type :text :text "plan"}]
                                                          :timestamp (java.time.Instant/now)}}
                                              {:origin :core})]
      (session/dispatch-in! ctx :session/prompt {:session-id session-id} {:origin :core})
      (session/dispatch-in! ctx :psi.workflow/cancel-run
                            {:run-id run-id
                             :reason "prompt prepare race"}
                            {:origin :core})
      (let [result (session/dispatch-in! ctx :session/prompt-prepare-request
                                         {:session-id session-id
                                          :turn-id (:turn-id submit-result)
                                          :user-msg (:user-msg submit-result)
                                          :return-execution-result? true}
                                         {:origin :core})]
        (is (= :cancelled (get-in result [:execution-result/assistant-message :workflow-stop-reason])))
        (is (= 0 @build-calls*)
            "request preparation must not run after workflow cancellation")
        (is (= 0 @execute-calls*)
            "provider execution must not run when request preparation is stopped")
        (is (nil? (:last-prepared-request-summary (ss/get-session-data-in ctx session-id)))
            "cancelled prepare must not record ordinary prompt lifecycle state")))))

(deftest workflow-cancel-after-prepare-before-effects-blocks-memory-and-provider-effects-test
  ;; Regression for task 225 pass 20: prompt-prepare may already have built an
  ;; effect vector when cancellation lands before the effects interceptor runs.
  ;; Workflow-guarded post-prepare effects must re-read the canonical stop signal
  ;; and no-op rather than starting memory recovery or provider execution.
  (let [[ctx0 session-id] (create-session-context {:persist? false})
        run-id "run-prepare-effects-cancel"
        memory-calls* (atom [])
        execute-calls* (atom 0)
        ctx (assoc ctx0
                   :execute-prepared-request-fn
                   (fn [& _]
                     (swap! execute-calls* inc)
                     {:execution-result/turn-id "turn-1"
                      :execution-result/session-id session-id
                      :execution-result/assistant-message {:role "assistant"
                                                           :content [{:type :text :text "must not execute"}]
                                                           :stop-reason :stop}
                      :execution-result/turn-outcome :turn.outcome/stop}))
        prepared-request {:prepared-request/id "turn-1"
                          :prepared-request/user-message {:role "user"
                                                          :content [{:type :text :text "recover this"}]}}
        effects (mapv #(assoc % :session-id session-id)
                      (turn-handlers/prompt-prepare-request-effects
                       prepared-request nil false false run-id))]
    (is (= [:memory/recover-query :runtime/prompt-execute-and-record]
           (mapv :effect/type effects))
        "regression must exercise the normal non-returning prepare effect vector")
    (is (every? #(= run-id (:workflow-run-id %)) effects)
        "workflow-owned post-prepare effects carry a canonical run guard")
    (is (every? dispatch-schema/valid-effect? effects)
        "workflow-guarded post-prepare effects must remain valid dispatch effects")
    (install-workflow-run! ctx run-id)
    (swap! (:state* ctx)
           update-in (ss/session-data-path session-id)
           assoc
           :workflow-owned? true
           :workflow-run-id run-id
           :workflow-step-id "plan"
           :workflow-attempt-id "attempt-plan")
    (session/dispatch-in! ctx :psi.workflow/cancel-run
                          {:run-id run-id
                           :reason "prepare effects race"}
                          {:origin :core})
    (with-redefs [memory-runtime/recover-for-query! (fn [query-text]
                                                      (swap! memory-calls* conj query-text))]
      (doseq [effect effects]
        (dispatch-effects/execute-effect! ctx effect)))
    (is (= [] @memory-calls*)
        "standalone memory recovery must not execute after workflow cancellation")
    (is (= 0 @execute-calls*)
        "provider execution must not execute after workflow cancellation")))
