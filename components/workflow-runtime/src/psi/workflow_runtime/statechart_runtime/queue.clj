(ns psi.workflow-runtime.statechart-runtime.queue)

(defn enqueue-event!
  [event-queue* working-memory* event data]
  (swap! event-queue* conj {:event event
                            :data (merge {:current-step-id (:current-step-id @working-memory*)
                                          :iteration-counts (:iteration-counts @working-memory*)}
                                         data)}))

(defn queue-event!
  [{:keys [event-queue* working-memory*]} event data]
  (enqueue-event! event-queue* working-memory* event data))
