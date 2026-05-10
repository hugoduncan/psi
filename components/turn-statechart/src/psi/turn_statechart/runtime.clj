(ns psi.turn-statechart.runtime
  "Turn-statechart runtime/session helpers and query surface."
  (:require
   [com.fulcrologic.statecharts :as sc]
   [com.fulcrologic.statecharts.data-model.working-memory-data-model :as wmdm]
   [com.fulcrologic.statecharts.events :as evts]
   [com.fulcrologic.statecharts.protocols :as sp]
   [com.fulcrologic.statecharts.simple :as simple]
   [psi.turn-statechart.chart :as chart]
   [psi.turn-statechart.data :as data]))

(defn- get-working-memory [sc-env session-id]
  (sp/get-working-memory (::sc/working-memory-store sc-env) sc-env session-id))

(defn- save-working-memory! [sc-env session-id wm]
  (sp/save-working-memory! (::sc/working-memory-store sc-env) sc-env session-id wm))

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

(defn turn-snapshot
  "Return a narrowed component-level result for the current turn context.

   This is the public result shape returned by `send-event!`, avoiding leakage
   of raw statechart working-memory internals to callers."
  [turn-ctx]
  {:turn-phase (turn-phase turn-ctx)
   :turn-data  (get-turn-data turn-ctx)})

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
        turn-data  (atom (data/create-turn-data))]
    (simple/register! sc-env :turn-streaming chart/turn-chart)
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
   into the working memory before processing.

   Returns a narrowed component-level snapshot:
   `{:turn-phase kw :turn-data map}`."
  ([turn-ctx event-kw]
   (send-event! turn-ctx event-kw nil))
  ([turn-ctx event-kw extra-data]
   (let [{:keys [sc-env session-id]} turn-ctx
         wm  (get-working-memory sc-env session-id)
         wm' (if extra-data
               (update wm ::wmdm/data-model merge extra-data)
               wm)
         _   (save-working-memory! sc-env session-id wm')
         evt (evts/new-event {:name event-kw})
         wm'' (sp/process-event! (::sc/processor sc-env) sc-env wm' evt)]
     (save-working-memory! sc-env session-id wm'')
     (turn-snapshot turn-ctx))))
