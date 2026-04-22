(ns psi.agent-session.dispatch-handlers.scheduler
  "Dispatch handlers for session-scoped delayed scheduler actions: same-session delayed prompts and delayed fresh top-level session creation."
  (:require
   [psi.agent-session.dispatch :as dispatch]
   [psi.agent-session.scheduler :as scheduler]
   [psi.agent-session.scheduler-runtime :as scheduler-runtime]
   [psi.agent-session.session-lifecycle :as session-lifecycle]
   [psi.agent-session.session-state :as ss]
   [psi.agent-session.tool-defs :as tool-defs]))

(defn- register-core-handler! [event handler]
  (dispatch/register-handler! event handler))

(defn- scheduler-state-in
  [ctx session-id]
  (or (ss/get-state-value-in ctx (ss/state-path :scheduler session-id))
      (scheduler/empty-state)))

(defn- scheduler-update
  [session-id f]
  (ss/session-update
   session-id
   #(update % :scheduler (fn [scheduler-state]
                           (f (or scheduler-state (scheduler/empty-state)))))))

(defn- scheduler-error-summary [e]
  {:message (or (ex-message e) (str e))
   :class (.getName (class e))
   :data (ex-data e)})

(defn- scheduled-user-message
  [{:keys [schedule-id label message]}]
  {:role "user"
   :content [{:type :text :text message}]
   :timestamp (java.time.Instant/now)
   :source :scheduled
   :schedule-id schedule-id
   :label label})

(defn- scheduled-session-config-dispatches
  [ctx session-id {:keys [system-prompt thinking-level model cache-breakpoints developer-prompt developer-prompt-source skills tool-defs prompt-component-selection preloaded-messages]}]
  (let [normalized-tool-defs (when (some? tool-defs)
                               (tool-defs/normalize-tool-defs tool-defs))
        existing-base-prompt (:base-system-prompt (ss/get-session-data-in ctx session-id))
        base-events          (cond-> []
                               (some? system-prompt)
                               (conj {:event-type :session/set-system-prompt
                                      :event-data {:session-id session-id
                                                   :prompt system-prompt}})

                               (some? thinking-level)
                               (conj {:event-type :session/set-thinking-level
                                      :event-data {:session-id session-id
                                                   :level thinking-level
                                                   :scope :session}})

                               (some? model)
                               (conj {:event-type :session/set-model
                                      :event-data {:session-id session-id
                                                   :model model
                                                   :scope :session}})

                               (some? cache-breakpoints)
                               (conj {:event-type :session/set-cache-breakpoints
                                      :event-data {:session-id session-id
                                                   :breakpoints cache-breakpoints}})

                               (or (some? developer-prompt)
                                   (some? developer-prompt-source))
                               (conj {:event-type :session/bootstrap-prompt-state
                                      :event-data {:session-id session-id
                                                   :system-prompt (or system-prompt existing-base-prompt "")
                                                   :developer-prompt developer-prompt
                                                   :developer-prompt-source developer-prompt-source}})

                               (some? skills)
                               (conj {:event-type :session/set-skills
                                      :event-data {:session-id session-id
                                                   :skills (vec skills)}})

                               (some? normalized-tool-defs)
                               (conj {:event-type :session/set-active-tools
                                      :event-data {:session-id session-id
                                                   :tool-maps normalized-tool-defs}})

                               (contains? {:prompt-component-selection prompt-component-selection} :prompt-component-selection)
                               (conj {:event-type :session/set-prompt-component-selection
                                      :event-data {:session-id session-id
                                                   :selection prompt-component-selection}}))
        message-events       (mapv (fn [message]
                                     {:event-type :session/append-extension-message
                                      :event-data {:session-id session-id
                                                   :message message}})
                                   (vec (or preloaded-messages [])))]
    (vec (concat base-events message-events))))

(defn register!
  [_ctx]
  (register-core-handler!
   :scheduler/create
   (fn [ctx {:keys [session-id schedule-id kind label message session-config created-at fire-at]}]
     (let [created-at (or created-at (java.time.Instant/now))
           kind       (or kind :message)
           {state' :state schedule :schedule}
           (scheduler/create-schedule
            (scheduler-state-in ctx session-id)
            {:schedule-id schedule-id
             :kind kind
             :label label
             :message message
             :created-at created-at
             :fire-at fire-at
             :origin-session-id session-id
             :session-config session-config
             :session-config-summary (scheduler-runtime/session-config-summary session-config)})]
       {:root-state-update (scheduler-update session-id (constantly state'))
        :effects [{:effect/type :scheduler/start-timer
                   :session-id session-id
                   :schedule-id schedule-id
                   :fire-at fire-at}]
        :return schedule})))

  (register-core-handler!
   :scheduler/cancel
   (fn [ctx {:keys [session-id schedule-id]}]
     (let [{state' :state schedule :schedule}
           (scheduler/cancel-schedule (scheduler-state-in ctx session-id) schedule-id)]
       {:root-state-update (scheduler-update session-id (constantly state'))
        :effects [{:effect/type :scheduler/cancel-timer
                   :schedule-id schedule-id}]
        :return schedule})))

  (register-core-handler!
   :scheduler/cancel-all
   (fn [ctx {:keys [session-id]}]
     (let [{state' :state cancelled-ids :cancelled-ids cancelled-schedules :cancelled-schedules}
           (scheduler/cancel-all-schedules (scheduler-state-in ctx session-id))]
       {:root-state-update (scheduler-update session-id (constantly state'))
        :effects (mapv (fn [schedule-id]
                         {:effect/type :scheduler/cancel-timer
                          :schedule-id schedule-id})
                       cancelled-ids)
        :return {:cancelled-count (count cancelled-ids)
                 :cancelled-schedule-ids cancelled-ids
                 :cancelled-schedules cancelled-schedules}})))

  (register-core-handler!
   :scheduler/fired
   (fn [ctx {:keys [session-id schedule-id]}]
     (let [{state' :state action :action schedule :schedule}
           (scheduler/fire-schedule
            (scheduler-state-in ctx session-id)
            (ss/get-session-data-in ctx session-id)
            schedule-id)]
       (cond-> {:root-state-update (scheduler-update session-id (constantly state'))
                :return schedule}
         (= :deliver action)
         (update :effects (fnil conj [])
                 {:effect/type :runtime/dispatch-event
                  :event-type :scheduler/deliver
                  :event-data {:session-id session-id
                               :schedule-id schedule-id
                               :delivery-phase (when (= :session (:kind schedule)) :create-session)}
                  :origin :core})))))

  (register-core-handler!
   :scheduler/deliver
   (fn [ctx {:keys [session-id schedule-id]}]
     (let [schedule (scheduler/get-schedule (scheduler-state-in ctx session-id) schedule-id)]
       (if (= :session (:kind schedule))
         (try
           (let [session-config   (or (:session-config schedule) {})
                 created         (session-lifecycle/create-top-level-session-in!
                                  ctx
                                  session-id
                                  {:session-name (:session-name session-config)
                                   :worktree-path (ss/session-worktree-path-in ctx session-id)
                                   :scheduled-origin-session-id session-id
                                   :scheduled-from-schedule-id schedule-id
                                   :scheduled-from-label (:label schedule)})
                 created-id      (:session-id created)
                 apply-dispatches (scheduled-session-config-dispatches ctx created-id session-config)
                 _               (doseq [{:keys [event-type event-data]} apply-dispatches]
                                   (dispatch/dispatch! ctx event-type event-data {:origin :core}))
                 prompt-result   (let [result (dispatch/dispatch! ctx :session/submit-synthetic-user-prompt
                                                                  {:session-id created-id
                                                                   :user-msg (scheduled-user-message schedule)}
                                                                  {:origin :core})]
                                   (when-not (:submitted? result)
                                     (throw (ex-info "scheduled session prompt submission failed"
                                                     {:created-session-id created-id
                                                      :delivery-phase :prompt-submit
                                                      :result result})))
                                   result)
                 {state' :state schedule' :schedule}
                 (scheduler/deliver-schedule (scheduler-state-in ctx session-id) schedule-id)
                 final-schedule  (assoc schedule'
                                        :created-session-id created-id
                                        :delivery-phase :prompt-submit)
                 final-state     (assoc-in state' [:schedules schedule-id] final-schedule)]
             {:root-state-update (scheduler-update session-id (constantly final-state))
              :return {:schedule final-schedule
                       :created-session-id created-id
                       :prompt-result prompt-result}})
           (catch Exception e
             (let [created-session-id (or (some-> e ex-data :created-session-id)
                                          (some-> e ex-data :session-id))
                   delivery-phase    (or (some-> e ex-data :delivery-phase)
                                         (if created-session-id :prompt-submit :create-session))
                   {state' :state failed :schedule}
                   (scheduler/fail-schedule (scheduler-state-in ctx session-id)
                                            schedule-id
                                            {:delivery-phase delivery-phase
                                             :created-session-id created-session-id
                                             :error-summary (scheduler-error-summary e)})]
               {:root-state-update (scheduler-update session-id (constantly state'))
                :return failed})))
         (let [{state' :state schedule' :schedule}
               (scheduler/deliver-schedule (scheduler-state-in ctx session-id) schedule-id)
               user-msg (scheduled-user-message schedule')]
           {:root-state-update (scheduler-update session-id (constantly state'))
            :effects [{:effect/type :runtime/dispatch-event-with-effect-result
                       :event-type :session/submit-synthetic-user-prompt
                       :event-data {:session-id session-id
                                    :user-msg user-msg}
                       :origin :core}]
            :return (assoc schedule' :message-record user-msg)})))))

  (register-core-handler!
   :scheduler/drain-queue
   (fn [ctx {:keys [session-id]}]
     (let [{state' :state drained? :drained? schedule :schedule}
           (scheduler/drain-one
            (scheduler-state-in ctx session-id)
            (ss/get-session-data-in ctx session-id))
           user-msg (when schedule (scheduled-user-message schedule))]
       (cond-> {:root-state-update (scheduler-update session-id (constantly state'))
                :return {:drained? drained?
                         :schedule-id (:schedule-id schedule)}}
         drained?
         (update :effects into [{:effect/type :runtime/dispatch-event-with-effect-result
                                 :event-type :session/submit-synthetic-user-prompt
                                 :event-data {:session-id session-id
                                              :user-msg user-msg}
                                 :origin :core}]))))))
