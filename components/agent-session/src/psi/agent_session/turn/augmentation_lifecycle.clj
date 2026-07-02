(ns psi.agent-session.turn.augmentation-lifecycle
  "Pure state transitions for the pre-turn augmentation lifecycle barrier."
  (:require
   [psi.agent-session.extensions :as extensions]
   [psi.session-state.state :as session]
   [psi.turn-runtime.augmentation :as turn-augmentation]))

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
  [session-id turn-id workflow-run-id selected-providers]
  {:session-id session-id
   :turn-id turn-id
   :workflow-run-id workflow-run-id
   :status :open
   :replay? false
   :accepting? true
   :accepted-operation-count 0
   :operations []
   :providers []
   :selected-providers selected-providers})

(defn open-phase-update
  [session-id turn-id workflow-run-id selected-providers]
  (fn [session-data]
    (-> session-data
        (assoc-in [:prompt-turns turn-id]
                  {:session-id session-id
                   :turn-id turn-id
                   :workflow-run-id workflow-run-id
                   :state :turn/augmentation-open})
        (assoc-in [:turn-augmentations turn-id]
                  (open-record session-id turn-id workflow-run-id selected-providers)))))

(defn close-phase-update
  ([session-id turn-id workflow-run-id close-record]
   (close-phase-update session-id turn-id workflow-run-id close-record :turn/augmentation-closed))
  ([session-id turn-id workflow-run-id close-record turn-state]
   (fn [session-data]
     (-> session-data
         (assoc-in [:prompt-turns turn-id]
                   {:session-id session-id
                    :turn-id turn-id
                    :workflow-run-id workflow-run-id
                    :state turn-state})
         (assoc-in [:turn-augmentations turn-id]
                   close-record)))))

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

(defn extension-session-capability-available?
  [session-data extension-id capability]
  (contains? (set (get-in session-data
                          [:available-extension-capabilities :extensions extension-id]))
             capability))

(defn live-turn-augmentation-authorized?
  [reg session-data extension-id]
  (and (contains? (extensions/effective-permissions-in reg extension-id)
                  turn-augmentation/turn-augmentation-capability)
       (extension-session-capability-available?
        session-data
        extension-id
        turn-augmentation/turn-augmentation-capability)))

(defn selected-provider-snapshot
  [provider]
  (select-keys provider [:extension-id :augmenter-id :registration-token]))

(defn selected-providers
  [reg session-data]
  (->> (extensions/turn-augmenters-in reg)
       (filter #(live-turn-augmentation-authorized? reg session-data (:extension-id %)))
       (mapv selected-provider-snapshot)))

(defn unauthorized-provider-results
  [reg session-data]
  (->> (extensions/turn-augmenters-in reg)
       (remove #(live-turn-augmentation-authorized? reg session-data (:extension-id %)))
       (mapv turn-augmentation/provider-unauthorized)))

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

(defn invoke-effect
  [{:keys [session-id turn-id user-msg workflow-run-id selected-providers prepare-event-data]}]
  (cond-> {:effect/type :runtime/turn-augmentation-invoke
           :session-id session-id
           :turn-id turn-id
           :user-msg user-msg
           :selected-providers selected-providers
           :prepare-event-data prepare-event-data}
    workflow-run-id (assoc :workflow-run-id workflow-run-id)))

(defn close-dispatch-effect
  [session-id turn-id workflow-run-id prepare-event-data close-record]
  (cond-> {:effect/type :runtime/dispatch-event-with-effect-result
           :event-type :session/close-pre-turn-augmentation
           :event-data (assoc prepare-event-data
                              :session-id session-id
                              :turn-id turn-id
                              :workflow-run-id workflow-run-id
                              :close-record close-record)
           :origin :core}
    workflow-run-id (assoc :workflow-run-id workflow-run-id)))

(defn open-phase-result
  [reg session-id turn-id workflow-run-id prepare-event-data session-data]
  (let [selected             (selected-providers reg session-data)
        unauthorized-results (unauthorized-provider-results reg session-data)
        no-live-providers?   (empty? selected)
        immediate-record     (when no-live-providers?
                               (if (seq unauthorized-results)
                                 (turn-augmentation/terminal-record
                                  session-id
                                  turn-id
                                  workflow-run-id
                                  unauthorized-results)
                                 (no-provider-close-record session-id turn-id workflow-run-id)))]
    {:root-state-update
     (session/session-update
      session-id
      (open-phase-update session-id turn-id workflow-run-id selected))
     :effects [(if immediate-record
                 (close-dispatch-effect session-id turn-id workflow-run-id prepare-event-data immediate-record)
                 (invoke-effect {:session-id session-id
                                 :turn-id turn-id
                                 :user-msg (:user-msg prepare-event-data)
                                 :workflow-run-id workflow-run-id
                                 :selected-providers selected
                                 :prepare-event-data prepare-event-data}))]
     :return-effect-result? true}))

(defn close-phase-result
  [session-id turn-id workflow-run-id close-record prepare-event-data]
  (let [canceled? (= :canceled (:status close-record))]
    (cond-> {:root-state-update
             (session/session-update
              session-id
              (close-phase-update session-id
                                  turn-id
                                  workflow-run-id
                                  close-record
                                  (if canceled?
                                    :turn/canceled
                                    :turn/augmentation-closed)))
             :return-effect-result? true}
      (not canceled?)
      (assoc :effects [(prepare-effect (assoc prepare-event-data
                                              :session-id session-id
                                              :turn-id turn-id
                                              :workflow-run-id workflow-run-id))])
      canceled?
      (assoc :return {:canceled? true
                      :session-id session-id
                      :turn-id turn-id}))))
