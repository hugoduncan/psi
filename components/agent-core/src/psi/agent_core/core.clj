(ns psi.agent-core.core
  "Stateful LLM agent: statechart lifecycle + Pathom3 introspection surface.

  Architecture
  ────────────
  ① StatechartAgent owns *lifecycle* state — idle vs streaming vs aborted.
     The statechart is the authoritative source of the agent's phase;
     the working-memory data model carries the full AgentState map.

  ② AgentState (in working memory) owns *conversation* state — messages,
     tools, queues, streaming partial.

  ③ Pathom3 resolvers expose everything through the EQL query surface,
     making the agent introspectable by any caller that holds a context.

  Nullable pattern
  ────────────────
  `create-context` returns an isolated map with its own statechart env
  and event atoms.  All public *-in functions take a context, keeping
  callers free of global state."
  (:require
   [com.fulcrologic.statecharts :as sc]
   [com.fulcrologic.statecharts.chart :as chart]
   [com.fulcrologic.statecharts.elements :as ele]
   [com.fulcrologic.statecharts.events :as evts]
   [com.fulcrologic.statecharts.protocols :as sp]
   [com.fulcrologic.statecharts.simple :as simple]
   [com.wsscode.pathom3.connect.indexes :as pci]
   [com.wsscode.pathom3.connect.operation :as pco]
   [com.wsscode.pathom3.interface.eql :as p.eql]
   [malli.core :as m]))

;; ============================================================
;; Malli schemas
;; ============================================================

(def model-schema
  [:map
   [:provider :string]
   [:id :string]
   [:api {:optional true} :string]])

(def thinking-level-schema
  [:enum :off :minimal :low :medium :high :xhigh])

(def agent-tool-schema
  [:map
   [:name :string]
   [:label :string]
   [:description :string]
   [:parameters [:or :string :map]]])

(def agent-state-schema
  [:map
   [:system-prompt :string]
   [:model model-schema]
   [:thinking-level thinking-level-schema]
   [:tools [:vector agent-tool-schema]]
   [:messages [:vector :map]]
   [:stream-message {:optional true} [:maybe :map]]
   [:pending-tool-calls [:set :string]]
   [:error {:optional true} [:maybe :string]]
   [:steering-queue [:vector :map]]
   [:follow-up-queue [:vector :map]]
   [:steering-mode [:enum :all :one-at-a-time]]
   [:follow-up-mode [:enum :all :one-at-a-time]]])

(defn valid-agent-state? [s] (m/validate agent-state-schema s))
(defn explain-agent-state [s] (m/explain agent-state-schema s))
(defn valid-agent-tool? [t] (m/validate agent-tool-schema t))

;; ============================================================
;; Config defaults
;; ============================================================

(def default-config
  {:default-model-provider  "google"
   :default-model-id        "gemini-2.5-flash-lite-preview-06-17"
   :default-thinking-level  :off
   :default-steering-mode   :one-at-a-time
   :default-follow-up-mode  :one-at-a-time})

;; ============================================================
;; Statechart definition
;; ============================================================
;;
;; States
;;   :idle     — ready for a new prompt
;;   :running  — LLM call or tool execution in progress
;;   :aborted  — abort was requested; returns to :idle on :reset
;;
;; Events
;;   :start    — PromptWithText / PromptWithMessages / Continue
;;   :done     — AgentLoopEnded (normal completion)
;;   :abort    — AbortAgent
;;   :reset    — ResetAgent (clears conversation, back to idle)
;;   :error    — streaming / tool error that terminates the run

(def agent-chart
  (chart/statechart {:id :agent}
                    (ele/state {:id :idle}
                               (ele/transition {:event :start :target :running}))

                    (ele/state {:id :running}
                               (ele/transition {:event :done  :target :idle})
                               (ele/transition {:event :error :target :idle})
                               (ele/transition {:event :abort :target :aborted}))

                    (ele/state {:id :aborted}
                               (ele/transition {:event :reset :target :idle}))))

;; ============================================================
;; Isolated context (Nullable pattern)
;; ============================================================

(defn create-context
  "Return a fresh, isolated agent context.

  Each context owns:
    - a statechart env (local memory registry + working memory store)
    - session-id (UUID) for this agent's statechart session
    - an atom holding the AgentState data (conversation, tools, queues)
    - an atom accumulating emitted events

  Pass to every -*in function.  Use in tests so nothing touches
  global state."
  []
  (let [sc-env     (simple/simple-env)
        session-id (java.util.UUID/randomUUID)]
    (simple/register! sc-env :agent agent-chart)
    {:sc-env     sc-env
     :session-id session-id
     :data-atom  (atom nil)
     :events-atom (atom [])}))

;; ============================================================
;; Statechart helpers
;; ============================================================

(defn- sc-working-memory [{:keys [sc-env session-id]}]
  (sp/get-working-memory (::sc/working-memory-store sc-env) sc-env session-id))

(defn- sc-save! [{:keys [sc-env session-id]} wm]
  (sp/save-working-memory! (::sc/working-memory-store sc-env) sc-env session-id wm))

(defn- sc-send-event!
  "Send `event-kw` to the agent's statechart session and process it
  synchronously (no event loop thread needed)."
  [{:keys [sc-env] :as ctx} event-kw]
  (let [wm  (sc-working-memory ctx)
        wm' (sp/process-event! (::sc/processor sc-env) sc-env wm
                               (evts/new-event {:name event-kw}))]
    (sc-save! ctx wm')
    wm'))

(defn sc-state-in
  "Return the current statechart configuration set (e.g. #{:idle}) for `ctx`."
  [ctx]
  (when-let [wm (sc-working-memory ctx)]
    (::sc/configuration wm)))

(defn sc-phase-in
  "Return the active phase keyword (:idle | :running | :aborted) for `ctx`.
  Returns nil if the statechart has not been started."
  [ctx]
  (first (sc-state-in ctx)))

;; ============================================================
;; AgentState helpers
;; ============================================================

(defn- initial-data
  "Build a fresh AgentState map, merging optional overrides."
  ([] (initial-data {}))
  ([overrides]
   (merge
    {:system-prompt      ""
     :model              {:provider (:default-model-provider default-config)
                          :id       (:default-model-id default-config)}
     :thinking-level     (:default-thinking-level default-config)
     :tools              []
     :messages           []
     :stream-message     nil
     :pending-tool-calls #{}
     :error              nil
     :steering-queue     []
     :follow-up-queue    []
     :steering-mode      (:default-steering-mode default-config)
     :follow-up-mode     (:default-follow-up-mode default-config)}
    overrides)))

(defn get-data-in
  "Return the current AgentState data map from `ctx`."
  [ctx]
  @(:data-atom ctx))

(defn- swap-data!
  "Apply `f` to the data atom in `ctx`, return the new value."
  [ctx f & args]
  (apply swap! (:data-atom ctx) f args))

;; ============================================================
;; Event emission
;; ============================================================

(defn emit-in!
  "Append `event` to the event log in `ctx`."
  [ctx event]
  (swap! (:events-atom ctx) conj event)
  nil)

(defn drain-events-in!
  "Remove and return all pending events from `ctx` (atomic swap)."
  [ctx]
  (let [a (:events-atom ctx)]
    (loop []
      (let [v @a]
        (if (compare-and-set! a v [])
          v
          (recur))))))

;; ============================================================
;; Lifecycle — CreateAgent / ResetAgent
;; ============================================================

(defn create-agent-in!
  "Start the statechart and initialise AgentState in `ctx`.
  Optional `initial` map is merged into the default state."
  ([ctx] (create-agent-in! ctx {}))
  ([ctx initial]
   (let [{:keys [sc-env session-id]} ctx
         data  (initial-data initial)]
     (when-not (valid-agent-state? data)
       (throw (ex-info "Invalid initial agent state"
                       {:errors (explain-agent-state data)})))
     ;; Start the statechart session (initial state = :idle)
     (let [wm0 (sp/start! (::sc/processor sc-env) sc-env :agent
                          {::sc/session-id session-id})]
       (sc-save! ctx wm0))
     (reset! (:data-atom ctx) data)
     data)))

(defn reset-agent-in!
  "Clear conversation history and streaming state; keep configuration.
  Drives statechart :reset event when agent is :aborted, otherwise
  simply clears the data (must be :idle to call this)."
  [ctx]
  (when (= :aborted (sc-phase-in ctx))
    (sc-send-event! ctx :reset))
  (swap-data! ctx assoc
              :messages          []
              :stream-message    nil
              :pending-tool-calls #{}
              :error             nil
              :steering-queue    []
              :follow-up-queue   []))

;; ============================================================
;; Configuration setters
;; ============================================================

(defn set-system-prompt-in! [ctx prompt]
  (swap-data! ctx assoc :system-prompt prompt))

(defn set-model-in! [ctx model]
  (swap-data! ctx assoc :model model))

(defn set-thinking-level-in! [ctx level]
  (swap-data! ctx assoc :thinking-level level))

(defn set-tools-in! [ctx tools]
  (swap-data! ctx assoc :tools tools))

;; ============================================================
;; Message management
;; ============================================================

(defn replace-messages-in! [ctx messages]
  (swap-data! ctx assoc :messages (vec messages)))

(defn append-message-in! [ctx message]
  (swap-data! ctx update :messages conj message))

(defn clear-messages-in! [ctx]
  (swap-data! ctx assoc :messages []))

;; ============================================================
;; Queue management
;; ============================================================

(defn queue-steering-in! [ctx message]
  (swap-data! ctx update :steering-queue conj message))

(defn queue-follow-up-in! [ctx message]
  (swap-data! ctx update :follow-up-queue conj message))

(defn clear-steering-queue-in! [ctx]
  (swap-data! ctx assoc :steering-queue []))

(defn clear-follow-up-queue-in! [ctx]
  (swap-data! ctx assoc :follow-up-queue []))

;; ============================================================
;; Derived predicates
;; ============================================================

(defn idle-in?
  "True when the statechart phase is :idle."
  [ctx]
  (= :idle (sc-phase-in ctx)))

(defn running-in?
  "True when the statechart phase is :running."
  [ctx]
  (= :running (sc-phase-in ctx)))

(defn has-queued-messages-in?
  "True when steering or follow-up queues are non-empty."
  [ctx]
  (let [d (get-data-in ctx)]
    (or (seq (:steering-queue d))
        (seq (:follow-up-queue d)))))

(defn dequeue-messages
  "Return messages to inject from `queue` according to `mode`.
  :one-at-a-time → [first]; :all → entire queue (unchanged)."
  [queue mode]
  (case mode
    :one-at-a-time (if (seq queue) [(first queue)] [])
    :all           (vec queue)))

(defn check-queues-in
  "Return {:action :steering|:follow-up|:done :messages [...]}
  describing what should be delivered next, without mutating state."
  [ctx]
  (let [d        (get-data-in ctx)
        steering (dequeue-messages (:steering-queue d) (:steering-mode d))
        follow   (dequeue-messages (:follow-up-queue d) (:follow-up-mode d))]
    (cond
      (seq steering) {:action :steering  :messages steering}
      (seq follow)   {:action :follow-up :messages follow}
      :else          {:action :done      :messages []})))

(defn drain-steering-in! [ctx messages]
  (swap-data! ctx update :steering-queue (partial drop (count messages))))

(defn drain-follow-up-in! [ctx messages]
  (swap-data! ctx update :follow-up-queue (partial drop (count messages))))

;; ============================================================
;; Agent loop — streaming state transitions
;;
;; These mutate ctx data and/or the statechart, and emit events.
;; They do NOT call the LLM or execute tools — that is the caller's job.
;; ============================================================

(defn start-loop-in!
  "Drive statechart :start, inject new-messages, emit agent_start + turn_start.
  Corresponds to AgentLoopStarted rule."
  [ctx new-messages]
  (sc-send-event! ctx :start)
  (swap-data! ctx (fn [d]
                    (-> d
                        (assoc :stream-message nil
                               :error          nil)
                        (update :messages into new-messages))))
  (doseq [msg new-messages]
    (emit-in! ctx {:type :message-start :message msg})
    (emit-in! ctx {:type :message-end   :message msg}))
  (emit-in! ctx {:type :agent-start})
  (emit-in! ctx {:type :turn-start})
  (get-data-in ctx))

(defn begin-stream-in!
  "Record partial assistant message at stream start. Emits message_start."
  [ctx partial-message]
  (let [data (swap-data! ctx assoc :stream-message partial-message)]
    (emit-in! ctx {:type :message-start :message partial-message})
    data))

(defn update-stream-in!
  "Update partial message with delta content. Emits message_update."
  [ctx updated-message]
  (let [data (swap-data! ctx assoc :stream-message updated-message)]
    (emit-in! ctx {:type :message-update :message updated-message})
    data))

(defn end-stream-in!
  "Commit final message to history, clear stream-message. Emits message_end."
  [ctx final-message]
  (let [data (swap-data! ctx (fn [d]
                               (-> d
                                   (update :messages conj final-message)
                                   (assoc  :stream-message nil))))]
    (emit-in! ctx {:type :message-end :message final-message})
    data))

(defn record-tool-result-in!
  "Append a tool result message, clear its pending tool-call id, and emit
   message_start / message_end."
  [ctx tool-result-msg]
  (let [tool-call-id (:tool-call-id tool-result-msg)
        data         (swap-data! ctx
                                 (fn [d]
                                   (cond-> (update d :messages conj tool-result-msg)
                                     tool-call-id
                                     (update :pending-tool-calls disj tool-call-id))))]
    (emit-in! ctx {:type :message-start :message tool-result-msg})
    (emit-in! ctx {:type :message-end   :message tool-result-msg})
    data))

(defn emit-turn-end-in!
  "Emit turn_end with assistant message and collected tool results."
  [ctx assistant-msg tool-results]
  (emit-in! ctx {:type         :turn-end
                 :message      assistant-msg
                 :tool-results tool-results})
  (get-data-in ctx))

(defn emit-tool-start-in!
  "Emit tool_execution_start for `tool-call` and mark it pending."
  [ctx tool-call]
  (let [tool-call-id (:id tool-call)]
    (swap-data! ctx update :pending-tool-calls (fnil conj #{}) tool-call-id)
    (emit-in! ctx {:type         :tool-execution-start
                   :tool-call-id tool-call-id
                   :tool-name    (:name tool-call)
                   :args         (:arguments tool-call)})
    (get-data-in ctx)))

(defn emit-tool-end-in!
  "Emit tool_execution_end for `tool-call` with `result` and `is-error?`.
   Pending state is cleared when the tool result is recorded."
  [ctx tool-call result is-error?]
  (emit-in! ctx {:type         :tool-execution-end
                 :tool-call-id (:id tool-call)
                 :tool-name    (:name tool-call)
                 :result       result
                 :is-error     is-error?})
  (get-data-in ctx))

(defn end-loop-in!
  "Drive statechart :done, clear streaming fields, emit agent_end.
  Corresponds to AgentLoopEnded rule."
  [ctx]
  (sc-send-event! ctx :done)
  (let [data (swap-data! ctx assoc
                         :stream-message     nil
                         :pending-tool-calls #{})]
    (emit-in! ctx {:type     :agent-end
                   :messages (:messages data)})
    data))

(defn end-loop-on-error-in!
  "Drive statechart :error, record error message, emit agent_end."
  [ctx error-msg]
  (sc-send-event! ctx :error)
  (let [data (swap-data! ctx assoc
                         :stream-message     nil
                         :pending-tool-calls #{}
                         :error              error-msg)]
    (emit-in! ctx {:type     :agent-end
                   :messages (:messages data)})
    data))

(defn abort-in!
  "Drive statechart :abort. Partial assistant message (if any) must
  already have been committed by end-stream-in! with stopReason=aborted."
  [ctx]
  (if (running-in? ctx)
    (do (sc-send-event! ctx :abort)
        (swap-data! ctx assoc
                    :stream-message     nil
                    :pending-tool-calls #{}))
    (get-data-in ctx)))

;; ============================================================
;; Introspection — diagnostic snapshot
;; ============================================================

(defn diagnostics-in
  "Return a snapshot map suitable for logging or EQL introspection."
  [ctx]
  (let [data  (get-data-in ctx)
        phase (sc-phase-in ctx)]
    {:phase              phase
     :is-streaming       (= :running phase)
     :message-count      (count (:messages data))
     :pending-tool-calls (:pending-tool-calls data)
     :has-error          (some? (:error data))
     :error              (:error data)
     :steering-depth     (count (:steering-queue data))
     :follow-up-depth    (count (:follow-up-queue data))
     :model              (:model data)
     :thinking-level     (:thinking-level data)
     :tool-count         (count (:tools data))}))

;; ============================================================
;; Pathom3 resolvers — introspection surface
;; ============================================================
;;
;; Attribute namespace: :psi.agent/
;; Seed key:           :psi/agent-ctx   — the context map itself
;;

(pco/defresolver agent-phase
  "Resolve :psi.agent/phase — statechart configuration set."
  [{:keys [psi/agent-ctx]}]
  {::pco/input  [:psi/agent-ctx]
   ::pco/output [:psi.agent/phase
                 :psi.agent/is-streaming
                 :psi.agent/is-idle]}
  (let [phase (sc-phase-in agent-ctx)]
    {:psi.agent/phase        phase
     :psi.agent/is-streaming (= :running phase)
     :psi.agent/is-idle      (= :idle phase)}))

(pco/defresolver agent-data
  "Resolve core AgentState fields."
  [{:keys [psi/agent-ctx]}]
  {::pco/input  [:psi/agent-ctx]
   ::pco/output [:psi.agent/system-prompt
                 :psi.agent/model
                 :psi.agent/thinking-level
                 :psi.agent/tool-count
                 :psi.agent/message-count
                 :psi.agent/has-stream-message
                 :psi.agent/error
                 :psi.agent/has-error
                 :psi.agent/pending-tool-calls
                 :psi.agent/steering-depth
                 :psi.agent/follow-up-depth
                 :psi.agent/steering-mode
                 :psi.agent/follow-up-mode
                 :psi.agent/has-queued-messages]}
  (let [d (get-data-in agent-ctx)]
    {:psi.agent/system-prompt      (:system-prompt d)
     :psi.agent/model              (:model d)
     :psi.agent/thinking-level     (:thinking-level d)
     :psi.agent/tool-count         (count (:tools d))
     :psi.agent/message-count      (count (:messages d))
     :psi.agent/has-stream-message (some? (:stream-message d))
     :psi.agent/error              (:error d)
     :psi.agent/has-error          (some? (:error d))
     :psi.agent/pending-tool-calls (:pending-tool-calls d)
     :psi.agent/steering-depth     (count (:steering-queue d))
     :psi.agent/follow-up-depth    (count (:follow-up-queue d))
     :psi.agent/steering-mode      (:steering-mode d)
     :psi.agent/follow-up-mode     (:follow-up-mode d)
     :psi.agent/has-queued-messages (has-queued-messages-in? agent-ctx)}))

(pco/defresolver agent-messages
  "Resolve full message history and stream-message."
  [{:keys [psi/agent-ctx]}]
  {::pco/input  [:psi/agent-ctx]
   ::pco/output [:psi.agent/messages
                 :psi.agent/stream-message]}
  (let [d (get-data-in agent-ctx)]
    {:psi.agent/messages       (:messages d)
     :psi.agent/stream-message (:stream-message d)}))

(pco/defresolver agent-tools
  "Resolve registered tools list."
  [{:keys [psi/agent-ctx]}]
  {::pco/input  [:psi/agent-ctx]
   ::pco/output [:psi.agent/tools]}
  {:psi.agent/tools (:tools (get-data-in agent-ctx))})

(pco/defresolver agent-diagnostics
  "Resolve full diagnostics snapshot."
  [{:keys [psi/agent-ctx]}]
  {::pco/input  [:psi/agent-ctx]
   ::pco/output [:psi.agent/diagnostics]}
  {:psi.agent/diagnostics (diagnostics-in agent-ctx)})

(pco/defresolver agent-statechart-config
  "Resolve raw statechart configuration set (set of active state keywords)."
  [{:keys [psi/agent-ctx]}]
  {::pco/input  [:psi/agent-ctx]
   ::pco/output [:psi.agent/sc-configuration]}
  {:psi.agent/sc-configuration (sc-state-in agent-ctx)})

(def all-resolvers
  [agent-phase
   agent-data
   agent-messages
   agent-tools
   agent-diagnostics
   agent-statechart-config])

;; ============================================================
;; Pathom3 environment — built from the component's own resolvers
;; ============================================================

(defn build-env
  "Build a Pathom3 EQL environment for querying an agent context.

  Usage:
    (def env (build-env))
    (p.eql/process env {:psi/agent-ctx ctx} [:psi.agent/phase :psi.agent/message-count])"
  []
  (pci/register all-resolvers))

(defonce ^:private query-env (atom nil))

(defn invalidate-query-env!
  "Drop the cached Pathom env so the next query rebuilds from the current
   resolver vars after reload."
  []
  (reset! query-env nil)
  nil)

(defn- ensure-query-env! []
  (or @query-env (reset! query-env (build-env))))

(defn query-in
  "Run an EQL `q` against `ctx` through the component's Pathom graph.

  Example:
    (query-in ctx [:psi.agent/phase :psi.agent/message-count])"
  [ctx q]
  (p.eql/process (ensure-query-env!)
                 {:psi/agent-ctx ctx}
                 q))
