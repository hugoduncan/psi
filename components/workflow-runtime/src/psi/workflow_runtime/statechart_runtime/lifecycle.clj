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

(defn drain-events!
  [{:keys [event-queue* run-id] :as wf-ctx} wm]
  (loop [wm wm
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
                                (if (state/terminal-configuration? (::sc/configuration wm))
                                  wm
                                  (process-event! wf-ctx wm event data)))
                              wm
                              events)]
              (recur wm' (+ processed (count events))))))))))

(defn send-and-drain!
  [wf-ctx wm event data]
  (->> (process-event! wf-ctx wm event data)
       (drain-events! wf-ctx)))
