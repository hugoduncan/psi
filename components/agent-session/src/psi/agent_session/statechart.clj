(ns psi.agent-session.statechart
  "AgentSession statechart — models the orthogonal phases of a session.

   States
   ──────
   :idle       — ready for user input; neither streaming nor compacting
   :streaming  — agent loop is running (entered via :session/prompt)
   :compacting — compaction is executing (entered via :session/compact-start)
   :retrying   — waiting for exponential backoff before the next retry

   The streaming → compacting / retrying transitions fire reactively when
   the agent-core emits an :agent-end event.  The session context registers
   an add-watch on the agent-core events atom so that every event emitted by
   agent-core is forwarded to the session statechart as :session/agent-event
   (with the agent event in the working memory under :pending-agent-event).

   Execution model
   ───────────────
   `simple-env` uses the flat working memory data model.  Guard and action
   functions receive `(fn [env data])` where `data` is the current flat map.
   The current event is at `(:_event data)` as stored by the algorithm.

   Working memory keys (stored in the flat data model)
   ─────────────────────────────────────────────────────
   :ctx                — runtime session context; statechart guards read current
                         AgentSession data through canonical state helpers
   :actions-fn         — (fn [key]) — side-effect dispatcher
   :config             — merged config map
   :pending-agent-event — the most recent agent event (set before dispatching
                          :session/agent-event to allow guards to inspect it)"
  (:require
   [com.fulcrologic.statecharts :as sc]
   [com.fulcrologic.statecharts.chart :as chart]
   [com.fulcrologic.statecharts.elements :as ele]
   [com.fulcrologic.statecharts.events :as evts]
   [com.fulcrologic.statecharts.protocols :as sp]
   [com.fulcrologic.statecharts.simple :as simple]
   [psi.agent-session.session :as session]))

;; ============================================================
;; Guard helpers — operate on flat working memory data
;; ============================================================

(defn- agent-end-event? [data]
  (= :agent-end (:type (:pending-agent-event data))))

(defn- last-assistant-message [data]
  (let [m (last (:messages (:pending-agent-event data)))]
    (when (= "assistant" (:role m)) m)))

(defn- overflow-error? [msg]
  (let [stop-reason (or (:stop-reason msg) (:stopReason msg))]
    (and (or (= :error stop-reason)
             (= "error" stop-reason))
         (session/context-overflow-error? (:error-message msg)))))

(defn- threshold-reached? [sd config]
  (let [tokens  (:context-tokens sd)
        window  (:context-window sd)
        reserve (long (or (:auto-compaction-reserve-tokens config) 16384))
        cutoff  (when (and (number? window) (pos? window))
                  (max 0 (- window reserve)))]
    (and (number? tokens)
         (number? cutoff)
         (> tokens cutoff))))

(defn- current-session-data [data]
  (let [ctx (:ctx data)
        sid (:session-id data)]
    (get-in @(:state* ctx) [:agent-session :sessions sid :data])))

(defn- auto-compaction-reason [data]
  (let [sd      (current-session-data data)
        config  (:config data)
        last-msg (last-assistant-message data)]
    (cond
      (and (agent-end-event? data)
           (:auto-compaction-enabled sd)
           (map? last-msg)
           (overflow-error? last-msg))
      :overflow

      (and (agent-end-event? data)
           (:auto-compaction-enabled sd)
           (threshold-reached? sd config)
           (not (overflow-error? last-msg)))
      :threshold

      :else
      nil)))

(defn- should-auto-compact? [data]
  (boolean (auto-compaction-reason data)))

(defn- should-retry? [data]
  (let [sd     (current-session-data data)
        max-r  (get-in data [:config :auto-retry-max-retries] 3)
        event  (:pending-agent-event data)
        last-m (last (:messages event))]
    (boolean
     (and (agent-end-event? data)
          (:auto-retry-enabled sd)
          (not (:interrupt-pending sd))
          (< (:retry-attempt sd) max-r)
          (not (session/context-overflow-error? (:error-message last-m)))
          (session/retry-error? (:stop-reason last-m)
                                (:error-message last-m)
                                (:http-status last-m))))))

(defn- dispatch! [data action-key]
  (when-let [af (:actions-fn data)]
    (af action-key data)))

;; ============================================================
;; Statechart definition
;; ============================================================

(def session-chart
  (chart/statechart {:id :agent-session}

    ;; ── idle ─────────────────────────────────────────────
                    (ele/state {:id :idle}
                               (ele/transition {:event :session/prompt        :target :streaming})
                               (ele/transition {:event :session/compact-start :target :compacting})
                               (ele/transition {:event :session/reset         :target :idle}))

    ;; ── streaming ────────────────────────────────────────
                    (ele/state {:id :streaming}
                               (ele/on-entry {}
                                             (ele/script {:expr (fn [_env data] (dispatch! data :on-streaming-entered))}))

      ;; auto-compact guard takes priority over retry guard
                               (ele/transition
                                {:event  :session/agent-event
                                 :target :compacting
                                 :cond   (fn [_env data] (should-auto-compact? data))}
                                (ele/script {:expr (fn [_env data] (dispatch! data :on-auto-compact-triggered))}))

                               (ele/transition
                                {:event  :session/agent-event
                                 :target :retrying
                                 :cond   (fn [_env data] (should-retry? data))}
                                (ele/script {:expr (fn [_env data] (dispatch! data :on-retry-triggered))}))

                               (ele/transition
                                {:event  :session/agent-event
                                 :target :idle
                                 :cond   (fn [_env data] (agent-end-event? data))}
                                (ele/script {:expr (fn [_env data] (dispatch! data :on-agent-done))}))

                               (ele/transition {:event :session/abort :target :idle}
                                               (ele/script {:expr (fn [_env data] (dispatch! data :on-abort))}))
                               (ele/transition {:event :session/reset :target :idle}))

    ;; ── compacting ───────────────────────────────────────
                    (ele/state {:id :compacting}
                               (ele/on-entry {}
                                             (ele/script {:expr (fn [_env data] (dispatch! data :on-compacting-entered))}))
                               (ele/transition {:event :session/compact-done :target :idle}
                                               (ele/script {:expr (fn [_env data] (dispatch! data :on-compact-done))}))
                               (ele/transition {:event :session/reset :target :idle}))

    ;; ── retrying ─────────────────────────────────────────
                    (ele/state {:id :retrying}
                               (ele/on-entry {}
                                             (ele/script {:expr (fn [_env data] (dispatch! data :on-retrying-entered))}))
                               (ele/transition {:event :session/retry-done :target :streaming}
                                               (ele/script {:expr (fn [_env data] (dispatch! data :on-retry-resume))}))
                               (ele/transition {:event :session/reset :target :idle}))))

;; ============================================================
;; Statechart env / session management
;; ============================================================

(defn create-sc-env
  "Create and return a statechart environment with the session chart registered."
  []
  (let [env (simple/simple-env)]
    (simple/register! env :agent-session session-chart)
    env))

(defn- get-working-memory [sc-env session-id]
  (sp/get-working-memory (::sc/working-memory-store sc-env) sc-env session-id))

(defn- save-working-memory! [sc-env session-id wm]
  (sp/save-working-memory! (::sc/working-memory-store sc-env) sc-env session-id wm))

(defn start-session!
  "Start the statechart session in `sc-env` with `session-id`.
  `initial-data` is merged into the flat working memory so guard fns can access it."
  [sc-env session-id initial-data]
  (let [wm (sp/start! (::sc/processor sc-env) sc-env :agent-session
                      (merge initial-data {::sc/session-id session-id}))]
    (save-working-memory! sc-env session-id wm)
    wm))

(defn send-event!
  "Send `event-kw` to the session statechart, optionally merging `extra-data`
  into the working memory first so guards can inspect it."
  ([sc-env session-id event-kw]
   (send-event! sc-env session-id event-kw nil))
  ([sc-env session-id event-kw extra-data]
   (let [wm   (get-working-memory sc-env session-id)
         ;; merge extra-data into working memory data model before processing
         wm'  (if extra-data
                (update wm ::sc/data-model merge extra-data)
                wm)
         _    (save-working-memory! sc-env session-id wm')
         evt  (evts/new-event {:name event-kw})
         wm'' (sp/process-event! (::sc/processor sc-env) sc-env wm' evt)]
     (save-working-memory! sc-env session-id wm'')
     wm'')))

(defn sc-configuration
  "Return the active statechart configuration set (e.g. #{:idle}) for `session-id`."
  [sc-env session-id]
  (when-let [wm (get-working-memory sc-env session-id)]
    (::sc/configuration wm)))

(defn sc-phase
  "Return the active phase keyword (:idle | :streaming | :compacting | :retrying)."
  [sc-env session-id]
  (first (sc-configuration sc-env session-id)))
