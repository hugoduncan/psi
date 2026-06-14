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

(defn enqueue-cancel!
  "Enqueue the cooperative `:workflow/cancel` signal (fixed empty data).

   The single named cancel action for every cancellation checkpoint across the
   statechart runtime (the `:workflow/cancel`/`{}` shape has one point of change),
   symmetric with its sibling disposition `record-actor-pending!`."
  [event-queue* working-memory*]
  (enqueue-event! event-queue* working-memory* :workflow/cancel {}))
