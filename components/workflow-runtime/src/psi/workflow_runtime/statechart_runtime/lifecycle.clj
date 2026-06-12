(ns psi.workflow-runtime.statechart-runtime.lifecycle
  (:require
   [com.fulcrologic.statecharts :as sc]
   [com.fulcrologic.statecharts.data-model.working-memory-data-model :as wmdm]
   [com.fulcrologic.statecharts.events :as evts]
   [com.fulcrologic.statecharts.protocols :as sp]
   [psi.workflow-runtime.statechart-runtime.state :as state]))

(defn process-event!
  [{:keys [ctx run-id env sc-session-id working-memory*] :as wf-ctx} wm event data]
  (let [wm' (update wm ::wmdm/data-model merge (assoc @working-memory* :actions-fn (:actions-fn wf-ctx)) data)
        wm'' (sp/process-event! (::sc/processor env) env wm' (evts/new-event {:name event :data (or data {})}))]
    (sp/save-working-memory! (::sc/working-memory-store env) env sc-session-id wm'')
    (state/sync-run-projection! ctx run-id working-memory* (::sc/configuration wm''))
    wm''))

(defn- cancelled-wm?
  [wm]
  (boolean (some #{:cancelled} (::sc/configuration wm))))

(defn- mark-cancelled!
  [{:keys [ctx run-id env sc-session-id working-memory*]} wm]
  (swap! working-memory* assoc :updated-at (state/now))
  (when-not (= :removed (state/workflow-stop-signal ctx run-id))
    (state/sync-run-projection! ctx run-id working-memory* #{:cancelled}))
  (let [wm' (assoc wm ::sc/configuration #{:cancelled})]
    (when (and env sc-session-id)
      (sp/save-working-memory! (::sc/working-memory-store env) env sc-session-id wm'))
    wm'))

(defn- clear-queue-and-cancel!
  [{:keys [event-queue*] :as wf-ctx} wm]
  (reset! event-queue* [])
  (if (cancelled-wm? wm)
    wm
    (mark-cancelled! wf-ctx wm)))

(defn stop-checkpoint
  "Return wm unchanged or transition it to :cancelled when canonical run state
   carries a cooperative stop signal.

   The checkpoint treats both :cancelled status and run absence as stop signals.
   It is safe to call repeatedly; once the chart is terminal no further ordinary
   workflow advancement is processed."
  [{:keys [ctx run-id] :as wf-ctx} wm]
  (if (and (not (state/terminal-configuration? (::sc/configuration wm)))
           (state/workflow-stopped? ctx run-id))
    (clear-queue-and-cancel! wf-ctx wm)
    wm))

(defn drain-events!
  [{:keys [event-queue* run-id] :as wf-ctx} wm]
  (loop [wm (stop-checkpoint wf-ctx wm)
         processed 0]
    (cond
      (state/terminal-configuration? (::sc/configuration wm))
      (do
        (reset! event-queue* [])
        wm)

      (>= processed state/max-drain-events)
      (throw (ex-info "Workflow event drain exceeded safety bound"
                      {:run-id run-id
                       :processed-events processed
                       :max-drain-events state/max-drain-events
                       :configuration (::sc/configuration wm)
                       :queued-events @event-queue*}))

      :else
      (let [events @event-queue*]
        (if (empty? events)
          wm
          (do
            (reset! event-queue* [])
            (let [wm' (reduce (fn [wm {:keys [event data]}]
                                (let [wm* (stop-checkpoint wf-ctx wm)]
                                  (if (state/terminal-configuration? (::sc/configuration wm*))
                                    wm*
                                    (let [process-event-fn (or (:process-event-fn wf-ctx) process-event!)]
                                      (stop-checkpoint wf-ctx (process-event-fn wf-ctx wm* event data))))))
                              wm
                              events)]
              (recur wm' (+ processed (count events))))))))))

(defn send-and-drain!
  [wf-ctx wm event data]
  (let [wm* (stop-checkpoint wf-ctx wm)]
    (if (state/terminal-configuration? (::sc/configuration wm*))
      wm*
      (let [process-event-fn (or (:process-event-fn wf-ctx) process-event!)]
        (->> (process-event-fn wf-ctx wm* event data)
             (stop-checkpoint wf-ctx)
             (drain-events! wf-ctx))))))
