(ns psi.turn_statechart.core
  "Per-turn stream-assembly statechart — formalises the implicit provider
   streaming accumulation in `stream-turn!` as an explicit, queryable statechart.

   Scope
   ─────
   This chart owns one provider-stream assembly boundary only:
   text/thinking/tool-call accumulation and one streamed assistant terminal
   result (`:done` | `:error`).

   It does not own the next-step orchestration boundary after stream completion:
   tool execution, recurse-for-next-turn decisions, and agent-loop completion
   remain in `psi.agent-session.prompt-turn` / `psi.agent-session.prompt-loop`.

   States
   ──────
   :idle               — not streaming, ready for one provider stream
   :text-accumulating   — receiving text deltas from the provider
   :tool-accumulating   — receiving tool-call argument deltas
   :done                — final assistant message assembled for this stream
   :error               — provider error or timeout for this stream

   Working memory keys (stored in the flat data model)
   ─────────────────────────────────────────────────────
   :turn-data   — atom holding the turn accumulation map
   :actions-fn  — (fn [action-key data]) — side-effect dispatcher

   Provider events → statechart events
   ────────────────────────────────────
   (prompt-turn)   → :turn/start          {}
   :text-delta     → :turn/text-delta     {:delta \"...\"}
   :toolcall-start → :turn/toolcall-start {:content-index n :tool-id \"...\" :tool-name \"...\"}
   :toolcall-delta → :turn/toolcall-delta {:content-index n :delta \"...\"}
   :toolcall-end   → :turn/toolcall-end   {:content-index n}
   :done           → :turn/done           {:reason kw :usage map?}
   :error          → :turn/error          {:error-message \"...\"}
   (reset)         → :turn/reset"
  (:require
   [com.fulcrologic.statecharts :as sc]
   [com.fulcrologic.statecharts.chart :as chart]
   [com.fulcrologic.statecharts.data-model.working-memory-data-model :as wmdm]
   [com.fulcrologic.statecharts.elements :as ele]
   [com.fulcrologic.statecharts.events :as evts]
   [com.fulcrologic.statecharts.protocols :as sp]
   [com.fulcrologic.statecharts.simple :as simple]))

;; ============================================================
;; Turn data model
;; ============================================================

(defn create-turn-data
  "Return a fresh turn data map."
  []
  {:text-buffer         ""
   :thinking-blocks     (sorted-map)
   :tool-calls          (sorted-map)
   :content-blocks      (sorted-map)
   :last-provider-event nil
   :final-message       nil
   :error-message       nil
   :stop-reason         nil})

;; ============================================================
;; Script helper
;; ============================================================

(defn- dispatch! [data action-key]
  (when-let [af (:actions-fn data)]
    (af action-key data)))

;; ============================================================
;; Statechart definition
;;
;; All accumulation transitions use self-transitions (same target)
;; rather than targetless transitions for simple-env compatibility.
;; ============================================================

(def turn-chart
  (chart/statechart {:id :turn-streaming}

                    (ele/state {:id :idle}
                               (ele/transition {:event  :turn/start
                                                :target :text-accumulating}
                                               (ele/script {:expr (fn [_env data] (dispatch! data :on-stream-start))})))

                    (ele/state {:id :text-accumulating}
                               (ele/transition {:event  :turn/text-delta
                                                :target :text-accumulating}
                                               (ele/script {:expr (fn [_env data] (dispatch! data :on-text-delta))}))
                               (ele/transition {:event  :turn/toolcall-start
                                                :target :tool-accumulating}
                                               (ele/script {:expr (fn [_env data] (dispatch! data :on-toolcall-start))}))
                               (ele/transition {:event  :turn/done
                                                :target :done}
                                               (ele/script {:expr (fn [_env data] (dispatch! data :on-done))}))
                               (ele/transition {:event  :turn/error
                                                :target :error}
                                               (ele/script {:expr (fn [_env data] (dispatch! data :on-error))})))

                    (ele/state {:id :tool-accumulating}
                               (ele/transition {:event  :turn/toolcall-delta
                                                :target :tool-accumulating}
                                               (ele/script {:expr (fn [_env data] (dispatch! data :on-toolcall-delta))}))
                               (ele/transition {:event  :turn/toolcall-end
                                                :target :text-accumulating}
                                               (ele/script {:expr (fn [_env data] (dispatch! data :on-toolcall-end))}))
                               (ele/transition {:event  :turn/toolcall-start
                                                :target :tool-accumulating}
                                               (ele/script {:expr (fn [_env data] (dispatch! data :on-toolcall-start))}))
                               (ele/transition {:event  :turn/text-delta
                                                :target :text-accumulating}
                                               (ele/script {:expr (fn [_env data] (dispatch! data :on-text-delta))}))
                               (ele/transition {:event  :turn/done
                                                :target :done}
                                               (ele/script {:expr (fn [_env data] (dispatch! data :on-done))}))
                               (ele/transition {:event  :turn/error
                                                :target :error}
                                               (ele/script {:expr (fn [_env data] (dispatch! data :on-error))})))

                    (ele/state {:id :done}
                               (ele/transition {:event  :turn/reset
                                                :target :idle}
                                               (ele/script {:expr (fn [_env data] (dispatch! data :on-reset))})))

                    (ele/state {:id :error}
                               (ele/transition {:event  :turn/reset
                                                :target :idle}
                                               (ele/script {:expr (fn [_env data] (dispatch! data :on-reset))})))))

;; ============================================================
;; Accumulation actions (pure data, no agent-core dependency)
;; ============================================================

(defn make-accumulation-actions
  "Create an actions-fn that handles data accumulation only.
   Does not call agent-core.  Used by tests and as the base
   for the full actions-fn in the executor.

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
              final (cond-> {:role        "assistant"
                             :content     content
                             :stop-reason (or (:reason data) :stop)
                             :timestamp   (java.time.Instant/now)}
                      (map? usage) (assoc :usage usage))]
          (swap! td assoc :final-message final)
          (when done-p (deliver done-p final)))

        :on-error
        (let [{:keys [text-buffer]} @td
              err-msg (:error-message data)
              content (cond-> []
                        (seq text-buffer) (conj {:type :text :text text-buffer})
                        :always           (conj {:type :error :text err-msg}))
              final {:role          "assistant"
                     :content       content
                     :stop-reason   :error
                     :error-message err-msg
                     :timestamp     (java.time.Instant/now)}]
          (swap! td assoc :final-message final :error-message err-msg)
          (when done-p (deliver done-p final)))

        :on-reset
        (reset! td (create-turn-data))

        ;; unknown — ignore
        nil))))

;; ============================================================
;; Statechart env / session management
;; ============================================================

(defn- get-working-memory [sc-env session-id]
  (sp/get-working-memory (::sc/working-memory-store sc-env) sc-env session-id))

(defn- save-working-memory! [sc-env session-id wm]
  (sp/save-working-memory! (::sc/working-memory-store sc-env) sc-env session-id wm))

(defn create-turn-context
  "Create an isolated turn streaming context.

   `actions-fn` — (fn [action-key data]) side-effect dispatcher.
                  Use `make-accumulation-actions` for pure tests or wrap it
                  with agent-core calls for production.

   Returns a context map:
     :sc-env     — statechart environment
     :session-id — UUID for this turn's statechart session
     :turn-data  — atom holding the turn accumulation data"
  [actions-fn]
  (let [sc-env     (simple/simple-env)
        session-id (java.util.UUID/randomUUID)
        turn-data  (atom (create-turn-data))]
    (simple/register! sc-env :turn-streaming turn-chart)
    (let [wm (sp/start! (::sc/processor sc-env) sc-env :turn-streaming
                        {::sc/session-id session-id})]
      ;; User data goes into the flat data model's key so scripts see it.
      ;; The FlatWorkingMemoryDataModel reads ::wmdm/data-model (NOT ::sc/data-model).
      (save-working-memory! sc-env session-id
                            (assoc wm ::wmdm/data-model
                                   {:turn-data  turn-data
                                    :actions-fn actions-fn})))
    {:sc-env     sc-env
     :session-id session-id
     :turn-data  turn-data}))

(defn send-event!
  "Send `event-kw` to the turn statechart, optionally merging `extra-data`
   into the working memory before processing."
  ([turn-ctx event-kw]
   (send-event! turn-ctx event-kw nil))
  ([turn-ctx event-kw extra-data]
   (let [{:keys [sc-env session-id]} turn-ctx
         wm  (get-working-memory sc-env session-id)
         ;; Merge event data into the flat data model's key so scripts see it.
         wm' (if extra-data
               (update wm ::wmdm/data-model merge extra-data)
               wm)
         _   (save-working-memory! sc-env session-id wm')
         evt (evts/new-event {:name event-kw})
         wm'' (sp/process-event! (::sc/processor sc-env) sc-env wm' evt)]
     (save-working-memory! sc-env session-id wm'')
     wm'')))

;; ============================================================
;; Query functions
;; ============================================================

(defn turn-configuration
  "Return the active statechart configuration set (e.g. #{:idle})."
  [turn-ctx]
  (when-let [wm (get-working-memory (:sc-env turn-ctx) (:session-id turn-ctx))]
    (::sc/configuration wm)))

(defn turn-phase
  "Return the active phase keyword
   (:idle | :text-accumulating | :tool-accumulating | :done | :error)."
  [turn-ctx]
  (first (turn-configuration turn-ctx)))

(defn get-turn-data
  "Return the current turn data map (deref of :turn-data atom)."
  [turn-ctx]
  @(:turn-data turn-ctx))


