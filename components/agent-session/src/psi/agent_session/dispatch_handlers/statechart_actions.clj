(ns psi.agent-session.dispatch-handlers.statechart-actions
  "Handlers for statechart action events.

   Provider request retry is now owned by turn-runtime at the prepared request
   boundary. The legacy retry-triggered/resume handlers remain registered as
   compatibility no-ops so stale or historical statechart events cannot replay
   the whole agent loop."
  (:require
   [psi.workflow-coordination.stop-signal :as stop-signal]
   [psi.agent-session.extensions :as ext]
   [psi.session-state.model :as session-model]
   [psi.state-kernel.dispatch :as kernel]
   [psi.session-state.state :as session]))

;;; Thread utilities

(defn daemon-thread
  "Start a daemon thread running f. Returns the Thread."
  [f]
  (doto (Thread. ^Runnable f)
    (.setDaemon true)
    (.start)))

;;; Auto-compaction helpers

(defn- last-assistant-message-from-event [event]
  (let [m (last (:messages event))]
    (when (= "assistant" (:role m))
      m)))

(defn- overflow-error-assistant? [msg]
  (let [stop-reason (or (:stop-reason msg) (:stopReason msg))]
    (and (map? msg)
         (or (= :error stop-reason) (= "error" stop-reason))
         (session-model/context-overflow-error? (:error-message msg)))))

(defn- threshold-auto-compact? [session-data config]
  (let [tokens  (:context-tokens session-data)
        window  (:context-window session-data)
        reserve (long (or (:auto-compaction-reserve-tokens config) 16384))
        cutoff  (when (and (number? window) (pos? window))
                  (max 0 (- window reserve)))]
    (and (number? tokens)
         (number? cutoff)
         (> tokens cutoff))))

(defn- auto-compaction-reason
  "Return :overflow, :threshold, or nil from statechart working-memory `data`."
  [session-id data]
  (let [ctx    (:ctx data)
        sd     (session/get-session-data-in ctx session-id)
        config (:config data)
        event  (:pending-agent-event data)
        last-m (last-assistant-message-from-event event)]
    (cond
      (and (:auto-compaction-enabled sd)
           (overflow-error-assistant? last-m))
      :overflow

      (and (:auto-compaction-enabled sd)
           (threshold-auto-compact? sd config)
           (let [stop-reason (or (:stop-reason last-m) (:stopReason last-m))]
             (not (or (= :error stop-reason) (= "error" stop-reason)))))
      :threshold

      :else nil)))

;;; Registration

(defn- provider-id-for
  [model]
  (or (some-> model :provider name)
      (some-> model :provider str)
      "unknown"))

(defn- model-id-for
  [model]
  (or (:id model) "unknown"))

(defn- terminal-provider-error-event?
  [pending-agent-event]
  (let [assistant-msg (last-assistant-message-from-event pending-agent-event)
        stop-reason   (or (:stop-reason assistant-msg) (:stopReason assistant-msg))]
    (and (= :agent-end (:type pending-agent-event))
         (map? assistant-msg)
         (not (:retry/outcome assistant-msg))
         (or (= :error stop-reason) (= "error" stop-reason))
         (or (:provider-error/headers pending-agent-event)
             (:error-message assistant-msg)
             (:http-status assistant-msg)))))

(defn- dispatch-provider-event!
  [ctx event-name payload]
  (when-let [reg (:extension-registry ctx)]
    (ext/dispatch-in reg event-name (assoc payload :type event-name))))

(defn- failed-provider-event-payload
  [ctx session-id pending-agent-event retry-attempt final?]
  (let [sd            (session/get-session-data-in ctx session-id)
        model         (:model sd)
        assistant-msg (last-assistant-message-from-event pending-agent-event)
        stop-reason   (or (:stop-reason assistant-msg) (:stopReason assistant-msg))
        error-message (:error-message assistant-msg)
        http-status   (:http-status assistant-msg)]
    {:session-id session-id
     :turn-id (:turn-id (or (:last-execution-result-summary sd)
                            (:last-prepared-request-summary sd)))
     :attempt-id (:turn-id (or (:last-execution-result-summary sd)
                               (:last-prepared-request-summary sd)))
     :provider (provider-id-for model)
     :model-id (model-id-for model)
     :retry-attempt retry-attempt
     :status :failed
     :final? final?
     :retryable? (session-model/retry-error? stop-reason error-message http-status)
     :error-kind (session-model/provider-error-kind stop-reason error-message http-status)
     :stop-reason stop-reason
     :error-message error-message}))

(defn- workflow-run-stop-signal
  [ctx run-id]
  (stop-signal/workflow-stop-signal ctx run-id))

(defn- workflow-session-run-id
  [session-data event-data]
  (or (:workflow-run-id event-data)
      (when (:workflow-owned? session-data)
        (:workflow-run-id session-data))))

(defn- workflow-effect
  [effect run-id]
  (cond-> effect
    run-id (assoc :workflow-run-id run-id)))

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

(defn- workflow-stopped-result
  [session-id reason]
  {:return {:workflow-stopped? true
            :reason reason
            :session-id session-id}})

(defn register!
  "Register all statechart action handlers.
   Called once during context creation. Handlers are context-independent."
  [_ctx]
  (kernel/register-handler!
   :on-streaming-entered
   (fn [_ctx {:keys [session-id]}]
     {:root-state-update (session/session-update session-id #(assoc % :is-streaming true))}))

  (kernel/register-handler!
   :on-agent-done
   (fn [ctx {:keys [session-id] :as data}]
     (let [sd                  (session/get-session-data-in ctx session-id)
           run-id              (workflow-session-run-id sd data)
           stop-reason         (workflow-run-stop-signal ctx run-id)
           interruption-reason (:interrupt-reason sd)
           pending-agent-event (:pending-agent-event data)]
       (if stop-reason
         (workflow-stopped-result session-id stop-reason)
         (do
           (when (terminal-provider-error-event? pending-agent-event)
             (dispatch-provider-event!
              ctx
              "provider_request_finished"
              (failed-provider-event-payload ctx session-id pending-agent-event (:retry-attempt sd) true)))
           {:root-state-update (guard-workflow-root-update
                                (session/session-update session-id #(assoc % :is-streaming false
                                                                           :retry-attempt 0
                                                                           :retry nil
                                                                           :interrupt-pending false
                                                                           :interrupt-requested-at nil
                                                                           :interrupt-reason nil))
                                run-id)
            :effects (cond-> [(workflow-effect {:effect/type :runtime/mark-workflow-jobs-terminal} run-id)
                              (workflow-effect {:effect/type :runtime/emit-background-job-terminal-messages} run-id)
                              (workflow-effect {:effect/type :scheduler/drain-queue} run-id)]
                       interruption-reason
                       (into [(workflow-effect {:effect/type :runtime/record-pending-tool-call-interrupts
                                                :session-id session-id
                                                :reason interruption-reason}
                                               run-id)]))})))))

  (kernel/register-handler!
   :on-abort
   (fn [_ctx {:keys [session-id]}]
     {:root-state-update (session/session-update session-id #(assoc % :is-streaming false
                                                                    :retry nil
                                                                    :interrupt-pending false
                                                                    :interrupt-requested-at nil
                                                                    :interrupt-reason nil))
      :effects [{:effect/type :runtime/agent-abort}
                {:effect/type :scheduler/drain-queue}]}))

  (kernel/register-handler!
   :on-auto-compact-triggered
   (fn [_ctx {:keys [session-id] :as data}]
     (let [reason      (or (auto-compaction-reason session-id data) :threshold)
           will-retry? (= :overflow reason)]
       {:root-state-update (session/session-update session-id #(assoc % :is-compacting true))
        :effects [{:effect/type :runtime/auto-compact-workflow
                   :reason      reason
                   :will-retry? will-retry?}]})))

  (kernel/register-handler!
   :on-compacting-entered
   (fn [_ctx {:keys [session-id]}]
     {:root-state-update (session/session-update session-id #(assoc % :is-compacting true))}))

  (kernel/register-handler!
   :on-compact-done
   (fn [_ctx {:keys [session-id]}]
     {:root-state-update (session/session-update session-id #(assoc % :is-compacting false
                                                                    :retry nil))
      :effects [{:effect/type :scheduler/drain-queue}]}))

  (kernel/register-handler!
   :on-retry-triggered
   (fn [_ctx {:keys [session-id]}]
     {:root-state-update (session/session-update session-id #(assoc % :retry nil))
      :effects []}))

  (kernel/register-handler!
   :on-retrying-entered
   (fn [_ctx _data]
     {:effects []}))

  (kernel/register-handler!
   :on-retry-resume
   (fn [_ctx {:keys [session-id]}]
     {:root-state-update (session/session-update session-id #(assoc % :retry nil))
      :effects []})))
