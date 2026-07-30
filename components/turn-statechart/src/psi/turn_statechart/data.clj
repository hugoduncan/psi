(ns psi.turn-statechart.data
  "Turn-statechart data shape and accumulation actions.

   This namespace owns the canonical per-turn data map plus the pure
   accumulation/update actions used by the statechart runtime.")

(defn create-turn-data
  "Return a fresh turn data map.

   Shared turn-data shape notes:
   - `:thinking-blocks` is intentionally present even though the minimal
     statechart itself does not mutate it; higher-level accumulation actions
     enrich the same turn-data map with streamed thinking blocks.
   - `:stop-reason` is intentionally stored in turn-data so terminal outcome
     classification can read one canonical terminal reason from the accumulated
     turn state."
  []
  {:text-buffer         ""
   :text-blocks         (sorted-map)
   :thinking-blocks     (sorted-map)
   :tool-calls          (sorted-map)
   :content-blocks      (sorted-map)
   :last-provider-event nil
   :final-message       nil
   :error-message       nil
   :stop-reason         nil})

(defn make-accumulation-actions
  "Create an actions-fn that handles data accumulation only.
   Does not call agent-core. Used by tests and as the base for the full
   actions-fn in the executor.

   `done-p` — optional promise, delivered when :on-done or :on-error fires."
  [done-p]
  (fn [action-key data]
    (let [td (:turn-data data)]
      (case action-key
        :on-stream-start nil

        :on-text-delta
        (swap! td update :text-buffer str (:delta data))

        :on-toolcall-start
        (let [idx     (:content-index data)
              tc-id   (:tool-id data)
              tc-name (:tool-name data)]
          (swap! td assoc-in [:tool-calls idx]
                 {:id tc-id :name tc-name :arguments ""}))

        :on-toolcall-delta
        (let [idx   (:content-index data)
              delta (:delta data)]
          (swap! td update-in [:tool-calls idx :arguments] str delta))

        :on-toolcall-end nil

        :on-done
        (let [{:keys [text-buffer tool-calls]} @td
              tc-blocks (->> tool-calls
                             (sort-by key)
                             (mapv (fn [[_ tc]]
                                     {:type      :tool-call
                                      :id        (:id tc)
                                      :name      (:name tc)
                                      :arguments (:arguments tc)})))
              content (cond-> []
                        (seq text-buffer) (conj {:type :text :text text-buffer})
                        :always           (into tc-blocks))
              usage (:usage data)
              stop-reason (or (:reason data) :stop)
              final (cond-> {:role        "assistant"
                             :content     content
                             :stop-reason stop-reason
                             :timestamp   (java.time.Instant/now)}
                      (map? usage) (assoc :usage usage))]
          (swap! td assoc :final-message final :stop-reason stop-reason)
          (when done-p (deliver done-p final)))

        :on-error
        (let [{:keys [text-buffer]} @td
              stop-reason (or (:stop-reason data) :error)
              err-msg     (:error-message data)
              content     (cond-> []
                            (seq text-buffer) (conj {:type :text :text text-buffer})
                            :always           (conj {:type :error :text err-msg}))
              final       {:role          "assistant"
                           :content       content
                           :stop-reason   stop-reason
                           :error-message err-msg
                           :timestamp     (java.time.Instant/now)}]
          (swap! td assoc
                 :final-message final
                 :error-message err-msg
                 :stop-reason stop-reason)
          (when done-p (deliver done-p final)))

        :on-reset
        (reset! td (create-turn-data))

        ;; unknown — ignore
        nil))))
