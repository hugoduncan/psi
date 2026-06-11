(ns psi.agent-session.prompt-lifecycle-workflow-cancellation-test
  (:require
   [clojure.test :refer [deftest is]]
   [psi.agent-session.core :as session]
   [psi.agent-session.dispatch-effects :as dispatch-effects]
   [psi.agent-session.dispatch-schema :as dispatch-schema]
   [psi.agent-session.test-support :as test-support]
   [psi.agent-session.turn.handlers :as turn-handlers]
   [psi.memory.runtime :as memory-runtime]
   [psi.session-persistence.core :as persist]
   [psi.session-state.state :as ss]
   [psi.state-kernel.dispatch :as kernel]
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

(defn- workflow-attempt-session!
  [ctx session-id run-id]
  (swap! (:state* ctx)
         (fn [state]
           (-> state
               (assoc-in (ss/session-data-path session-id)
                         (assoc (get-in state (ss/session-data-path session-id))
                                :workflow-owned? true
                                :workflow-run-id run-id
                                :workflow-step-id "plan"
                                :workflow-attempt-id "attempt-plan"))
               (assoc-in [:workflows :runs run-id :current-step-id] "plan")
               (assoc-in [:workflows :runs run-id :status] :running)
               (assoc-in [:workflows :runs run-id :step-runs "plan" :attempts]
                         [{:attempt-id "attempt-plan"
                           :status :running
                           :execution-session-id session-id}])))))

(defn- journal-messages
  [ctx session-id]
  (->> (ss/get-state-value-in ctx (ss/state-path :journal session-id))
       (filter #(= :message (:kind %)))
       (mapv #(get-in % [:data :message]))))

(deftest workflow-cancel-between-prompt-submit-and-prompt-blocks-streaming-transition-test
  ;; Regression for task 225 pass 19: a workflow-owned prompt lifecycle must not
  ;; transition the child session to streaming when cancellation lands after
  ;; prompt-submit but before :session/prompt.
  (let [[ctx session-id] (create-session-context {:persist? false})
        run-id "run-prompt-submit-to-prompt-cancel"]
    (install-workflow-run! ctx run-id)
    (workflow-attempt-session! ctx session-id run-id)
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
    (workflow-attempt-session! ctx session-id run-id)
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
    (workflow-attempt-session! ctx session-id run-id)
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

(deftest workflow-cancel-after-provider-before-record-response-blocks-recording-and-advancement-test
  ;; Regression for task 225 pass 21: provider execution may have already
  ;; returned when cancellation lands before :session/prompt-record-response.
  ;; Response recording and its ordinary follow-on lifecycle effects must no-op.
  (let [[ctx session-id] (create-session-context {:persist? false})
        run-id "run-provider-to-record-cancel"
        assistant-msg {:role "assistant"
                       :content [{:type :text :text "must not record"}]
                       :stop-reason :stop
                       :timestamp (java.time.Instant/now)}
        execution-result {:execution-result/turn-id "turn-record-race"
                          :execution-result/session-id session-id
                          :execution-result/assistant-message assistant-msg
                          :execution-result/turn-outcome :turn.outcome/stop
                          :execution-result/tool-calls []
                          :execution-result/stop-reason :stop
                          :execution-result/usage {:total-tokens 11}
                          :execution-result/model {:context-window 100}}
        append-effect {:effect/type :runtime/dispatch-event
                       :event-type :session/append-journal-entry
                       :event-data {:session-id session-id
                                    :entry (persist/message-entry assistant-msg)}
                       :origin :core
                       :workflow-run-id run-id}]
    (kernel/clear-event-log!)
    (install-workflow-run! ctx run-id)
    (workflow-attempt-session! ctx session-id run-id)
    (session/dispatch-in! ctx :psi.workflow/cancel-run
                          {:run-id run-id
                           :reason "record response race"}
                          {:origin :core})
    (let [result (session/dispatch-in! ctx :session/prompt-record-response
                                       {:session-id session-id
                                        :execution-result execution-result}
                                       {:origin :core})]
      (is (= {:workflow-stopped? true
              :reason :cancelled
              :session-id session-id}
             result))
      (is (nil? (:last-execution-result-summary (ss/get-session-data-in ctx session-id)))
          "record-response must not write ordinary execution summaries after cancellation")
      (is (= [] (journal-messages ctx session-id))
          "record-response must not append an assistant journal entry after cancellation")
      (let [entries (kernel/event-log-entries)]
        (is (not-any? #(contains? #{:session/prompt-continue
                                    :session/prompt-finish
                                    :session/update-context-usage}
                                  (:event-type %))
                      entries)
            "record-response must not enqueue ordinary lifecycle advancement after cancellation")))
    (let [entry-count-before-stale-effects (count (kernel/event-log-entries))]
      (dispatch-effects/execute-effect! ctx append-effect)
      (dispatch-effects/execute-effect!
       ctx
       {:effect/type :runtime/dispatch-event
        :event-type :session/prompt-finish
        :event-data {:session-id session-id
                     :turn-id (:execution-result/turn-id execution-result)
                     :terminal-result execution-result}
        :origin :core
        :workflow-run-id run-id})
      (is (= [] (journal-messages ctx session-id))
          "stale workflow-guarded dispatch effects must no-op after cancellation")
      (is (= entry-count-before-stale-effects (count (kernel/event-log-entries)))
          "stale guarded dispatch effects must not re-enter ordinary prompt lifecycle events"))))

(deftest workflow-cancel-after-record-response-build-before-apply-blocks-stale-recording-effects-test
  ;; Regression for the record-response pure-result application window: if a
  ;; live handler already built ordinary summary/journal/advancement effects,
  ;; cancellation before apply/effects must make that stale pure result harmless.
  (let [[ctx session-id] (create-session-context {:persist? false})
        run-id "run-record-built-then-cancelled"
        assistant-msg {:role "assistant"
                       :content [{:type :text :text "stale response"}]
                       :stop-reason :stop
                       :timestamp (java.time.Instant/now)}
        execution-result {:execution-result/turn-id "turn-built-race"
                          :execution-result/session-id session-id
                          :execution-result/assistant-message assistant-msg
                          :execution-result/turn-outcome :turn.outcome/stop
                          :execution-result/tool-calls []
                          :execution-result/stop-reason :stop}
        _ (install-workflow-run! ctx run-id)
        _ (workflow-attempt-session! ctx session-id run-id)
        handler-result ((:fn (kernel/handler-entry :session/prompt-record-response))
                        ctx
                        {:session-id session-id
                         :execution-result execution-result})]
    (is (fn? (:root-state-update handler-result))
        "live record-response handler builds a summary root update")
    (is (every? #(= run-id (:workflow-run-id %)) (:effects handler-result))
        "live record-response effects carry the workflow guard")
    (session/dispatch-in! ctx :psi.workflow/cancel-run
                          {:run-id run-id
                           :reason "record result built before cancel"}
                          {:origin :core})
    (kernel/clear-event-log!)
    (ss/apply-root-state-update-in! ctx (:root-state-update handler-result))
    (doseq [effect (:effects handler-result)]
      (dispatch-effects/execute-effect! ctx effect))
    (is (nil? (:last-execution-result-summary (ss/get-session-data-in ctx session-id)))
        "stale record-response root update must no-op after cancellation")
    (is (= [] (journal-messages ctx session-id))
        "stale response append effect must no-op after cancellation")
    (is (empty? (kernel/event-log-entries))
        "stale guarded advancement effects must not dispatch after cancellation")))
