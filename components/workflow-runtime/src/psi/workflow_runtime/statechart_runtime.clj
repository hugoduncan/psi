(ns psi.workflow-runtime.statechart-runtime
  "Phase A workflow statechart runtime scaffolding.

   This namespace now serves as the public runtime façade over smaller role-
   focused runtime namespaces:
   - state/projection + working-memory helpers
   - queue helpers
   - step execution helpers
   - delegate execution helpers
   - statechart event lifecycle/draining"
  (:require
   [com.fulcrologic.statecharts :as sc]
   [com.fulcrologic.statecharts.data-model.working-memory-data-model :as wmdm]
   [com.fulcrologic.statecharts.protocols :as sp]
   [com.fulcrologic.statecharts.simple :as simple]
   [psi.workflow-runtime.attempts :as workflow-attempts]
   [psi.workflow-runtime.core :as workflow-runtime]
   [psi.workflow-runtime.execution-adapter :as execution-adapter]
   [psi.workflow-runtime.progression-recording :as workflow-progression-recording]
   [psi.workflow-runtime.statechart :as workflow-statechart]
   [psi.workflow-runtime.statechart-runtime.delegate :as delegate]
   [psi.workflow-runtime.statechart-runtime.lifecycle :as lifecycle]
   [psi.workflow-runtime.statechart-runtime.queue :as queue]
   [psi.workflow-runtime.statechart-runtime.state :as state]
   [psi.workflow-runtime.statechart-runtime.step-execution :as step-execution]))

(declare create-workflow-context send-and-drain!)

(def create-working-memory state/create-working-memory)
(def step-id-from-configuration state/step-id-from-configuration)
(def run-status-from-configuration state/run-status-from-configuration)
(def sync-run-projection! state/sync-run-projection!)
(def assistant-message-text step-execution/assistant-message-text)
(def operation-result->invoke-step-result step-execution/operation-result->invoke-step-result)
(def queue-event! queue/queue-event!)
(def terminal-configuration? state/terminal-configuration?)

(defn- clear-pending-judge-state!
  "Clear pending judge/routing fields from working memory after a judge cycle
   completes (used by both :judge/record and :iteration/exhausted)."
  [working-memory*]
  (swap! working-memory*
         (fn [wm]
           (-> wm
               (assoc :pending-judge-result nil
                      :pending-routing nil
                      :updated-at (state/now))))))

(defn make-workflow-actions
  "Create the Phase A workflow actions dispatcher.

   Keeps the statechart-owned control-flow switch local while delegating step,
   delegate, queue, and projection sub-behaviors to lower focused helpers."
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
              step-def (state/runtime-step-def workflow-run step-id)
              invoke-step? (= :invoke (:type step-def))
              delegate-step? (= :delegate (:type step-def))
              session-step? (= :session (:type step-def))]
          (try
            (let [step-config (when session-step?
                                ((:resolve-workflow-step-session-config-fn ctx)
                                 ctx parent-session-id workflow-run step-id))
                  session-conversation (when session-step?
                                         ((:materialize-workflow-step-session-conversation-fn ctx)
                                          workflow-run step-id))
                  {:keys [preloaded-messages prompt]}
                  (if session-step?
                    ((:split-workflow-step-session-conversation-fn ctx) session-conversation)
                    {})
                  {:keys [attempt execution-session]}
                  (if session-step?
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

                       (:response-mode step-config)
                       (assoc :response-mode (:response-mode step-config))

                       (:skills step-config)
                       (assoc :skills (:skills step-config))

                       (contains? step-config :logprobs)
                       (assoc :logprobs (:logprobs step-config))

                       (contains? step-config :top-logprobs)
                       (assoc :top-logprobs (:top-logprobs step-config))

                       (contains? step-config :temperature)
                       (assoc :temperature (:temperature step-config))

                       (:model step-config)
                       (assoc :model (:model step-config))

                       (contains? step-config :speed-mode)
                       (assoc :speed-mode (:speed-mode step-config))

                       (contains? step-config :effort-override)
                       (assoc :effort-override (:effort-override step-config))

                       (:model-fallback step-config)
                       (assoc :model-fallback (:model-fallback step-config))

                       (contains? step-config :prompt-component-selection)
                       (assoc :prompt-component-selection (:prompt-component-selection step-config))

                       preloaded-messages
                       (assoc :preloaded-messages preloaded-messages)))
                    {:attempt {:attempt-id attempt-id
                               :status :pending
                               :execution-session-id nil}
                     :execution-session nil})]
              (swap! working-memory*
                     (fn [wm]
                       (cond-> (-> wm
                                   (assoc-in [:attempt-ids step-id] attempt-id)
                                   (update-in [:attempt-counts step-id] (fnil inc 0))
                                   (assoc :current-step-id step-id
                                          :blocked-step-id nil
                                          :pending-actor-result nil
                                          :updated-at (state/now))
                                   (update-in [:iteration-counts step-id] (fnil inc 0)))
                         (:session-id execution-session)
                         (assoc-in [:sessions step-id] (:session-id execution-session)))))
              (swap! (:state* ctx)
                     (fn [state-map]
                       (-> state-map
                           (update-in [:workflows :runs run-id]
                                      #(workflow-attempts/append-attempt-to-run % step-id attempt))
                           (workflow-progression-recording/start-latest-attempt run-id step-id)
                           (workflow-progression-recording/increment-iteration-count run-id step-id))))
              (cond
                invoke-step?
                (let [invoke-result (step-execution/invoke-step-runtime-result ctx parent-session-id run-id step-id step-def workflow-run)
                      {:keys [attempt-data pending-kind payload]} (step-execution/apply-invoke-step-result invoke-result)]
                  (swap! (:state* ctx)
                         workflow-progression-recording/merge-latest-attempt-data run-id step-id attempt-data)
                  (swap! working-memory* assoc :pending-actor-result {:kind pending-kind
                                                                      :payload payload
                                                                      :step-id step-id
                                                                      :attempt-id attempt-id
                                                                      :updated-at (state/now)})
                  (queue/enqueue-event! event-queue* working-memory*
                                        (case pending-kind
                                          :success :actor/done
                                          :blocked :actor/blocked
                                          :failure :actor/failed
                                          :actor/failed)
                                        {}))

                delegate-step?
                (let [{:keys [pending-kind payload]}
                      (delegate/delegate-step-runtime-result create-workflow-context send-and-drain!
                                                             (:resolve-inherited-defaults-fn ctx)
                                                             ctx parent-session-id step-id step-def workflow-run)]
                  (swap! working-memory* assoc :pending-actor-result {:kind pending-kind
                                                                      :payload payload
                                                                      :step-id step-id
                                                                      :attempt-id attempt-id
                                                                      :updated-at (state/now)})
                  (queue/enqueue-event! event-queue* working-memory*
                                        (case pending-kind
                                          :success :actor/done
                                          :blocked :actor/blocked
                                          :failure :actor/failed
                                          :actor/failed)
                                        {}))

                :else
                (step-execution/execute-session-step! ctx execution-session step-def step-id attempt-id working-memory* event-queue* prompt)))
            (catch Exception e
              (let [failure-payload {:message (ex-message e)}
                    attempt-present? (= attempt-id (get-in @working-memory* [:attempt-ids step-id]))]
                (when-not attempt-present?
                  (swap! (:state* ctx)
                         (fn [state-map]
                           (-> state-map
                               (update-in [:workflows :runs run-id]
                                          #(workflow-attempts/append-attempt-to-run % step-id {:attempt-id attempt-id
                                                                                               :status :pending
                                                                                               :execution-session-id nil}))
                               (workflow-progression-recording/start-latest-attempt run-id step-id)
                               (workflow-progression-recording/increment-iteration-count run-id step-id)))))
                (swap! working-memory*
                       (fn [wm]
                         (cond-> (-> wm
                                     (assoc :current-step-id step-id
                                            :blocked-step-id nil
                                            :pending-actor-result {:kind :failure
                                                                   :payload failure-payload
                                                                   :step-id step-id
                                                                   :attempt-id attempt-id
                                                                   :updated-at (state/now)}
                                            :updated-at (state/now)))
                           (not attempt-present?)
                           (-> (assoc-in [:attempt-ids step-id] attempt-id)
                               (update-in [:attempt-counts step-id] (fnil inc 0))
                               (update-in [:iteration-counts step-id] (fnil inc 0))))))
                (queue/enqueue-event! event-queue* working-memory* :actor/failed {}))))
          nil)

        :step/record-result
        (let [{:keys [payload]} (:pending-actor-result @working-memory*)
              workflow-run (workflow-runtime/workflow-run-in @(:state* ctx) run-id)
              judged-step? (some? (:judge (state/runtime-step-def workflow-run step-id)))]
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
                              :updated-at (state/now)))))
          nil)

        :step/record-failure
        (let [{:keys [payload]} (:pending-actor-result @working-memory*)]
          (swap! (:state* ctx)
                 workflow-progression-recording/record-attempt-execution-failure run-id step-id payload)
          (swap! working-memory*
                 (fn [wm]
                   (-> wm
                       (assoc :pending-actor-result nil
                              :updated-at (state/now)))))
          nil)

        :judge/enter
        (let [workflow-run (workflow-runtime/workflow-run-in @(:state* ctx) run-id)
              step-def (state/runtime-step-def workflow-run step-id)
              judge-spec (:judge step-def)
              routing-table (or (:on step-def) {})
              actor-session-id (get-in @working-memory* [:sessions step-id])
              judge-result (execution-adapter/execute-judge!
                            ctx
                            parent-session-id
                            actor-session-id
                            judge-spec
                            routing-table
                            {:current-step-id step-id
                             :step-order (state/runtime-step-order workflow-run)
                             :step-runs (get-in @(:state* ctx) [:workflows :runs run-id :step-runs])
                             :workflow-run-id run-id
                             :workflow-run workflow-run})
              routing-result (:routing-result judge-result)]
          (swap! working-memory*
                 (fn [wm]
                   (-> wm
                       (assoc :current-step-id step-id
                              :pending-judge-result judge-result
                              :pending-routing routing-result
                              :updated-at (state/now))
                       (assoc-in [:judge-results step-id] judge-result)
                       (assoc-in [:sessions (str step-id "-judge")] (:judge-session-id judge-result)))))
          (queue/enqueue-event! event-queue* working-memory*
                                (case (:action routing-result)
                                  :no-match :judge/no-match
                                  :fail :judge/failed
                                  :judge/signal)
                                (cond-> {}
                                  (:judge-event judge-result) (assoc :signal (:judge-event judge-result))))
          nil)

        :judge/record
        (let [judge-result (:pending-judge-result @working-memory*)
              routing-result (:routing-result judge-result)]
          (swap! (:state* ctx)
                 workflow-progression-recording/record-judge-result run-id step-id judge-result)
          (swap! (:state* ctx)
                 (fn [state-map]
                   (update-in state-map [:workflows :runs run-id]
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
                                             :finished-at (or (:finished-at workflow-run) (state/now))
                                             :terminal-outcome {:outcome :completed
                                                                :step-id step-id
                                                                :attempt-id (:attempt-id (workflow-progression-recording/latest-attempt workflow-run step-id))
                                                                :result-envelope (get-in workflow-run [:step-runs step-id :accepted-result])}))

                                  (-> workflow-run
                                      (assoc :status :failed
                                             :finished-at (or (:finished-at workflow-run) (state/now))
                                             :terminal-outcome {:outcome :failed
                                                                :reason (or (:reason routing-result) :judge-no-match)
                                                                :step-id step-id
                                                                :attempt-id (:attempt-id (workflow-progression-recording/latest-attempt workflow-run step-id))
                                                                :judge-output (:judge-output judge-result)})))))))
          (clear-pending-judge-state! working-memory*)
          nil)

        :step/block
        (do
          (swap! working-memory*
                 (fn [wm]
                   (-> wm
                       (assoc :blocked-step-id step-id
                              :current-step-id step-id
                              :updated-at (state/now)))))
          nil)

        :iteration/exhausted
        (let [iteration-count (get-in @working-memory* [:iteration-counts step-id] 0)
              workflow-run (workflow-runtime/workflow-run-in @(:state* ctx) run-id)
              step-def (state/runtime-step-def workflow-run step-id)
              last-judge-result (get-in @working-memory* [:judge-results step-id])
              last-step-output (get-in @working-memory* [:step-outputs step-id])
              last-judge-signal (:judge-event last-judge-result)]
          ;; Record judge result for the final iteration before recording terminal outcome
          (when last-judge-result
            (swap! (:state* ctx)
                   workflow-progression-recording/record-judge-result run-id step-id last-judge-result))
          (swap! (:state* ctx)
                 (fn [state-map]
                   (update-in state-map [:workflows :runs run-id]
                              (fn [wf-run]
                                (-> wf-run
                                    (assoc :status :failed
                                           :finished-at (or (:finished-at wf-run) (state/now))
                                           :terminal-outcome
                                           {:outcome :failed
                                            :reason :iteration-limit-reached
                                            :step-id step-id
                                            :iteration-count iteration-count
                                            :max-iterations (get-in step-def [:on last-judge-signal :max-iterations])
                                            :last-judge-signal last-judge-signal
                                            :last-result-text (get-in last-step-output [:outputs :final-llm-reply])}))))))
          (clear-pending-judge-state! working-memory*)
          nil)

        :terminal/record
        (do
          (swap! working-memory* assoc :updated-at (state/now))
          nil)

        :enqueue-event
        (do
          (queue/enqueue-event! event-queue* working-memory* (:event data) (:data data))
          nil)

        nil))))

(defn create-workflow-context
  "Create a statechart execution context for Phase A workflow execution."
  ([ctx run-id]
   (create-workflow-context ctx nil run-id))
  ([ctx parent-session-id run-id]
   (let [workflow-run (workflow-runtime/workflow-run-in @(:state* ctx) run-id)
         authoritative-parent-session-id (or parent-session-id
                                             (:parent-session-id workflow-run))
         chart (workflow-statechart/compile-hierarchical-chart (:effective-definition workflow-run))
         env (simple/simple-env)
         sc-session-id (java.util.UUID/randomUUID)
         working-memory* (atom (create-working-memory ctx authoritative-parent-session-id run-id))
         event-queue* (atom [])
         actions-fn (make-workflow-actions ctx authoritative-parent-session-id run-id working-memory* event-queue*)]
     (simple/register! env :workflow-run chart)
     (let [wm0 (sp/start! (::sc/processor env) env :workflow-run
                          {::sc/session-id sc-session-id
                           ::wmdm/data-model (assoc @working-memory* :actions-fn actions-fn)})]
       (sp/save-working-memory! (::sc/working-memory-store env) env sc-session-id wm0)
       {:ctx ctx
        :run-id run-id
        :parent-session-id authoritative-parent-session-id
        :env env
        :sc-session-id sc-session-id
        :wm wm0
        :working-memory* working-memory*
        :event-queue* event-queue*
        :actions-fn actions-fn}))))

(def process-event! lifecycle/process-event!)
(def drain-events! lifecycle/drain-events!)
(def send-and-drain! lifecycle/send-and-drain!)
