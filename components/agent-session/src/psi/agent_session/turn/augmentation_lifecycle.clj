(ns psi.agent-session.turn.augmentation-lifecycle
  "Pure state transitions for the pre-turn augmentation lifecycle barrier."
  (:require
   [psi.session-state.state :as session]))

(def terminal-turn-states
  #{:turn/augmentation-closed
    :turn/canceled
    :turn/augmentation-failed})

(defn submitted-turn-lifecycle
  [session-id turn-id workflow-run-id]
  {:session-id session-id
   :turn-id turn-id
   :workflow-run-id workflow-run-id
   :state :turn/submitted})

(defn turn-lifecycle
  [session-data turn-id]
  (get-in session-data [:prompt-turns turn-id]))

(defn turn-state
  [session-data turn-id]
  (:state (turn-lifecycle session-data turn-id)))

(defn invalid-transition!
  [event session-id turn-id from-state]
  (throw (ex-info "Invalid prompt lifecycle transition"
                  {:reason :invalid-prompt-lifecycle-transition
                   :event event
                   :session-id session-id
                   :turn-id turn-id
                   :from-state from-state})))

(defn require-turn-state!
  [event session-id session-data turn-id expected-states]
  (let [from-state (turn-state session-data turn-id)]
    (when-not (contains? expected-states from-state)
      (invalid-transition! event session-id turn-id from-state))
    from-state))

(defn open-record
  [session-id turn-id workflow-run-id]
  {:session-id session-id
   :turn-id turn-id
   :workflow-run-id workflow-run-id
   :status :open
   :replay? false
   :accepting? true
   :accepted-operation-count 0
   :operations []
   :providers []})

(defn open-phase-update
  [session-id turn-id workflow-run-id]
  (fn [session-data]
    (-> session-data
        (assoc-in [:prompt-turns turn-id]
                  {:session-id session-id
                   :turn-id turn-id
                   :workflow-run-id workflow-run-id
                   :state :turn/augmentation-open})
        (assoc-in [:turn-augmentations turn-id]
                  (open-record session-id turn-id workflow-run-id)))))

(defn close-phase-update
  [session-id turn-id workflow-run-id close-record]
  (fn [session-data]
    (-> session-data
        (assoc-in [:prompt-turns turn-id]
                  {:session-id session-id
                   :turn-id turn-id
                   :workflow-run-id workflow-run-id
                   :state :turn/augmentation-closed})
        (assoc-in [:turn-augmentations turn-id]
                  close-record))))

(defn no-provider-close-record
  [session-id turn-id workflow-run-id]
  {:session-id session-id
   :turn-id turn-id
   :workflow-run-id workflow-run-id
   :status :no-op
   :replay? false
   :accepted-operation-count 0
   :operations []
   :providers []})

(defn prepare-effect
  [{:keys [session-id turn-id user-msg progress-queue runtime-opts return-execution-result? workflow-run-id]}]
  (cond-> {:effect/type :runtime/dispatch-event-with-effect-result
           :event-type :session/prompt-prepare-request
           :event-data (cond-> {:session-id session-id
                                :turn-id turn-id
                                :user-msg user-msg}
                         progress-queue (assoc :progress-queue progress-queue)
                         runtime-opts (assoc :runtime-opts runtime-opts)
                         return-execution-result? (assoc :return-execution-result? true)
                         workflow-run-id (assoc :workflow-run-id workflow-run-id))
           :origin :core}
    workflow-run-id (assoc :workflow-run-id workflow-run-id)))

(defn open-phase-result
  [session-id turn-id workflow-run-id prepare-event-data]
  {:root-state-update
   (session/session-update
    session-id
    (open-phase-update session-id turn-id workflow-run-id))
   :effects [{:effect/type :runtime/dispatch-event-with-effect-result
              :event-type :session/close-pre-turn-augmentation
              :event-data (assoc prepare-event-data
                                 :session-id session-id
                                 :turn-id turn-id
                                 :workflow-run-id workflow-run-id)
              :origin :core}]
   :return-effect-result? true})

(defn close-phase-result
  [session-id turn-id workflow-run-id close-record prepare-event-data]
  {:root-state-update
   (session/session-update
    session-id
    (close-phase-update session-id turn-id workflow-run-id close-record))
   :effects [(prepare-effect (assoc prepare-event-data
                                    :session-id session-id
                                    :turn-id turn-id
                                    :workflow-run-id workflow-run-id))]
   :return-effect-result? true})
