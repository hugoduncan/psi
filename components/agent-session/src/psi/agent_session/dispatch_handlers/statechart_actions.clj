(ns psi.agent-session.dispatch-handlers.statechart-actions
  "Handlers for statechart action events:
   on-streaming-entered, on-agent-done, on-abort, on-auto-compact-triggered,
   on-compacting-entered, on-compact-done, on-retry-triggered, on-retrying-entered,
   on-retry-resume."
  (:require
   [psi.agent-session.dispatch :as dispatch]
   [psi.agent-session.session :as session-data]
   [psi.agent-session.session-state :as session]))

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
         (session-data/context-overflow-error? (:error-message msg)))))

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

(defn register!
  "Register all statechart action handlers.
   Called once during context creation. Handlers are context-independent."
  [_ctx]
  (dispatch/register-handler!
   :on-streaming-entered
   (fn [_ctx {:keys [session-id]}]
     {:root-state-update (session/session-update session-id #(assoc % :is-streaming true))}))

  (dispatch/register-handler!
   :on-agent-done
   (fn [_ctx {:keys [session-id]}]
     {:root-state-update (session/session-update session-id #(assoc % :is-streaming false
                                                                    :retry-attempt 0
                                                                    :interrupt-pending false
                                                                    :interrupt-requested-at nil))
      :effects [{:effect/type :runtime/mark-workflow-jobs-terminal}
                {:effect/type :runtime/emit-background-job-terminal-messages}
                {:effect/type :scheduler/drain-queue}]}))

  (dispatch/register-handler!
   :on-abort
   (fn [_ctx {:keys [session-id]}]
     {:root-state-update (session/session-update session-id #(assoc % :is-streaming false
                                                                    :interrupt-pending false
                                                                    :interrupt-requested-at nil))
      :effects [{:effect/type :runtime/agent-abort}
                {:effect/type :scheduler/drain-queue}]}))

  (dispatch/register-handler!
   :on-auto-compact-triggered
   (fn [_ctx {:keys [session-id] :as data}]
     (let [reason      (or (auto-compaction-reason session-id data) :threshold)
           will-retry? (= :overflow reason)]
       {:root-state-update (session/session-update session-id #(assoc % :is-compacting true))
        :effects [{:effect/type :runtime/auto-compact-workflow
                   :reason      reason
                   :will-retry? will-retry?}]})))

  (dispatch/register-handler!
   :on-compacting-entered
   (fn [_ctx {:keys [session-id]}]
     {:root-state-update (session/session-update session-id #(assoc % :is-compacting true))}))

  (dispatch/register-handler!
   :on-compact-done
   (fn [_ctx {:keys [session-id]}]
     {:root-state-update (session/session-update session-id #(assoc % :is-compacting false))
      :effects [{:effect/type :scheduler/drain-queue}]}))

  (dispatch/register-handler!
   :on-retry-triggered
   (fn [ctx {:keys [session-id]}]
     (let [sd       (session/get-session-data-in ctx session-id)
           attempt  (:retry-attempt sd)
           base-ms  (get-in ctx [:config :auto-retry-base-delay-ms] 2000)
           max-ms   (get-in ctx [:config :auto-retry-max-delay-ms] 60000)
           delay-ms (session-data/exponential-backoff-ms attempt base-ms max-ms)]
       {:root-state-update (session/session-update session-id #(update % :retry-attempt inc))
        :effects [{:effect/type :runtime/schedule-thread-sleep-send-event
                   :delay-ms    delay-ms
                   :event       :session/retry-done}]})))

  (dispatch/register-handler!
   :on-retrying-entered
   (fn [_ctx _data]
     {:effects []}))

  (dispatch/register-handler!
   :on-retry-resume
   (fn [_ctx _data]
     {:effects [{:effect/type :runtime/agent-start-loop}]})))
