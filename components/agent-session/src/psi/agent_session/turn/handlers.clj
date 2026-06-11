(ns psi.agent-session.turn.handlers
  "Thin turn-lifecycle handler helpers.

   This namespace owns the turn lifecycle dispatch orchestration used by
   `dispatch-handlers.prompt-lifecycle`, leaving that namespace as registration
   and adaptation only."
  (:require
   [clojure.string :as str]
   [psi.agent-core.core :as agent]
   [psi.command-registry.registry :as command-registry]
   [psi.session-state.state :as session]
   [psi.turn-runtime.recording :as turn-recording]
   [psi.turn-runtime.request :as turn-request]
   [psi.workflow-runtime.cancellation-entry :as cancellation-entry]))

(defn- now-inst []
  (java.time.Instant/now))

(defn- follow-up-text->message
  [text]
  {:role "user"
   :content [{:type :text :text text}]
   :timestamp (java.time.Instant/now)})

(defn queued-follow-up-batch
  [ctx session-id]
  (let [sd           (session/get-session-data-in ctx session-id)
        agent-ctx    (session/agent-ctx-in ctx session-id)
        follow-mode  (or (some-> agent-ctx agent/get-data-in :follow-up-mode)
                         :one-at-a-time)
        texts        (->> (:follow-up-messages sd)
                          (filter string?)
                          (map str/trim)
                          (remove str/blank?)
                          vec)
        batch-texts  (case follow-mode
                       :all texts
                       :one-at-a-time (vec (take 1 texts))
                       (vec (take 1 texts)))
        messages     (mapv follow-up-text->message batch-texts)]
    (when (seq messages)
      {:texts         batch-texts
       :messages      messages
       :consume-count (count messages)
       :follow-mode   follow-mode})))

(defn synthetic-user-prompt-effects
  [session-id user-msg]
  [{:effect/type :runtime/dispatch-event-with-effect-result
    :event-type :session/prompt-submit
    :event-data {:session-id session-id
                 :user-msg user-msg}
    :origin :core}
   {:effect/type :runtime/dispatch-event
    :event-type :session/prompt
    :event-data {:session-id session-id}
    :origin :core}
   {:effect/type :runtime/dispatch-event-with-effect-result
    :event-type :session/prompt-prepare-request
    :event-data {:session-id session-id
                 :turn-id (str (java.util.UUID/randomUUID))
                 :user-msg user-msg}
    :origin :core}])

(defn prepared-request-state-summary
  [turn-id prepared-request]
  {:turn-id             turn-id
   :system-prompt-chars (count (or (:prepared-request/system-prompt prepared-request) ""))
   :message-count       (count (:prepared-request/messages prepared-request))
   :tool-count          (count (:prepared-request/tools prepared-request))
   :cache-breakpoints   (get-in prepared-request [:prepared-request/session-snapshot :cache-breakpoints])
   :input-expansion     (:prepared-request/input-expansion prepared-request)
   :prepared-at         (now-inst)})

(defn prepared-request-query-text
  [prepared-request]
  (turn-request/prepared-request-query-text prepared-request))

(defn- with-workflow-guard
  [effect workflow-run-id]
  (cond-> effect
    workflow-run-id (assoc :workflow-run-id workflow-run-id)))

(defn prompt-prepare-request-effects
  [prepared-request progress-queue steering-consumed? return-execution-result? workflow-run-id]
  (let [guard #(with-workflow-guard % workflow-run-id)]
    (cond-> (vec (remove nil?
                         [(if return-execution-result?
                            (guard {:effect/type      :runtime/recover-query-prompt-execute-and-record
                                    :query-text       (prepared-request-query-text prepared-request)
                                    :prepared-request prepared-request
                                    :progress-queue   progress-queue})
                            (guard {:effect/type :memory/recover-query
                                    :query-text (prepared-request-query-text prepared-request)}))
                          (when-not return-execution-result?
                            (guard {:effect/type      :runtime/prompt-execute-and-record
                                    :prepared-request prepared-request
                                    :progress-queue   progress-queue}))]))
      steering-consumed?
      (conj (guard {:effect/type :runtime/agent-clear-steering-queue})))))

(defn- workflow-session-stop-signal
  [ctx session-id]
  (let [session-data (session/get-session-data-in ctx session-id)
        run-id (:workflow-run-id session-data)
        state* (:state* ctx)
        run (when (and state* run-id)
              (get-in @state* [:workflows :runs run-id]))]
    (when (and (:workflow-owned? session-data) state* run-id)
      (cond
        (nil? run) :removed
        (= :cancelled (:status run)) :cancelled))))

(defn- stopped-workflow-execution-result
  ([session-id reason]
   (stopped-workflow-execution-result session-id reason "request preparation"))
  ([session-id reason phase]
   (let [message (str "Workflow execution stopped before " phase)]
     {:execution-result/session-id session-id
      :execution-result/assistant-message {:role "assistant"
                                           :content [{:type :error
                                                      :text message}]
                                           :stop-reason :error
                                           :error-message message
                                           :workflow-stop-reason reason}
      :execution-result/turn-outcome :turn.outcome/error
      :execution-result/tool-calls []
      :execution-result/error-message message
      :execution-result/stop-reason :error})))

(defn- stopped-workflow-prepare-result
  [session-id return-execution-result? reason]
  (cond-> {:return-effect-result? true
           :return {:workflow-stopped? true
                    :reason reason
                    :session-id session-id}}
    return-execution-result?
    (assoc :return (stopped-workflow-execution-result session-id reason))))

(defn prompt-prepare-request-handler
  [ctx {:keys [session-id turn-id user-msg runtime-opts progress-queue return-execution-result?]}]
  (let [session-data (session/get-session-data-in ctx session-id)
        run-id (:workflow-run-id session-data)]
    (cancellation-entry/with-run-read-lock
      ctx
      run-id
      (fn []
        (if-let [reason (workflow-session-stop-signal ctx session-id)]
          (stopped-workflow-prepare-result session-id return-execution-result? reason)
          (let [prepared-request   ((:build-prepared-request-fn ctx)
                                    ctx session-id {:turn-id turn-id
                                                    :user-message user-msg
                                                    :runtime-opts runtime-opts
                                                    :commands (command-registry/command-names-in (:extension-registry ctx))})
                api-key            (get-in prepared-request [:prepared-request/ai-options :api-key])
                steering-consumed? (seq (:prepared-request/queued-steering-messages prepared-request))]
            (cond-> {:root-state-update
                     (session/session-update
                      session-id
                      #(cond-> (assoc % :last-prepared-request-summary
                                      (prepared-request-state-summary turn-id prepared-request))
                         api-key            (assoc :runtime-api-key api-key)
                         steering-consumed? (assoc :steering-messages [])))
                     :effects (prompt-prepare-request-effects prepared-request progress-queue steering-consumed? return-execution-result? run-id)
                     :return-effect-result? true}
              (not return-execution-result?)
              (assoc :return {:prepared-request prepared-request}))))))))

(defn execution-usage-tokens
  [execution-result]
  (turn-recording/execution-usage-tokens execution-result))

(defn prompt-record-next-payload
  [session-id execution-result progress-queue next-event]
  (cond-> {:session-id session-id
           :execution-result execution-result
           :progress-queue progress-queue}
    (= next-event :session/prompt-finish)
    (assoc :turn-id (:execution-result/turn-id execution-result)
           :terminal-result execution-result)))

(defn prompt-record-next-event-effect
  [next-event next-payload workflow-run-id]
  (cond-> {:effect/type :runtime/dispatch-event
           :event-type next-event
           :event-data next-payload
           :origin :core}
    workflow-run-id (assoc :workflow-run-id workflow-run-id)))

(defn prompt-record-context-usage-effect
  [session-id tokens window workflow-run-id]
  (cond-> {:effect/type :runtime/dispatch-event
           :event-type :session/update-context-usage
           :event-data {:session-id session-id
                        :tokens tokens
                        :window window}
           :origin :core}
    workflow-run-id (assoc :workflow-run-id workflow-run-id)))

(defn- stopped-workflow-record-response-result
  [session-id reason]
  {:return {:workflow-stopped? true
            :reason reason
            :session-id session-id}
   :return-effect-result? true})

(defn- live-workflow-run-in-state?
  [state-map run-id]
  (let [run (get-in state-map [:workflows :runs run-id])]
    (and run (not= :cancelled (:status run)))))

(defn- guard-workflow-root-update
  [root-state-update run-id]
  (if-not (and root-state-update run-id)
    root-state-update
    (fn [state-map]
      (if (live-workflow-run-in-state? state-map run-id)
        (root-state-update state-map)
        state-map))))

(defn prompt-record-response-handler
  [ctx {:keys [session-id execution-result progress-queue]}]
  (let [session-data (session/get-session-data-in ctx session-id)
        run-id (:workflow-run-id session-data)]
    (cancellation-entry/with-run-read-lock
      ctx
      run-id
      (fn []
        (if-let [reason (workflow-session-stop-signal ctx session-id)]
          (stopped-workflow-record-response-result session-id reason)
          (let [result       ((:build-record-response-fn ctx) session-id execution-result progress-queue run-id)
                next-event   (get-in result [:return :next-event])
                next-payload (prompt-record-next-payload session-id execution-result progress-queue next-event)
                tokens       (execution-usage-tokens execution-result)
                sd           (when tokens session-data)
                window       (or (some-> execution-result :execution-result/model :context-window)
                                 (when sd (:context-window sd)))]
            (cond-> result
              (:root-state-update result)
              (update :root-state-update guard-workflow-root-update run-id)

              next-event
              (update :effects (fnil conj []) (prompt-record-next-event-effect next-event next-payload run-id))
              (and tokens (number? window) (pos? window))
              (update :effects (fnil conj []) (prompt-record-context-usage-effect session-id tokens window run-id)))))))))

(defn prompt-continue-handler
  [ctx {:keys [session-id execution-result progress-queue]}]
  (let [turn-id (str (java.util.UUID/randomUUID))
        run-id  (:workflow-run-id (session/get-session-data-in ctx session-id))
        guard   #(with-workflow-guard % run-id)]
    {:effects [(guard {:effect/type :runtime/prompt-continue-chain
                       :execution-result execution-result
                       :progress-queue progress-queue})
               (guard {:effect/type :runtime/dispatch-event-with-effect-result
                       :event-type :session/prompt-prepare-request
                       :event-data {:session-id session-id
                                    :turn-id turn-id
                                    :user-msg nil
                                    :progress-queue progress-queue}
                       :origin :core})
               (guard {:effect/type :runtime/reconcile-and-emit-background-job-terminals})]
     :return-effect-result? true
     :return {:continued? true
              :next-turn-id turn-id
              :turn-outcome (:execution-result/turn-outcome execution-result)}}))

(defn prompt-finish-base-result
  ([session-id turn-id terminal-result next-turn-id follow-up-msg follow-up-batch]
   (prompt-finish-base-result session-id turn-id terminal-result next-turn-id follow-up-msg follow-up-batch nil))
  ([session-id turn-id terminal-result next-turn-id follow-up-msg follow-up-batch workflow-run-id]
   (let [guard #(with-workflow-guard % workflow-run-id)]
     {:effects [(guard {:effect/type :runtime/dispatch-event
                        :event-type :on-agent-done
                        :event-data {:session-id session-id
                                     :pending-agent-event {:type :agent-end
                                                           :messages [(:execution-result/assistant-message terminal-result)]
                                                           :turn-id turn-id
                                                           :provider-error/headers (get-in terminal-result [:execution-result/assistant-message :provider-error/headers])}}
                        :origin :core})
                (guard {:effect/type :notify/extension-dispatch
                        :event-name "session_turn_finished"
                        :payload (cond-> {:session-id session-id
                                          :turn-id turn-id}
                                   (seq (:execution-result/logprobs terminal-result))
                                   (assoc :logprobs (:execution-result/logprobs terminal-result))

                                   (:execution-result/assistant-message terminal-result)
                                   (assoc :assistant-message (:execution-result/assistant-message terminal-result)))})
                (guard {:effect/type :runtime/reconcile-and-emit-background-job-terminals})
                (guard {:effect/type :statechart/send-event
                        :event :session/reset})]
      :return {:finished? true
               :turn-id turn-id
               :next-turn-id next-turn-id
               :turn-outcome (:execution-result/turn-outcome terminal-result)
               :follow-up-triggered? (boolean follow-up-msg)
               :follow-up-count (or (:consume-count follow-up-batch) 0)}})))

(defn consume-follow-up-state-update
  [session-id follow-up-batch]
  (session/session-update
   session-id
   #(update % :follow-up-messages
            (fn [xs]
              (vec (drop (:consume-count follow-up-batch) (or xs [])))))))

(defn prompt-finish-follow-up-effects
  ([session-id follow-up-batch follow-up-msg]
   (prompt-finish-follow-up-effects session-id follow-up-batch follow-up-msg nil))
  ([session-id follow-up-batch follow-up-msg workflow-run-id]
   (let [guard #(with-workflow-guard % workflow-run-id)]
     [(guard {:effect/type :runtime/agent-drain-follow-up-queue
              :messages (:messages follow-up-batch)})
      (guard {:effect/type :runtime/dispatch-event-with-effect-result
              :event-type :session/submit-synthetic-user-prompt
              :event-data {:session-id session-id
                           :user-msg follow-up-msg}
              :origin :core})])))

(defn prompt-finish-handler
  [ctx {:keys [session-id turn-id terminal-result]}]
  (let [run-id          (:workflow-run-id (session/get-session-data-in ctx session-id))
        follow-up-batch (queued-follow-up-batch ctx session-id)
        follow-up-msg   (first (:messages follow-up-batch))
        next-turn-id    (when follow-up-msg (str (java.util.UUID/randomUUID)))]
    (cond-> (prompt-finish-base-result session-id turn-id terminal-result next-turn-id follow-up-msg follow-up-batch run-id)
      follow-up-batch
      (assoc :root-state-update (guard-workflow-root-update
                                 (consume-follow-up-state-update session-id follow-up-batch)
                                 run-id))
      follow-up-msg
      (update :effects into (prompt-finish-follow-up-effects session-id follow-up-batch follow-up-msg run-id)))))

(defn prompt-execute-handler
  [_ctx {:keys [user-msg]}]
  {:effects [{:effect/type :runtime/agent-start-loop-with-messages
              :messages [user-msg]}
             {:effect/type :runtime/reconcile-and-emit-background-job-terminals}]})
