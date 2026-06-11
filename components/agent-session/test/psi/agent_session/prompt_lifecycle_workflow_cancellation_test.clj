(ns psi.agent-session.prompt-lifecycle-workflow-cancellation-test
  (:require
   [clojure.test :refer [deftest is]]
   [psi.agent-session.core :as session]
   [psi.agent-session.dispatch-effects :as dispatch-effects]
   [psi.agent-session.dispatch-schema :as dispatch-schema]
   [psi.agent-session.test-support :as test-support]
   [psi.agent-session.turn.handlers :as turn-handlers]
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
                   :memory-recover-query-fn
                   (fn [query-text]
                     (swap! memory-calls* conj query-text))
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
    (dispatch-effects/execute-effect! ctx (first effects))
    (is (= ["recover this"] @memory-calls*)
        "nullable memory recovery seam represents ordinary memory work while the workflow run is live")
    (reset! memory-calls* [])
    (session/dispatch-in! ctx :psi.workflow/cancel-run
                          {:run-id run-id
                           :reason "prepare effects race"}
                          {:origin :core})
    (doseq [effect effects]
      (dispatch-effects/execute-effect! ctx effect))
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

(deftest workflow-cancel-after-prompt-continue-build-before-effects-blocks-stale-continuation-test
  ;; Regression for task 225 pass 22: if prompt-continue has already built its
  ;; pure result, cancellation before effects must prevent continuation-chain
  ;; work and the follow-on prepare dispatch.
  (let [[ctx0 session-id] (create-session-context {:persist? false})
        run-id "run-prompt-continue-built-then-cancelled"
        continue-calls* (atom [])
        ctx (assoc ctx0
                   :continue-prompt-chain-fn
                   (fn [_ctx sid execution-result progress-queue]
                     (swap! continue-calls* conj {:session-id sid
                                                  :execution-result execution-result
                                                  :progress-queue progress-queue})))
        execution-result {:execution-result/turn-id "turn-continue-race"
                          :execution-result/session-id session-id
                          :execution-result/assistant-message {:role "assistant"
                                                               :content [{:type :text :text "continue"}]
                                                               :stop-reason :tool_use}
                          :execution-result/turn-outcome :turn.outcome/tool-use}
        _ (install-workflow-run! ctx run-id)
        _ (workflow-attempt-session! ctx session-id run-id)
        handler-result ((:fn (kernel/handler-entry :session/prompt-continue))
                        ctx
                        {:session-id session-id
                         :execution-result execution-result
                         :progress-queue :progress})]
    (is (= [:runtime/prompt-continue-chain
            :runtime/dispatch-event-with-effect-result
            :runtime/reconcile-and-emit-background-job-terminals]
           (mapv :effect/type (:effects handler-result)))
        "regression must exercise prompt-continue's ordinary effect vector")
    (is (every? #(= run-id (:workflow-run-id %)) (:effects handler-result))
        "workflow-owned prompt-continue effects carry the workflow guard")
    (is (every? dispatch-schema/valid-effect? (:effects handler-result))
        "workflow-guarded prompt-continue effects must remain valid")
    (session/dispatch-in! ctx :psi.workflow/cancel-run
                          {:run-id run-id
                           :reason "continue built before cancel"}
                          {:origin :core})
    (kernel/clear-event-log!)
    (doseq [effect (:effects handler-result)]
      (dispatch-effects/execute-effect! ctx (assoc effect :session-id session-id)))
    (is (= [] @continue-calls*)
        "stale prompt-continue-chain effect must no-op after cancellation")
    (is (empty? (kernel/event-log-entries))
        "stale prompt-continue follow-on dispatch effects must not re-enter ordinary lifecycle events")))

(deftest workflow-cancel-after-prompt-finish-build-before-apply-blocks-stale-finish-effects-test
  ;; Regression for task 225 pass 22: if prompt-finish has already built its pure
  ;; result, cancellation before apply/effects must prevent terminal UI/extension
  ;; effects, session reset, follow-up draining, and follow-up prompt dispatch.
  (let [[ctx0 session-id] (create-session-context {:persist? false})
        run-id "run-prompt-finish-built-then-cancelled"
        extension-events* (atom [])
        reconcile-calls* (atom 0)
        ctx (assoc ctx0
                   :reconcile-and-emit-background-job-terminals-fn
                   (fn [& _] (swap! reconcile-calls* inc))
                   :extension-registry {:dispatch-fn (fn [& args]
                                                       (swap! extension-events* conj args))})
        terminal-result {:execution-result/turn-id "turn-finish-race"
                         :execution-result/session-id session-id
                         :execution-result/assistant-message {:role "assistant"
                                                              :content [{:type :text :text "done"}]
                                                              :stop-reason :stop}
                         :execution-result/turn-outcome :turn.outcome/stop}
        _ (install-workflow-run! ctx run-id)
        _ (workflow-attempt-session! ctx session-id run-id)
        _ (swap! (:state* ctx) assoc-in (conj (ss/session-data-path session-id) :follow-up-messages)
                 ["follow up" "later"])
        handler-result ((:fn (kernel/handler-entry :session/prompt-finish))
                        ctx
                        {:session-id session-id
                         :turn-id (:execution-result/turn-id terminal-result)
                         :terminal-result terminal-result})]
    (is (= [:runtime/dispatch-event
            :notify/extension-dispatch
            :runtime/reconcile-and-emit-background-job-terminals
            :statechart/send-event
            :runtime/agent-drain-follow-up-queue
            :runtime/dispatch-event-with-effect-result]
           (mapv :effect/type (:effects handler-result)))
        "regression must exercise prompt-finish terminal and follow-up effects")
    (is (fn? (:root-state-update handler-result))
        "live prompt-finish with follow-up builds a follow-up consumption root update")
    (is (every? #(= run-id (:workflow-run-id %)) (:effects handler-result))
        "workflow-owned prompt-finish effects carry the workflow guard")
    (is (every? dispatch-schema/valid-effect? (:effects handler-result))
        "workflow-guarded prompt-finish effects must remain valid")
    (session/dispatch-in! ctx :psi.workflow/cancel-run
                          {:run-id run-id
                           :reason "finish built before cancel"}
                          {:origin :core})
    (kernel/clear-event-log!)
    (ss/apply-root-state-update-in! ctx (:root-state-update handler-result))
    (doseq [effect (:effects handler-result)]
      (dispatch-effects/execute-effect! ctx (assoc effect :session-id session-id)))
    (is (= ["follow up" "later"]
           (:follow-up-messages (ss/get-session-data-in ctx session-id)))
        "stale prompt-finish root update must not consume follow-up state after cancellation")
    (is (= 0 @reconcile-calls*)
        "stale terminal reconciliation effect must no-op after cancellation")
    (is (= [] @extension-events*)
        "stale extension turn-finished notification must no-op after cancellation")
    (is (= :idle (ss/sc-phase-in ctx session-id))
        "stale finish reset must not mutate the session statechart after cancellation")
    (is (empty? (kernel/event-log-entries))
        "stale prompt-finish dispatch effects must not emit terminal notifications or follow-up prompts")))

(deftest workflow-cancel-after-on-agent-done-build-before-apply-blocks-stale-terminal-effects-test
  ;; Regression for task 225 pass 23: prompt-finish's guarded dispatch effect may
  ;; have already admitted :on-agent-done and built that handler's pure result
  ;; before D31 cancellation lands. The stale result must not clear terminal
  ;; session state or run ordinary terminal side effects after cancellation.
  (let [[ctx0 session-id] (create-session-context {:persist? false})
        run-id "run-on-agent-done-built-then-cancelled"
        terminal-calls* (atom 0)
        terminal-messages* (atom 0)
        ctx (assoc ctx0
                   :mark-workflow-jobs-terminal-fn (fn [& _] (swap! terminal-calls* inc))
                   :emit-background-job-terminal-messages-fn (fn [& _] (swap! terminal-messages* inc))
                   :scheduler-timers* (atom {}))
        pending-event {:type :agent-end
                       :messages [{:role "assistant"
                                   :content [{:type :text :text "done"}]
                                   :stop-reason :stop}]
                       :turn-id "turn-on-agent-done-race"}
        _ (install-workflow-run! ctx run-id)
        _ (workflow-attempt-session! ctx session-id run-id)
        _ (swap! (:state* ctx)
                 update-in
                 (ss/session-data-path session-id)
                 assoc
                 :is-streaming true
                 :retry-attempt 2
                 :retry {:delay-ms 1000}
                 :interrupt-pending true)
        handler-result ((:fn (kernel/handler-entry :on-agent-done))
                        ctx
                        {:session-id session-id
                         :workflow-run-id run-id
                         :pending-agent-event pending-event})]
    (is (fn? (:root-state-update handler-result))
        "live on-agent-done builds the ordinary terminal root update")
    (is (= [:runtime/mark-workflow-jobs-terminal
            :runtime/emit-background-job-terminal-messages
            :scheduler/drain-queue]
           (mapv :effect/type (:effects handler-result)))
        "regression must exercise on-agent-done terminal effects")
    (is (every? #(= run-id (:workflow-run-id %)) (:effects handler-result))
        "workflow-owned on-agent-done effects carry the workflow guard")
    (is (every? dispatch-schema/valid-effect? (:effects handler-result))
        "workflow-guarded on-agent-done effects must remain valid")
    (session/dispatch-in! ctx :psi.workflow/cancel-run
                          {:run-id run-id
                           :reason "on-agent-done built before cancel"}
                          {:origin :core})
    (reset! terminal-calls* 0)
    (reset! terminal-messages* 0)
    (kernel/clear-event-log!)
    (ss/apply-root-state-update-in! ctx (:root-state-update handler-result))
    (doseq [effect (:effects handler-result)]
      (dispatch-effects/execute-effect! ctx (assoc effect :session-id session-id)))
    (is (= {:is-streaming true
            :retry-attempt 2
            :retry {:delay-ms 1000}
            :interrupt-pending true}
           (select-keys (ss/get-session-data-in ctx session-id)
                        [:is-streaming :retry-attempt :retry :interrupt-pending]))
        "stale on-agent-done root update must not clear ordinary terminal session state after cancellation")
    (is (= 0 @terminal-calls*)
        "stale on-agent-done must not terminalize background jobs after cancellation")
    (is (= 0 @terminal-messages*)
        "stale on-agent-done must not emit background-job terminal messages after cancellation")
    (is (empty? (kernel/event-log-entries))
        "stale on-agent-done must not drain the scheduler after cancellation")))

(deftest workflow-cancel-after-synthetic-follow-up-build-before-effects-blocks-stale-prompt-dispatch-test
  ;; Regression for task 225 pass 24: prompt-finish's follow-up dispatch may have
  ;; admitted :session/submit-synthetic-user-prompt and built that handler's pure
  ;; result before D31 cancellation lands. The stale synthetic follow-up effects
  ;; must not append the follow-up user message or start the next prompt lifecycle.
  (let [[ctx session-id] (create-session-context {:persist? false})
        run-id "run-synthetic-follow-up-built-then-cancelled"
        user-msg {:role "user"
                  :content [{:type :text :text "follow up"}]
                  :timestamp (java.time.Instant/now)}
        _ (install-workflow-run! ctx run-id)
        _ (workflow-attempt-session! ctx session-id run-id)
        handler-result ((:fn (kernel/handler-entry :session/submit-synthetic-user-prompt))
                        ctx
                        {:session-id session-id
                         :workflow-run-id run-id
                         :user-msg user-msg})]
    (is (= [:runtime/dispatch-event-with-effect-result
            :runtime/dispatch-event
            :runtime/dispatch-event-with-effect-result]
           (mapv :effect/type (:effects handler-result)))
        "regression must exercise the synthetic follow-up prompt lifecycle effects")
    (is (= [:session/prompt-submit
            :session/prompt
            :session/prompt-prepare-request]
           (mapv :event-type (:effects handler-result)))
        "synthetic follow-up handler starts with prompt-submit, prompt, and prepare effects")
    (is (every? #(= run-id (:workflow-run-id %)) (:effects handler-result))
        "workflow-owned synthetic follow-up effects carry the workflow guard")
    (is (every? #(= run-id (get-in % [:event-data :workflow-run-id]))
                (filter #(= :session/prompt-submit (:event-type %)) (:effects handler-result)))
        "prompt-submit receives the workflow guard so stale user-journal append effects are guarded too")
    (is (every? dispatch-schema/valid-effect? (:effects handler-result))
        "workflow-guarded synthetic follow-up effects must remain valid")
    (session/dispatch-in! ctx :psi.workflow/cancel-run
                          {:run-id run-id
                           :reason "synthetic follow-up built before cancel"}
                          {:origin :core})
    (kernel/clear-event-log!)
    (doseq [effect (:effects handler-result)]
      (dispatch-effects/execute-effect! ctx (assoc effect :session-id session-id)))
    (is (= [] (journal-messages ctx session-id))
        "stale synthetic follow-up prompt-submit must not append a user journal entry after cancellation")
    (is (= :idle (ss/sc-phase-in ctx session-id))
        "stale synthetic follow-up prompt must not transition the session into streaming after cancellation")
    (is (nil? (:last-prepared-request-summary (ss/get-session-data-in ctx session-id)))
        "stale synthetic follow-up prepare must not record ordinary request state after cancellation")
    (is (empty? (kernel/event-log-entries))
        "stale synthetic follow-up effects must not re-enter ordinary prompt lifecycle events")))

(deftest workflow-cancel-after-guarded-nested-dispatch-build-before-apply-blocks-stale-nested-results-test
  ;; Regression for task 225 pass 26: a workflow-guarded dispatch effect may
  ;; pass its outer stop check and admit a nested handler while the run is live;
  ;; if cancellation lands before that nested pure result is applied, the nested
  ;; root update and adjacent effects must still no-op.
  (let [[ctx session-id] (create-session-context {:persist? false})
        run-id "run-nested-dispatch-built-then-cancelled"
        file (java.io.File/createTempFile "psi-stale-nested-journal" ".ndedn")
        assistant-msg {:role "assistant"
                       :content [{:type :text :text "must not append"}]
                       :timestamp (java.time.Instant/now)}
        entry (persist/message-entry assistant-msg)
        _ (install-workflow-run! ctx run-id)
        _ (workflow-attempt-session! ctx session-id run-id)
        _ (ss/assoc-state-value-in! ctx (ss/state-path :flush-state session-id)
                                    {:flushed? false :session-file file})
        append-result ((:fn (kernel/handler-entry :session/append-journal-entry))
                       ctx
                       {:session-id session-id
                        :entry entry
                        :workflow-run-id run-id})
        usage-result ((:fn (kernel/handler-entry :session/update-context-usage))
                      ctx
                      {:session-id session-id
                       :tokens 42
                       :window 100
                       :workflow-run-id run-id})]
    (is (fn? (:root-state-update append-result))
        "live append-journal handler builds a root update")
    (is (= [:persist/session-journal-io] (mapv :effect/type (:effects append-result)))
        "live append-journal handler builds the adjacent persistence effect")
    (is (every? #(= run-id (:workflow-run-id %)) (:effects append-result))
        "workflow-owned append-journal effects carry the workflow guard")
    (is (fn? (:root-state-update usage-result))
        "live context-usage handler builds a root update")
    (is (every? dispatch-schema/valid-effect? (:effects append-result))
        "workflow-guarded nested effects must remain valid")
    (session/dispatch-in! ctx :psi.workflow/cancel-run
                          {:run-id run-id
                           :reason "nested dispatch built before cancel"}
                          {:origin :core})
    (ss/apply-root-state-update-in! ctx (:root-state-update append-result))
    (ss/apply-root-state-update-in! ctx (:root-state-update usage-result))
    (doseq [effect (:effects append-result)]
      (dispatch-effects/execute-effect! ctx effect))
    (is (= [] (journal-messages ctx session-id))
        "stale append-journal root update must not append after cancellation")
    (is (nil? (:context-tokens (ss/get-session-data-in ctx session-id)))
        "stale context-usage root update must not record token usage after cancellation")
    (is (nil? (:context-window (ss/get-session-data-in ctx session-id)))
        "stale context-usage root update must not record context window after cancellation")
    (is (false? (:flushed? (ss/get-state-value-in ctx (ss/state-path :flush-state session-id))))
        "stale journal IO effect must not mark flush state after cancellation")))
