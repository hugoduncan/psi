(ns psi.agent-session.workflow-statechart-runtime
  "Phase A workflow statechart runtime scaffolding.

   This namespace owns the execution context around the hierarchical workflow
   statechart:
   - working-memory seed creation
   - event queue management
   - action dispatch boundary
   - active-chart-state -> workflow-run status projection

   Slice 2 scope deliberately stops short of full step execution. It establishes
   context shape, attempt identity semantics, and projection policy so later
   slices can move actor/judge execution into entry/exit actions without changing
   the runtime envelope again."
  (:require
   [clojure.string :as str]
   [com.fulcrologic.statecharts :as sc]
   [com.fulcrologic.statecharts.data-model.working-memory-data-model :as wmdm]
   [com.fulcrologic.statecharts.events :as evts]
   [com.fulcrologic.statecharts.protocols :as sp]
   [com.fulcrologic.statecharts.simple :as simple]
   [psi.agent-session.prompt-control :as prompt-control]
   [psi.agent-session.prompt-recording :as prompt-recording]
   [psi.agent-session.workflow-attempts :as workflow-attempts]
   [psi.agent-session.workflow-judge :as workflow-judge]
   [psi.agent-session.workflow-progression-recording :as workflow-progression-recording]
   [psi.agent-session.workflow-runtime :as workflow-runtime]
   [psi.agent-session.workflow-statechart :as workflow-statechart]
   [psi.agent-session.workflow-step-prep :as workflow-step-prep]))

(defn- now []
  (java.time.Instant/now))

(defn- initial-step-outputs
  [workflow-run]
  (into {}
        (keep (fn [[step-id step-run]]
                (when-let [accepted (:accepted-result step-run)]
                  [step-id accepted])))
        (:step-runs workflow-run)))

(defn- initial-iteration-counts
  [workflow-run]
  (into {}
        (map (fn [[step-id step-run]]
               [step-id (or (:iteration-count step-run) 0)]))
        (:step-runs workflow-run)))

(defn- initial-attempt-counts
  [workflow-run]
  (into {}
        (map (fn [[step-id step-run]]
               [step-id (count (:attempts step-run))]))
        (:step-runs workflow-run)))

(defn- initial-attempt-ids
  [workflow-run]
  (into {}
        (keep (fn [[step-id step-run]]
                (when-let [attempt-id (:attempt-id (last (:attempts step-run)))]
                  [step-id attempt-id])))
        (:step-runs workflow-run)))

(defn- initial-sessions
  [workflow-run]
  (into {}
        (keep (fn [[step-id step-run]]
                (when-let [execution-session-id (:execution-session-id (last (:attempts step-run)))]
                  [step-id execution-session-id])))
        (:step-runs workflow-run)))

(defn create-working-memory
  "Create the authoritative Phase A execution memory for a workflow run.

   This is stored in an atom and snapshotted into the flat statechart data model
   at event-processing boundaries so guards read a pure snapshot while actions can
   still evolve execution state between events."
  [ctx parent-session-id run-id]
  (let [workflow-run (workflow-runtime/workflow-run-in @(:state* ctx) run-id)
        steps (get-in workflow-run [:effective-definition :steps])]
    {:workflow-run-id run-id
     :parent-session-id parent-session-id
     :workflow-input (:workflow-input workflow-run)
     :step-outputs (initial-step-outputs workflow-run)
     :iteration-counts (initial-iteration-counts workflow-run)
     :judge-results {}
     :sessions (initial-sessions workflow-run)
     :attempt-ids (initial-attempt-ids workflow-run)
     :attempt-counts (initial-attempt-counts workflow-run)
     :actor-retries {}
     :actor-retry-limits (into {}
                               (map (fn [[step-id step-def]]
                                      [step-id (or (get-in step-def [:retry-policy :max-attempts]) 1)]))
                               steps)
     :judge-retries {}
     :blocked-step-id nil
     :pending-actor-result nil
     :pending-judge-result nil
     :pending-routing nil
     :current-step-id (:current-step-id workflow-run)
     :created-at (now)
     :updated-at (now)}))

(defn step-id-from-configuration
  "Project the logical workflow step id from an active hierarchical chart config.

   The returned step id is logical (`plan`, `build`, etc.), not the leaf state id.
   Compound-state parent ids are ignored in favor of the leaf `.acting`/`.judging`/
   `.blocked` state ids."
  [configuration]
  (some (fn [state-id]
          (when (keyword? state-id)
            (let [s (str state-id)]
              (when (str/starts-with? s ":step/")
                (let [suffix (subs s 6)]
                  (if-let [idx (str/index-of suffix ".")]
                    (subs suffix 0 idx)
                    suffix))))))
        configuration))

(defn run-status-from-configuration
  "Derive public workflow-run status from active hierarchical chart configuration."
  [configuration]
  (cond
    (contains? configuration :pending) :pending
    (contains? configuration :completed) :completed
    (contains? configuration :failed) :failed
    (contains? configuration :cancelled) :cancelled
    (some #(str/ends-with? (name %) ".blocked") configuration) :blocked
    :else :running))

(defn sync-run-projection!
  "Project active chart status/current-step-id onto the canonical workflow run."
  [ctx run-id working-memory* configuration]
  (let [status (run-status-from-configuration configuration)
        step-id (or (step-id-from-configuration configuration)
                    (:current-step-id @working-memory*))]
    (swap! (:state* ctx)
           update-in
           [:workflows :runs run-id]
           (fn [workflow-run]
             (cond-> (assoc workflow-run
                            :status status
                            :current-step-id (case status
                                               :completed nil
                                               step-id)
                            :updated-at (now))
               (= status :blocked)
               (assoc :blocked {:step-id (:blocked-step-id @working-memory*)})

               (not= status :blocked)
               (assoc :blocked nil)

               (contains? #{:completed :failed :cancelled} status)
               (assoc :finished-at (or (:finished-at workflow-run) (now))))))))

(defn assistant-message-text
  [assistant-message]
  (or (some->> (:content assistant-message)
               (filter map?)
               (keep (fn [block]
                       (when (= :text (:type block))
                         (:text block))))
               seq
               (str/join "\n"))
      (when (string? (:content assistant-message))
        (:content assistant-message))
      ""))

(defn- assistant-error-message
  [assistant-message]
  (or (:error-message assistant-message)
      (some->> (:content assistant-message)
               (filter map?)
               (keep (fn [block]
                       (when (= :error (:type block))
                         (:text block))))
               seq
               (str/join "\n"))
      "Assistant turn ended in error"))

(defn- assistant-turn-classification
  [assistant-message]
  (prompt-recording/classify-assistant-message assistant-message))

(defn- execution-failure-payload
  [execution-session-id assistant-message]
  (let [{:keys [turn/outcome]} (assistant-turn-classification assistant-message)]
    (cond-> {:message (assistant-error-message assistant-message)}
      (:stop-reason assistant-message)
      (assoc :stop-reason (:stop-reason assistant-message))

      (= :turn.outcome/error outcome)
      (assoc :turn-outcome outcome)

      execution-session-id
      (assoc :session-id execution-session-id))))

(defn- enqueue-event!
  [event-queue* working-memory* event data]
  (swap! event-queue* conj {:event event
                            :data (merge {:current-step-id (:current-step-id @working-memory*)
                                          :iteration-counts (:iteration-counts @working-memory*)}
                                         data)}))

(defn queue-event!
  "Enqueue a workflow event onto the FIFO queue using the current authoritative
   working-memory snapshot as the event-data base."
  [{:keys [event-queue* working-memory*]} event data]
  (enqueue-event! event-queue* working-memory* event data))

(def ^:private max-drain-events
  1000)

(defn terminal-configuration?
  [configuration]
  (boolean (some configuration [:completed :failed :cancelled])))

(defn make-workflow-actions
  "Create the Phase A workflow actions dispatcher.

   Slice 2 scope:
   - allocate fresh attempt ids on `.acting` entry
   - preserve same attempt id through `.judging`
   - record blocked-step metadata on `.blocked` entry
   - expose snapshot-only retry predicate placeholder
   - keep the workflow-run projection synchronized for attempt creation metadata"
  [ctx parent-session-id run-id working-memory* event-queue*]
  (fn [action-key data]
    (let [step-id (:step-id data)]
      (case action-key
        :retry-available?
        (let [attempt-count (get-in @working-memory* [:attempt-counts step-id] 0)
              max-attempts (get-in @working-memory* [:actor-retry-limits step-id] 1)]
          (< attempt-count max-attempts))

        :step/enter
        (let [attempt-id (str (java.util.UUID/randomUUID))
              workflow-run (workflow-runtime/workflow-run-in @(:state* ctx) run-id)
              step-config (workflow-step-prep/resolve-step-session-config ctx parent-session-id workflow-run step-id)
              {:keys [prompt]} (workflow-step-prep/step-prompt workflow-run step-id)
              preloaded-messages (workflow-step-prep/materialize-step-session-preload ctx workflow-run step-id)
              {:keys [attempt execution-session]}
              (workflow-attempts/create-step-attempt-session!
               ctx
               parent-session-id
               (cond-> {:workflow-run-id run-id
                        :workflow-step-id step-id
                        :attempt-id attempt-id
                        :session-name (str "workflow " step-id " attempt")
                        :tool-defs (:tool-defs step-config)
                        :thinking-level (:thinking-level step-config)}
                 (:developer-prompt step-config)
                 (assoc :developer-prompt (:developer-prompt step-config)
                        :developer-prompt-source :explicit)

                 (:prompt-mode step-config)
                 (assoc :prompt-mode (:prompt-mode step-config))

                 (:skills step-config)
                 (assoc :skills (:skills step-config))

                 (:model step-config)
                 (assoc :model (:model step-config))

                 (contains? step-config :prompt-component-selection)
                 (assoc :prompt-component-selection (:prompt-component-selection step-config))

                 preloaded-messages
                 (assoc :preloaded-messages preloaded-messages)))]
          (swap! working-memory*
                 (fn [wm]
                   (-> wm
                       (assoc-in [:attempt-ids step-id] attempt-id)
                       (assoc-in [:sessions step-id] (:session-id execution-session))
                       (update-in [:attempt-counts step-id] (fnil inc 0))
                       (assoc :current-step-id step-id
                              :blocked-step-id nil
                              :pending-actor-result nil
                              :updated-at (now))
                       (update-in [:iteration-counts step-id] (fnil inc 0)))))
          (swap! (:state* ctx)
                 (fn [state]
                   (-> state
                       (update-in [:workflows :runs run-id]
                                  #(workflow-attempts/append-attempt-to-run % step-id attempt))
                       (workflow-progression-recording/start-latest-attempt run-id step-id)
                       (workflow-progression-recording/increment-iteration-count run-id step-id))))
          (try
            (let [execution-result (prompt-control/prompt-execution-result-in! ctx (:session-id execution-session) prompt)
                  assistant-message (:execution-result/assistant-message execution-result)
                  {:keys [turn/outcome]} (assistant-turn-classification assistant-message)
                  failure-payload (when (= :turn.outcome/error outcome)
                                    (execution-failure-payload (:session-id execution-session) assistant-message))]
              (if failure-payload
                (do
                  (swap! working-memory* assoc :pending-actor-result {:kind :failure
                                                                      :payload failure-payload
                                                                      :step-id step-id
                                                                      :attempt-id attempt-id
                                                                      :updated-at (now)})
                  (enqueue-event! event-queue* working-memory* :actor/failed {}))
                (let [envelope {:outcome :ok
                                :outputs {:text (assistant-message-text assistant-message)}}]
                  (swap! working-memory* assoc :pending-actor-result {:kind (if (= :blocked (:outcome envelope)) :blocked :success)
                                                                      :payload envelope
                                                                      :step-id step-id
                                                                      :attempt-id attempt-id
                                                                      :updated-at (now)})
                  (enqueue-event! event-queue* working-memory*
                                  (if (= :blocked (:outcome envelope)) :actor/blocked :actor/done)
                                  {}))))
            (catch Exception e
              (let [failure-payload {:message (ex-message e)}]
                (swap! working-memory* assoc :pending-actor-result {:kind :failure
                                                                    :payload failure-payload
                                                                    :step-id step-id
                                                                    :attempt-id attempt-id
                                                                    :updated-at (now)})
                (enqueue-event! event-queue* working-memory* :actor/failed {}))))
          nil)

        :step/record-result
        (let [{:keys [payload]} (:pending-actor-result @working-memory*)
              workflow-run (workflow-runtime/workflow-run-in @(:state* ctx) run-id)
              judged-step? (some? (get-in workflow-run [:effective-definition :steps step-id :judge]))]
          (if judged-step?
            (swap! (:state* ctx)
                   workflow-progression-recording/record-actor-result run-id step-id payload)
            (swap! (:state* ctx)
                   workflow-progression-recording/record-step-result run-id step-id payload))
          (swap! working-memory*
                 (fn [wm]
                   (-> wm
                       (assoc :pending-actor-result nil
                              :step-outputs (assoc (:step-outputs wm) step-id payload)
                              :updated-at (now)))))
          nil)

        :step/record-failure
        (let [{:keys [payload]} (:pending-actor-result @working-memory*)]
          (swap! (:state* ctx)
                 workflow-progression-recording/record-attempt-execution-failure run-id step-id payload)
          (swap! working-memory*
                 (fn [wm]
                   (-> wm
                       (assoc :pending-actor-result nil
                              :updated-at (now)))))
          nil)

        :judge/enter
        (let [workflow-run (workflow-runtime/workflow-run-in @(:state* ctx) run-id)
              judge-spec (get-in workflow-run [:effective-definition :steps step-id :judge])
              routing-table (or (get-in workflow-run [:effective-definition :steps step-id :on]) {})
              actor-session-id (get-in @working-memory* [:sessions step-id])
              judge-result (workflow-judge/execute-judge!
                            ctx
                            parent-session-id
                            actor-session-id
                            judge-spec
                            routing-table
                            {:current-step-id step-id
                             :step-order (get-in workflow-run [:effective-definition :step-order])
                             :step-runs (get-in @(:state* ctx) [:workflows :runs run-id :step-runs])})
              routing-result (:routing-result judge-result)]
          (swap! working-memory*
                 (fn [wm]
                   (-> wm
                       (assoc :current-step-id step-id
                              :pending-judge-result judge-result
                              :pending-routing routing-result
                              :updated-at (now))
                       (assoc-in [:judge-results step-id] judge-result)
                       (assoc-in [:sessions (str step-id "-judge")] (:judge-session-id judge-result)))))
          (enqueue-event! event-queue* working-memory*
                          (if (= :no-match (:action routing-result)) :judge/no-match :judge/signal)
                          (cond-> {}
                            (:judge-event judge-result) (assoc :signal (:judge-event judge-result))))
          nil)

        :judge/record
        (let [judge-result (:pending-judge-result @working-memory*)
              routing-result (:routing-result judge-result)]
          (swap! (:state* ctx)
                 workflow-progression-recording/record-judge-result run-id step-id judge-result)
          (swap! (:state* ctx)
                 (fn [state]
                   (update-in state [:workflows :runs run-id]
                              (fn [workflow-run]
                                (case (:action routing-result)
                                  :goto
                                  (-> workflow-run
                                      (assoc :current-step-id (:target routing-result)
                                             :status :running))

                                  :complete
                                  (-> workflow-run
                                      (assoc :status :completed
                                             :current-step-id nil
                                             :finished-at (or (:finished-at workflow-run) (now))
                                             :terminal-outcome {:outcome :completed
                                                                :step-id step-id
                                                                :attempt-id (:attempt-id (workflow-progression-recording/latest-attempt workflow-run step-id))
                                                                :result-envelope (get-in workflow-run [:step-runs step-id :accepted-result])}))

                                  (-> workflow-run
                                      (assoc :status :failed
                                             :finished-at (or (:finished-at workflow-run) (now))
                                             :terminal-outcome {:outcome :failed
                                                                :reason (or (:reason routing-result) :judge-no-match)
                                                                :step-id step-id
                                                                :attempt-id (:attempt-id (workflow-progression-recording/latest-attempt workflow-run step-id))
                                                                :judge-output (:judge-output judge-result)})))))))
          (swap! working-memory*
                 (fn [wm]
                   (-> wm
                       (assoc :pending-judge-result nil
                              :pending-routing nil
                              :updated-at (now)))))
          nil)

        :step/block
        (do
          (swap! working-memory*
                 (fn [wm]
                   (-> wm
                       (assoc :blocked-step-id step-id
                              :current-step-id step-id
                              :updated-at (now)))))
          nil)

        :terminal/record
        (do
          (swap! working-memory* assoc :updated-at (now))
          nil)

        :enqueue-event
        (do
          (enqueue-event! event-queue* working-memory* (:event data) (:data data))
          nil)

        nil))))

(defn create-workflow-context
  "Create a statechart execution context for Phase A workflow execution.

   Returns a map with the registered env/session, authoritative working memory,
   FIFO event queue, and workflow actions dispatcher."
  ([ctx run-id]
   (create-workflow-context ctx nil run-id))
  ([ctx parent-session-id run-id]
   (let [workflow-run (workflow-runtime/workflow-run-in @(:state* ctx) run-id)
         chart (workflow-statechart/compile-hierarchical-chart (:effective-definition workflow-run))
         env (simple/simple-env)
         sc-session-id (java.util.UUID/randomUUID)
         working-memory* (atom (create-working-memory ctx parent-session-id run-id))
         event-queue* (atom [])
         actions-fn (make-workflow-actions ctx parent-session-id run-id working-memory* event-queue*)]
     (simple/register! env :workflow-run chart)
     (let [wm0 (sp/start! (::sc/processor env) env :workflow-run
                          {::sc/session-id sc-session-id
                           ::wmdm/data-model (assoc @working-memory* :actions-fn actions-fn)})]
       (sp/save-working-memory! (::sc/working-memory-store env) env sc-session-id wm0)
       {:ctx ctx
        :run-id run-id
        :parent-session-id parent-session-id
        :env env
        :sc-session-id sc-session-id
        :wm wm0
        :working-memory* working-memory*
        :event-queue* event-queue*
        :actions-fn actions-fn}))))

(defn process-event!
  "Process one workflow statechart event against a fresh working-memory snapshot,
   then project active chart status back onto the workflow run."
  [{:keys [ctx run-id env sc-session-id working-memory*] :as wf-ctx} wm event data]
  (let [wm' (update wm ::wmdm/data-model merge (assoc @working-memory* :actions-fn (:actions-fn wf-ctx)) data)
        wm'' (sp/process-event! (::sc/processor env) env wm' (evts/new-event {:name event :data (or data {})}))]
    (sp/save-working-memory! (::sc/working-memory-store env) env sc-session-id wm'')
    (sync-run-projection! ctx run-id working-memory* (::sc/configuration wm''))
    wm''))

(defn drain-events!
  "Drain the workflow FIFO queue to quiescence.

   Once the chart reaches a terminal configuration, any queued tail events are
   discarded instead of being processed.

   A hard safety bound prevents accidental infinite event churn in tests or
   runtime regressions; overflow throws with queue/configuration context."
  [{:keys [event-queue* run-id] :as wf-ctx} wm]
  (loop [wm wm
         processed 0]
    (cond
      (terminal-configuration? (::sc/configuration wm))
      (do
        (reset! event-queue* [])
        wm)

      (>= processed max-drain-events)
      (throw (ex-info "Workflow event drain exceeded safety bound"
                      {:run-id run-id
                       :processed-events processed
                       :max-drain-events max-drain-events
                       :configuration (::sc/configuration wm)
                       :queued-events @event-queue*}))

      :else
      (let [events @event-queue*]
        (if (empty? events)
          wm
          (do
            (reset! event-queue* [])
            (let [wm' (reduce (fn [wm {:keys [event data]}]
                                (if (terminal-configuration? (::sc/configuration wm))
                                  wm
                                  (process-event! wf-ctx wm event data)))
                              wm
                              events)]
              (recur wm' (+ processed (count events))))))))))

(defn send-and-drain!
  "Send one event into the workflow chart and drain queued follow-on work."
  [wf-ctx wm event data]
  (->> (process-event! wf-ctx wm event data)
       (drain-events! wf-ctx)))
