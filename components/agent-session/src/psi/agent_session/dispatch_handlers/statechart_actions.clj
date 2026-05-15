(ns psi.agent-session.dispatch-handlers.statechart-actions
  "Handlers for statechart action events:
   on-streaming-entered, on-agent-done, on-abort, on-auto-compact-triggered,
   on-compacting-entered, on-compact-done, on-retry-triggered, on-retrying-entered,
   on-retry-resume."
  (:require
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

(defn- compute-retry-metadata
  [headers attempt exponential-delay-ms now-ms]
  ((requiring-resolve 'psi.session-state.model/retry-metadata)
   headers attempt exponential-delay-ms now-ms))

(defn- retry-metadata-for
  [ctx sd event]
  (let [attempt              (:retry-attempt sd)
        base-ms              (get-in ctx [:config :auto-retry-base-delay-ms] 2000)
        max-ms               (get-in ctx [:config :auto-retry-max-delay-ms] 60000)
        exponential-delay-ms (session-model/exponential-backoff-ms attempt base-ms max-ms)
        now-fn               (or (:now-fn ctx) #(java.time.Instant/now))
        now-ms               (.toEpochMilli ^java.time.Instant (now-fn))
        provider-headers     (:provider-error/headers event)]
    (compute-retry-metadata provider-headers attempt exponential-delay-ms now-ms)))

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
           interruption-reason (:interrupt-reason sd)
           pending-agent-event (:pending-agent-event data)]
       (when (terminal-provider-error-event? pending-agent-event)
         (dispatch-provider-event!
          ctx
          "provider_request_finished"
          (failed-provider-event-payload ctx session-id pending-agent-event (:retry-attempt sd) true)))
       {:root-state-update (session/session-update session-id #(assoc % :is-streaming false
                                                                      :retry-attempt 0
                                                                      :retry nil
                                                                      :interrupt-pending false
                                                                      :interrupt-requested-at nil
                                                                      :interrupt-reason nil))
        :effects (cond-> [{:effect/type :runtime/mark-workflow-jobs-terminal}
                          {:effect/type :runtime/emit-background-job-terminal-messages}
                          {:effect/type :scheduler/drain-queue}]
                   interruption-reason
                   (into [{:effect/type :runtime/record-pending-tool-call-interrupts
                           :session-id session-id
                           :reason interruption-reason}]))})))

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
   (fn [ctx {:keys [session-id] :as data}]
     (let [sd                 (session/get-session-data-in ctx session-id)
           pending-agent-event (:pending-agent-event data)
           retry-metadata     (retry-metadata-for ctx sd pending-agent-event)
           retry-attempt      (inc (:retry-attempt sd))
           provider-event     (failed-provider-event-payload ctx session-id pending-agent-event (:retry-attempt sd) false)
           model              (:model sd)]
       (dispatch-provider-event! ctx "provider_request_finished" provider-event)
       (dispatch-provider-event!
        ctx
        "provider_retry_scheduled"
        {:session-id session-id
         :turn-id (:turn-id provider-event)
         :provider (provider-id-for model)
         :model-id (model-id-for model)
         :retry-attempt retry-attempt
         :delay-ms (:delay-ms retry-metadata)
         :delay-source (:delay-source retry-metadata)
         :error-kind (:error-kind provider-event)
         :retryable? true})
       {:root-state-update (session/session-update session-id #(-> %
                                                                   (update :retry-attempt inc)
                                                                   (assoc :retry retry-metadata)))
        :effects [{:effect/type :runtime/schedule-thread-sleep-send-event
                   :delay-ms    (:delay-ms retry-metadata)
                   :event       :session/retry-done}]})))

  (kernel/register-handler!
   :on-retrying-entered
   (fn [_ctx _data]
     {:effects []}))

  (kernel/register-handler!
   :on-retry-resume
   (fn [_ctx {:keys [session-id]}]
     {:root-state-update (session/session-update session-id #(assoc % :retry nil))
      :effects [{:effect/type :runtime/agent-start-loop}]})))
