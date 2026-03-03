(ns psi.recursion.core
  "Feed-forward recursion controller.

   Establishes an isolated RecursionContext (Nullable pattern), global wrappers,
   the initial controller state shape, trigger intake, readiness gating,
   observation, FUTURE_STATE synthesis, plan proposal generation,
   approval gates, execution, verification, learning, and cycle finalization."
  (:require
   [clojure.string :as str]
   [psi.recursion.future-state :as future-state]
   [psi.recursion.policy :as policy]))

(defrecord RecursionContext [state-atom config])

(defn initial-state
  "Return the initial controller state map."
  []
  {:status :idle
   :current-future-state nil
   :policy (policy/default-policy)
   :config (policy/default-config)
   :hooks []
   :cycles []
   :paused-reason nil
   :paused-checkpoint nil
   :last-error nil})

(defn create-context
  "Create an isolated RecursionContext.

   Options:
   - :state-overrides   map merged over initial controller state
   - :config-overrides  map merged over default config"
  ([]
   (create-context {}))
  ([{:keys [state-overrides config-overrides]
     :or   {state-overrides {}
            config-overrides {}}}]
   (let [base-state  (initial-state)
         merged-config (merge (policy/default-config) config-overrides)
         state (merge base-state
                      {:config merged-config}
                      state-overrides)]
     (->RecursionContext
      (atom state)
      merged-config))))

(defonce ^:private global-ctx (atom nil))

(defn- ensure-global-ctx!
  []
  (or @global-ctx
      (let [ctx (create-context)]
        (reset! global-ctx ctx)
        ctx)))

(defn global-context
  "Return the global recursion context singleton, creating it when absent."
  []
  (ensure-global-ctx!))

(defn reset-global-context!
  "Reset the global context to nil. Useful for testing."
  []
  (reset! global-ctx nil))

(defn get-state-in
  "Return the full controller state map from `ctx`."
  [ctx]
  @(:state-atom ctx))

(defn swap-state-in!
  "Apply `f` to controller state atom in `ctx`."
  [ctx f & args]
  (apply swap! (:state-atom ctx) f args))

(defn get-state
  "Global wrapper for `get-state-in`."
  []
  (get-state-in (global-context)))

(defn swap-state!
  "Global wrapper for `swap-state-in!`."
  [f & args]
  (apply swap-state-in! (global-context) f args))

;;; --- Hooks ---

(defn register-hooks-in!
  "Initialize the hooks list from config's accepted trigger types.
   Each hook derives its `:enabled` flag from the config's `enabled-trigger-hooks`."
  [ctx]
  (let [state  (get-state-in ctx)
        config (:config state)
        accepted (:accepted-trigger-types config)
        enabled  (:enabled-trigger-hooks config)
        hooks  (mapv (fn [t]
                       {:id           (str "hook-" (name t))
                        :trigger-type t
                        :enabled      (contains? enabled t)
                        :timeout-ms   nil})
                     (sort-by name accepted))]
    (swap-state-in! ctx assoc :hooks hooks)
    hooks))

(defn register-hooks!
  "Global wrapper for `register-hooks-in!`."
  []
  (register-hooks-in! (global-context)))

;;; --- Trigger intake and readiness gating ---

(def feed-forward-manual-trigger-prompt-name
  "feed-forward-manual-trigger")

(def feed-forward-manual-trigger-prompt
  "Trigger a manual feed-forward cycle. Capture operator reason and current readiness context.")

(defn manual-trigger-signal
  "Build a canonical manual TriggerSignal payload shared by runtime command
   and EQL mutation entrypoints.

   Optional opts:
   - :actor   operator/actor identity string (default: operator)
   - :source  invocation source keyword (e.g. :runtime-command, :eql-mutation)
   - :extra-payload map merged into payload"
  ([reason]
   (manual-trigger-signal reason {}))
  ([reason {:keys [actor source extra-payload]
            :or {actor "operator" source :unknown extra-payload {}}}]
   {:type :manual
    :reason (or reason "manual-trigger")
    :payload (merge {:prompt-name feed-forward-manual-trigger-prompt-name
                     :prompt-body feed-forward-manual-trigger-prompt
                     :actor actor
                     :source source}
                    extra-payload)
    :timestamp (java.time.Instant/now)}))

(defn- new-cycle
  "Create a new cycle record for `trigger-signal` with the given initial `status`."
  [trigger-signal status]
  {:cycle-id           (str "cycle-" (random-uuid))
   :trigger            trigger-signal
   :started-at         (java.time.Instant/now)
   :ended-at           nil
   :status             status
   :observation        nil
   :proposal           nil
   :execution-attempts []
   :verification       nil
   :outcome            nil
   :learning-memory-ids #{}})

(defn- active-cycle?
  "True if cycle is in a non-terminal status."
  [cycle]
  (not (contains? #{:completed :failed :aborted :blocked} (:status cycle))))

(defn- readiness-ok?
  "Check all four readiness flags in `system-state`. Returns true when all ready."
  [system-state]
  (and (:query-ready system-state)
       (:graph-ready system-state)
       (:introspection-ready system-state)
       (:memory-ready system-state)))

(defn handle-trigger-in!
  "Main trigger entry point. Takes `ctx`, a `trigger-signal` map, and a
   `system-state` map with readiness flags. Returns a result map:
   - `{:result :accepted, :cycle-id ...}` on success
   - `{:result :ignored}` when trigger type is disabled
   - `{:result :blocked, :cycle-id ...}` when readiness fails
   - `{:result :rejected, :reason ...}` when trigger type unknown or controller busy"
  [ctx trigger-signal system-state]
  (let [state    (get-state-in ctx)
        config   (:config state)
        ttype    (:type trigger-signal)
        accepted (:accepted-trigger-types config)
        enabled  (:enabled-trigger-hooks config)]
    (cond
      ;; 1. Unknown trigger type
      (not (contains? accepted ttype))
      {:result :rejected, :reason :unknown-trigger-type}

      ;; 2. Disabled trigger — no state change, no cycle
      (not (contains? enabled ttype))
      {:result :ignored}

      ;; 3. Controller busy (not idle or has active cycles)
      (or (not= :idle (:status state))
          (some active-cycle? (:cycles state)))
      {:result :rejected, :reason :controller-busy}

      ;; 4. Readiness prerequisites fail
      (not (readiness-ok? system-state))
      (let [cycle (new-cycle trigger-signal :blocked)]
        (swap-state-in! ctx (fn [s]
                              (-> s
                                  (assoc :status :paused)
                                  (assoc :paused-reason "recursion_prerequisites_not_ready")
                                  (update :cycles conj cycle))))
        {:result :blocked, :cycle-id (:cycle-id cycle)})

      ;; 5. All checks pass — create observing cycle
      :else
      (let [cycle (new-cycle trigger-signal :observing)]
        (swap-state-in! ctx (fn [s]
                              (-> s
                                  (assoc :status :observing)
                                  (update :cycles conj cycle))))
        {:result :accepted, :cycle-id (:cycle-id cycle)}))))

(defn handle-trigger!
  "Global wrapper for `handle-trigger-in!`."
  [trigger-signal system-state]
  (handle-trigger-in! (global-context) trigger-signal system-state))

(declare find-cycle)
(declare observe-in!)
(declare plan-in!)
(declare apply-approval-gate-in!)
(declare approve-proposal-in!)
(declare reject-proposal-in!)
(declare execute-in!)
(declare verify-in!)
(declare learn-in!)
(declare update-future-state-from-outcome-in!)
(declare finalize-cycle-in!)

(defn- resolve-memory-ctx
  "Resolve memory context for orchestration.
   Prefers explicit `memory-ctx`, otherwise falls back to memory/global-context."
  [memory-ctx]
  (or memory-ctx
      (let [global-memory-context (requiring-resolve 'psi.memory.core/global-context)]
        (global-memory-context))))

(defn- normalize-approval-decision
  [approval-decision]
  (cond
    (nil? approval-decision) nil
    (keyword? approval-decision) approval-decision
    (string? approval-decision)
    (keyword (str/lower-case approval-decision))
    :else ::invalid))

(defn orchestrate-manual-trigger-in!
  "Single production orchestration entrypoint for Step 11 manual cycles.

   Runs explicit sequence:
   trigger -> observe -> plan -> approval gate -> (approve/reject/await)
   -> execute -> verify -> learn -> update FUTURE_STATE -> finalize

   Approval semantics:
   - If gate is manual and `approval-decision` is nil, orchestration stops at
     :awaiting-approval and returns pending approval metadata.
   - If gate is manual and `approval-decision` is :approve, it continues.
   - If gate is manual and `approval-decision` is :reject, it executes
     rejection path (learn/update/finalize) without execution.

   opts keys:
   - :system-state      readiness map (defaults all true)
   - :graph-state       graph snapshot map (optional)
   - :memory-state      memory snapshot map (optional)
   - :memory-ctx        memory context (optional; defaults to memory/global-context)
   - :approver          approver/reviewer id string
   - :approval-notes    approval/rejection notes string
   - :approval-decision nil | :approve | :reject (or string equivalents)
   - :hook-executor     execution hook fn
   - :check-runner      verification check fn"
  [ctx trigger-signal
   {:keys [system-state graph-state memory-state memory-ctx
           approver approval-notes approval-decision
           hook-executor check-runner]
    :or {system-state {:query-ready true
                       :graph-ready true
                       :introspection-ready true
                       :memory-ready true}
         graph-state {}
         memory-state {}
         approver "operator"
         approval-notes ""}}]
  (let [trigger-result (handle-trigger-in! ctx trigger-signal system-state)
        decision (normalize-approval-decision approval-decision)]
    (cond
      (not= :accepted (:result trigger-result))
      {:ok? true
       :trigger-result trigger-result
       :phase :trigger}

      (= ::invalid decision)
      {:ok? false
       :error :invalid-approval-decision
       :approval-decision approval-decision
       :expected #{:approve :reject nil}
       :trigger-result trigger-result}

      :else
      (let [cycle-id (:cycle-id trigger-result)
            observe-result (observe-in! ctx cycle-id system-state graph-state memory-state)
            plan-result (when (:ok? observe-result)
                          (plan-in! ctx cycle-id))
            gate-result (when (:ok? plan-result)
                          (apply-approval-gate-in! ctx cycle-id))]
        (cond
          (not (:ok? observe-result))
          {:ok? false
           :phase :observe
           :cycle-id cycle-id
           :trigger-result trigger-result
           :observe-result observe-result}

          (not (:ok? plan-result))
          {:ok? false
           :phase :plan
           :cycle-id cycle-id
           :trigger-result trigger-result
           :observe-result observe-result
           :plan-result plan-result}

          (not (contains? #{:manual :auto-approved} (:gate gate-result)))
          ;; defensive guard in case gate fn changes contract in future
          {:ok? false
           :phase :approval-gate
           :cycle-id cycle-id
           :trigger-result trigger-result
           :observe-result observe-result
           :plan-result plan-result
           :gate-result gate-result}

          (and (= :manual (:gate gate-result))
               (nil? decision))
          {:ok? true
           :phase :awaiting-approval
           :cycle-id cycle-id
           :trigger-result trigger-result
           :observe-result observe-result
           :plan-result plan-result
           :gate-result gate-result
           :approval {:required? true
                      :pending? true
                      :decision nil}}

          :else
          (let [approval-result (when (= :manual (:gate gate-result))
                                  (case decision
                                    :approve (approve-proposal-in! ctx cycle-id approver approval-notes)
                                    :reject (reject-proposal-in! ctx cycle-id approver approval-notes)
                                    ;; nil cannot happen here due branch above; keep defensive fallback
                                    {:ok? false :error :approval-decision-required}))
                state-after-approval (get-state-in ctx)
                cycle-after-approval (find-cycle (:cycles state-after-approval) cycle-id)
                cycle-status (:status cycle-after-approval)
                execute-result (when (= :executing cycle-status)
                                 (if hook-executor
                                   (execute-in! ctx cycle-id hook-executor)
                                   (execute-in! ctx cycle-id)))
                verify-result (let [post-exec-state (get-state-in ctx)
                                    post-exec-cycle (find-cycle (:cycles post-exec-state) cycle-id)]
                                (when (= :verifying (:status post-exec-cycle))
                                  (if check-runner
                                    (verify-in! ctx cycle-id check-runner)
                                    (verify-in! ctx cycle-id))))
                learn-result (let [post-verify-state (get-state-in ctx)
                                   post-verify-cycle (find-cycle (:cycles post-verify-state) cycle-id)]
                               (when (= :learning (:status post-verify-cycle))
                                 (learn-in! ctx cycle-id (resolve-memory-ctx memory-ctx))))
                future-state-result (when (:ok? learn-result)
                                      (update-future-state-from-outcome-in! ctx cycle-id))
                finalize-result (when (:ok? future-state-result)
                                  (finalize-cycle-in! ctx cycle-id))]
            (cond
              (and approval-result (not (:ok? approval-result)))
              {:ok? false
               :phase :approval
               :cycle-id cycle-id
               :trigger-result trigger-result
               :observe-result observe-result
               :plan-result plan-result
               :gate-result gate-result
               :approval-result approval-result}

              (and execute-result (not (:ok? execute-result)))
              {:ok? false
               :phase :execute
               :cycle-id cycle-id
               :trigger-result trigger-result
               :observe-result observe-result
               :plan-result plan-result
               :gate-result gate-result
               :approval-result approval-result
               :execute-result execute-result}

              (and verify-result (not (:ok? verify-result)))
              {:ok? false
               :phase :verify
               :cycle-id cycle-id
               :trigger-result trigger-result
               :observe-result observe-result
               :plan-result plan-result
               :gate-result gate-result
               :approval-result approval-result
               :execute-result execute-result
               :verify-result verify-result}

              (and learn-result (not (:ok? learn-result)))
              {:ok? false
               :phase :learn
               :cycle-id cycle-id
               :trigger-result trigger-result
               :observe-result observe-result
               :plan-result plan-result
               :gate-result gate-result
               :approval-result approval-result
               :execute-result execute-result
               :verify-result verify-result
               :learn-result learn-result}

              (and future-state-result (not (:ok? future-state-result)))
              {:ok? false
               :phase :future-state
               :cycle-id cycle-id
               :trigger-result trigger-result
               :observe-result observe-result
               :plan-result plan-result
               :gate-result gate-result
               :approval-result approval-result
               :execute-result execute-result
               :verify-result verify-result
               :learn-result learn-result
               :future-state-result future-state-result}

              (and finalize-result (not (:ok? finalize-result)))
              {:ok? false
               :phase :finalize
               :cycle-id cycle-id
               :trigger-result trigger-result
               :observe-result observe-result
               :plan-result plan-result
               :gate-result gate-result
               :approval-result approval-result
               :execute-result execute-result
               :verify-result verify-result
               :learn-result learn-result
               :future-state-result future-state-result
               :finalize-result finalize-result}

              :else
              {:ok? true
               :phase :completed
               :cycle-id cycle-id
               :trigger-result trigger-result
               :observe-result observe-result
               :plan-result plan-result
               :gate-result gate-result
               :approval-result approval-result
               :execute-result execute-result
               :verify-result verify-result
               :learn-result learn-result
               :future-state-result future-state-result
               :finalize-result finalize-result})))))))

(defn orchestrate-manual-trigger!
  "Global wrapper for `orchestrate-manual-trigger-in!`."
  ([trigger-signal]
   (orchestrate-manual-trigger! trigger-signal {}))
  ([trigger-signal opts]
   (orchestrate-manual-trigger-in! (global-context) trigger-signal opts)))

;;; --- Observation ---

(defn- find-cycle
  "Find a cycle by id in the cycles vector."
  [cycles cycle-id]
  (first (filter #(= cycle-id (:cycle-id %)) cycles)))

(defn- update-cycle
  "Update the cycle matching `cycle-id` with `f`."
  [cycles cycle-id f]
  (mapv (fn [c]
          (if (= cycle-id (:cycle-id c))
            (f c)
            c))
        cycles))

(defn- extract-graph-signals
  "Extract signal strings from graph-state map."
  [graph-state]
  (let [signals (transient #{})]
    (when-let [nc (:node-count graph-state)]
      (conj! signals (str "node-count=" nc)))
    (when-let [cc (:capability-count graph-state)]
      (conj! signals (str "capability-count=" cc)))
    (when-let [s (:status graph-state)]
      (conj! signals (str "status=" (name s))))
    (persistent! signals)))

(defn- extract-memory-signals
  "Extract signal strings from memory-state map."
  [memory-state]
  (let [signals (transient #{})]
    (when-let [ec (:entry-count memory-state)]
      (conj! signals (str "entry-count=" ec)))
    (when-let [s (:status memory-state)]
      (conj! signals (str "status=" (name s))))
    (when-let [rc (:recovery-count memory-state)]
      (conj! signals (str "recovery-count=" rc)))
    (persistent! signals)))

(defn- extract-gaps
  "Identify gaps from readiness and capability data."
  [readiness graph-state memory-state]
  (let [gaps (transient [])]
    (when-not (:query-ready readiness)
      (conj! gaps "query not ready"))
    (when-not (:graph-ready readiness)
      (conj! gaps "graph not ready"))
    (when-not (:introspection-ready readiness)
      (conj! gaps "introspection not ready"))
    (when-not (:memory-ready readiness)
      (conj! gaps "memory not ready"))
    (when (and (:capability-count graph-state)
               (< (:capability-count graph-state) 3))
      (conj! gaps "low capability count"))
    (when (and (:entry-count memory-state)
               (zero? (:entry-count memory-state)))
      (conj! gaps "no memory entries"))
    (persistent! gaps)))

(defn- extract-opportunities
  "Identify opportunities from system state."
  [readiness graph-state memory-state]
  (let [opps (transient [])]
    (when (and (:query-ready readiness)
               (:graph-ready readiness)
               (:introspection-ready readiness)
               (:memory-ready readiness))
      (conj! opps "system ready for evolution"))
    (when (and (:status graph-state) (= :stable (:status graph-state)))
      (conj! opps "stable graph available"))
    (when (and (:entry-count memory-state)
               (pos? (:entry-count memory-state)))
      (conj! opps "memory entries available for learning"))
    (persistent! opps)))

(defn observe-in!
  "Observe phase: capture system, graph, and memory signals and attach
   observation to the cycle. Transitions cycle and controller to :planning.

   Returns {:ok? true, :observation obs} on success,
   or {:ok? false, :error ...} if cycle not found or wrong status."
  [ctx cycle-id system-state graph-state memory-state]
  (let [state (get-state-in ctx)
        cycle (find-cycle (:cycles state) cycle-id)]
    (cond
      (nil? cycle)
      {:ok? false, :error :cycle-not-found}

      (not= :observing (:status cycle))
      {:ok? false, :error :wrong-cycle-status, :status (:status cycle)}

      :else
      (let [readiness {:query (boolean (:query-ready system-state))
                       :graph (boolean (:graph-ready system-state))
                       :introspection (boolean (:introspection-ready system-state))
                       :memory (boolean (:memory-ready system-state))}
            observation {:captured-at (java.time.Instant/now)
                         :readiness readiness
                         :graph-signals (extract-graph-signals graph-state)
                         :memory-signals (extract-memory-signals memory-state)
                         :gaps (extract-gaps system-state graph-state memory-state)
                         :opportunities (extract-opportunities system-state graph-state memory-state)}]
        (swap-state-in! ctx
                        (fn [s]
                          (-> s
                              (assoc :status :planning)
                              (update :cycles update-cycle cycle-id
                                      #(-> %
                                           (assoc :observation observation)
                                           (assoc :status :planning))))))
        {:ok? true, :observation observation}))))

(defn observe!
  "Global wrapper for `observe-in!`."
  [cycle-id system-state graph-state memory-state]
  (observe-in! (global-context) cycle-id system-state graph-state memory-state))

;;; --- Plan proposal generation ---

(def ^:private risk-order
  "Risk level ordering for aggregation (highest wins)."
  {:low 0, :medium 1, :high 2})

(defn- aggregate-risk
  "Return the highest risk level among actions. Defaults to :low."
  [actions]
  (if (empty? actions)
    :low
    (let [max-idx (apply max (map #(get risk-order (:risk %) 0) actions))]
      (first (keep (fn [[k v]] (when (= v max-idx) k)) risk-order)))))

(defn- goal->action
  "Convert a FutureGoal to a ProposedAction.
   All generated actions are atomic by design."
  [goal]
  {:id (str "action-" (:id goal))
   :title (str "Address: " (:title goal))
   :description (:description goal)
   :domain :planning
   :risk (:priority goal)
   :atomic true
   :expected-impact #{(:title goal)}
   :verification-hints #{"tests" "lint"}})

(defn plan-in!
  "Plan phase: synthesize FUTURE_STATE and generate a bounded PlanProposal
   from the top goal(s). Attaches proposal and updated future-state to cycle.
   Controller stays in :planning (approval gate is next step).

   Returns {:ok? true, :proposal proposal, :future-state fs} on success."
  [ctx cycle-id]
  (let [state (get-state-in ctx)
        cycle (find-cycle (:cycles state) cycle-id)]
    (cond
      (nil? cycle)
      {:ok? false, :error :cycle-not-found}

      (not= :planning (:status cycle))
      {:ok? false, :error :wrong-cycle-status, :status (:status cycle)}

      (nil? (:observation cycle))
      {:ok? false, :error :no-observation}

      :else
      (let [policy (:policy state)
            max-actions (:max-actions-per-cycle policy)
            current-fs (:current-future-state state)
            new-fs (future-state/synthesize-future-state current-fs (:observation cycle))
            ;; Sort goals: high priority first, then medium
            sorted-goals (sort-by (fn [g]
                                    (case (:priority g)
                                      :high 0
                                      :medium 1
                                      :low 2
                                      3))
                                  (:goals new-fs))
            ;; Generate actions bounded by max-actions-per-cycle
            actions (->> sorted-goals
                         (map goal->action)
                         (take max-actions)
                         vec)
            proposal {:actions actions
                      :risk (aggregate-risk actions)
                      :requires-approval (:require-human-approval policy)
                      :approved nil
                      :approval-by nil
                      :approval-notes nil
                      :generated-at (java.time.Instant/now)}]
        (swap-state-in! ctx
                        (fn [s]
                          (-> s
                              (assoc :current-future-state new-fs)
                              (update :cycles update-cycle cycle-id
                                      #(assoc % :proposal proposal)))))
        {:ok? true, :proposal proposal, :future-state new-fs}))))

(defn plan!
  "Global wrapper for `plan-in!`."
  [cycle-id]
  (plan-in! (global-context) cycle-id))

;;; --- Approval gate ---

(defn apply-approval-gate-in!
  "Apply the approval gate to the cycle's proposal.

   If manual approval is required: transitions cycle+controller to :awaiting-approval.
   If auto-approve: sets proposal.approved=true, proposal.requires-approval=false,
   transitions cycle+controller to :executing.

   Returns {:gate :manual} or {:gate :auto-approved}."
  [ctx cycle-id]
  (let [state (get-state-in ctx)
        cycle (find-cycle (:cycles state) cycle-id)]
    (cond
      (nil? cycle)
      {:ok? false, :error :cycle-not-found}

      (not= :planning (:status cycle))
      {:ok? false, :error :wrong-cycle-status, :status (:status cycle)}

      (nil? (:proposal cycle))
      {:ok? false, :error :no-proposal}

      :else
      (let [config (:config state)
            proposal (:proposal cycle)]
        (if (policy/requires-manual-approval? proposal config)
          ;; Manual approval required
          (do
            (swap-state-in! ctx
                            (fn [s]
                              (-> s
                                  (assoc :status :awaiting-approval)
                                  (update :cycles update-cycle cycle-id
                                          #(assoc % :status :awaiting-approval)))))
            {:gate :manual})
          ;; Auto-approve
          (do
            (swap-state-in! ctx
                            (fn [s]
                              (-> s
                                  (assoc :status :executing)
                                  (update :cycles update-cycle cycle-id
                                          #(-> %
                                               (assoc :status :executing)
                                               (assoc-in [:proposal :approved] true)
                                               (assoc-in [:proposal :requires-approval] false))))))
            {:gate :auto-approved}))))))

(defn apply-approval-gate!
  "Global wrapper for `apply-approval-gate-in!`."
  [cycle-id]
  (apply-approval-gate-in! (global-context) cycle-id))

;;; --- Approve / Reject proposals ---

(defn approve-proposal-in!
  "Approve a proposal that is awaiting approval.
   Sets approved=true, approval-by, approval-notes.
   Transitions cycle+controller to :executing.

   Returns {:ok? true} on success."
  [ctx cycle-id approver notes]
  (let [state (get-state-in ctx)
        cycle (find-cycle (:cycles state) cycle-id)]
    (cond
      (nil? cycle)
      {:ok? false, :error :cycle-not-found}

      (not= :awaiting-approval (:status cycle))
      {:ok? false, :error :wrong-cycle-status, :status (:status cycle)}

      :else
      (do
        (swap-state-in! ctx
                        (fn [s]
                          (-> s
                              (assoc :status :executing)
                              (update :cycles update-cycle cycle-id
                                      #(-> %
                                           (assoc :status :executing)
                                           (assoc-in [:proposal :approved] true)
                                           (assoc-in [:proposal :approval-by] approver)
                                           (assoc-in [:proposal :approval-notes] notes))))))
        {:ok? true}))))

(defn approve-proposal!
  "Global wrapper for `approve-proposal-in!`."
  [cycle-id approver notes]
  (approve-proposal-in! (global-context) cycle-id approver notes))

(defn reject-proposal-in!
  "Reject a proposal that is awaiting approval.
   Sets approved=false, approval-by, approval-notes, and an explicit
   aborted outcome so learn/finalize preserve rejection semantics.
   Transitions cycle+controller to :learning (skip execution).

   Returns {:ok? true} on success."
  [ctx cycle-id approver notes]
  (let [state (get-state-in ctx)
        cycle (find-cycle (:cycles state) cycle-id)]
    (cond
      (nil? cycle)
      {:ok? false, :error :cycle-not-found}

      (not= :awaiting-approval (:status cycle))
      {:ok? false, :error :wrong-cycle-status, :status (:status cycle)}

      :else
      (let [rejection-reason (if (seq notes) notes "proposal_rejected")
            outcome {:status :aborted
                     :summary "proposal_rejected"
                     :evidence #{rejection-reason}
                     :changed-goals #{}}]
        (swap-state-in! ctx
                        (fn [s]
                          (-> s
                              (assoc :status :learning)
                              (update :cycles update-cycle cycle-id
                                      #(-> %
                                           (assoc :status :learning)
                                           (assoc :outcome outcome)
                                           (assoc-in [:proposal :approved] false)
                                           (assoc-in [:proposal :approval-by] approver)
                                           (assoc-in [:proposal :approval-notes] notes))))))
        {:ok? true}))))

(defn reject-proposal!
  "Global wrapper for `reject-proposal-in!`."
  [cycle-id approver notes]
  (reject-proposal-in! (global-context) cycle-id approver notes))

;;; --- Execution ---

(defn- default-hook-executor
  "Default no-op hook executor. Returns success with a placeholder message."
  [_action]
  {:status :success, :output-summary "hook-not-implemented"})

(defn execute-in!
  "Execute approved proposal actions via hook-executor.

   Takes `ctx`, `cycle-id`, and an optional `hook-executor` function
   `(fn [action] {:status :success|:failed, :output-summary str})`.

   Creates an ExecutionAttempt record per action. Appends attempts to cycle.
   Transitions cycle+controller to :verifying.

   Returns {:ok? true, :attempts [...]} on success."
  ([ctx cycle-id]
   (execute-in! ctx cycle-id default-hook-executor))
  ([ctx cycle-id hook-executor]
   (let [state (get-state-in ctx)
         cycle (find-cycle (:cycles state) cycle-id)]
     (cond
       (nil? cycle)
       {:ok? false, :error :cycle-not-found}

       (not= :executing (:status cycle))
       {:ok? false, :error :wrong-cycle-status, :status (:status cycle)}

       (not (get-in cycle [:proposal :approved]))
       {:ok? false, :error :proposal-not-approved}

       :else
       (let [actions (get-in cycle [:proposal :actions])
             attempts (mapv (fn [action]
                              (let [started (java.time.Instant/now)
                                    result  (hook-executor action)
                                    ended   (java.time.Instant/now)]
                                {:action-id      (:id action)
                                 :started-at     started
                                 :ended-at       ended
                                 :status         (:status result)
                                 :output-summary (:output-summary result)}))
                            actions)]
         (swap-state-in! ctx
                         (fn [s]
                           (-> s
                               (assoc :status :verifying)
                               (update :cycles update-cycle cycle-id
                                       #(-> %
                                            (assoc :status :verifying)
                                            (update :execution-attempts into attempts))))))
         {:ok? true, :attempts attempts})))))

(defn execute!
  "Global wrapper for `execute-in!`."
  ([cycle-id]
   (execute-in! (global-context) cycle-id))
  ([cycle-id hook-executor]
   (execute-in! (global-context) cycle-id hook-executor)))

;;; --- Verification and rollback ---

(defn- default-check-runner
  "Default no-op check runner. Returns passing for each check."
  [_check-name]
  {:passed true, :details "check-not-implemented"})

(defn- failed-check-names
  "Return set of check names that did not pass."
  [checks]
  (into #{} (comp (remove :passed) (map :name)) checks))

(defn rollback-in!
  "Record a rollback action on the cycle.

   In Step 11 scaffold, this is a recorded action (stores rollback evidence
   on the cycle) rather than actual git reset. Appends a rollback record to
   the cycle's execution-attempts.

   Returns {:ok? true}."
  [ctx cycle-id]
  (swap-state-in! ctx
                  (fn [s]
                    (update s :cycles update-cycle cycle-id
                            #(update % :execution-attempts conj
                                     {:type :rollback
                                      :cycle-id cycle-id
                                      :timestamp (java.time.Instant/now)
                                      :reason "verification-failure"}))))
  {:ok? true})

(defn rollback!
  "Global wrapper for `rollback-in!`."
  [cycle-id]
  (rollback-in! (global-context) cycle-id))

(defn verify-in!
  "Verification phase: run required checks and produce a VerificationReport.

   Takes `ctx`, `cycle-id`, and an optional `check-runner` function
   `(fn [check-name] {:passed bool, :details str?})`.

   Requires cycle status `:verifying`. Runs each check in
   `config.required-verification-checks`.

   After verification:
   - If all checks pass: transitions cycle+controller to `:learning`.
   - If any check fails AND rollback-on-verification-failure=true:
     calls `rollback-in!`, sets cycle outcome to failed with rollback evidence,
     transitions to `:learning`.
   - If any check fails AND rollback-on-verification-failure=false:
     sets cycle outcome to failed, transitions to `:learning`.

   Returns {:ok? true, :report report} on success."
  ([ctx cycle-id]
   (verify-in! ctx cycle-id default-check-runner))
  ([ctx cycle-id check-runner]
   (let [state (get-state-in ctx)
         cycle (find-cycle (:cycles state) cycle-id)]
     (cond
       (nil? cycle)
       {:ok? false, :error :cycle-not-found}

       (not= :verifying (:status cycle))
       {:ok? false, :error :wrong-cycle-status, :status (:status cycle)}

       :else
       (let [config (:config state)
             policy (:policy state)
             required-checks (:required-verification-checks config)
             checks (mapv (fn [check-name]
                            (let [result (check-runner check-name)]
                              {:name check-name
                               :passed (:passed result)
                               :details (:details result)}))
                          (sort required-checks))
             passed-all (every? :passed checks)
             report {:checks checks
                     :passed-all passed-all
                     :completed-at (java.time.Instant/now)}]
         (if passed-all
           ;; All checks pass — transition to learning, no outcome set yet
           (do
             (swap-state-in! ctx
                             (fn [s]
                               (-> s
                                   (assoc :status :learning)
                                   (update :cycles update-cycle cycle-id
                                           #(-> %
                                                (assoc :status :learning)
                                                (assoc :verification report))))))
             {:ok? true, :report report})
           ;; Some checks failed
           (let [failed (failed-check-names checks)
                 rollback? (:rollback-on-verification-failure policy)
                 summary (if rollback?
                           "verification_failed_rolled_back"
                           "verification_failed")
                 outcome {:status :failed
                          :summary summary
                          :evidence failed
                          :changed-goals #{}}]
             ;; Record rollback if policy says so
             (when rollback?
               (rollback-in! ctx cycle-id))
             ;; Set outcome and transition to learning
             (swap-state-in! ctx
                             (fn [s]
                               (-> s
                                   (assoc :status :learning)
                                   (update :cycles update-cycle cycle-id
                                           #(-> %
                                                (assoc :status :learning)
                                                (assoc :verification report)
                                                (assoc :outcome outcome))))))
             {:ok? true, :report report})))))))

(defn verify!
  "Global wrapper for `verify-in!`."
  ([cycle-id]
   (verify-in! (global-context) cycle-id))
  ([cycle-id check-runner]
   (verify-in! (global-context) cycle-id check-runner)))

;;; --- Learn phase + memory writeback ---

(defn- build-success-outcome
  "Build a success outcome from the cycle's proposal actions and future-state goals."
  [cycle future-state]
  (let [action-titles (into #{} (map :title) (get-in cycle [:proposal :actions]))
        changed-goals (into #{} (map :id) (:goals future-state))]
    {:status :success
     :summary "cycle_completed_successfully"
     :evidence action-titles
     :changed-goals changed-goals}))

(defn- build-memory-content
  "Build the memory content string for a cycle outcome."
  [cycle-id outcome cycle]
  (let [action-titles (mapv :title (get-in cycle [:proposal :actions]))]
    (str "Feed-forward cycle " cycle-id ": "
         (name (:status outcome)) ". "
         (:summary outcome) ". "
         "Actions: " (pr-str action-titles) ".")))

(defn learn-in!
  "Learn phase: write cycle outcome to memory and store resulting memory IDs.

   Takes `ctx`, `cycle-id`, and `memory-ctx` (a `psi.memory.core/MemoryContext`).
   Requires cycle status `:learning`.

   If cycle has no outcome yet (successful verification path), sets outcome to
   success with action titles and changed goal IDs.

   Calls `psi.memory.core/remember-in!` with tags #{\"feed-forward\" \"cycle\" \"step-11\"}
   and provenance linking to this cycle.

   Returns {:ok? true, :memory-ids #{record-id}} on success."
  [ctx cycle-id memory-ctx]
  (let [state (get-state-in ctx)
        cycle (find-cycle (:cycles state) cycle-id)]
    (cond
      (nil? cycle)
      {:ok? false, :error :cycle-not-found}

      (not= :learning (:status cycle))
      {:ok? false, :error :wrong-cycle-status, :status (:status cycle)}

      :else
      (let [;; If no outcome yet, this is the success path
            outcome (or (:outcome cycle)
                        (build-success-outcome cycle (:current-future-state state)))
            ;; Set the outcome on the cycle if it wasn't already set
            _ (when (nil? (:outcome cycle))
                (swap-state-in! ctx
                                (fn [s]
                                  (update s :cycles update-cycle cycle-id
                                          #(assoc % :outcome outcome)))))
            ;; Build memory content
            content (build-memory-content cycle-id outcome cycle)
            trigger-type (get-in cycle [:trigger :type])
            ;; Write to memory using remember-in!
            ;; We need to call the memory module's remember-in!
            remember-fn (requiring-resolve 'psi.memory.core/remember-in!)
            mem-result (remember-fn memory-ctx
                                    {:content-type :discovery
                                     :content content
                                     :tags ["feed-forward" "cycle" "step-11"]
                                     :provenance {:source :feed-forward
                                                  :cycle-id cycle-id
                                                  :trigger-type trigger-type
                                                  :outcome-status (:status outcome)}})
            record-id (get-in mem-result [:record :record-id])]
        (if (:ok? mem-result)
          (do
            ;; Store memory record ID on the cycle
            (swap-state-in! ctx
                            (fn [s]
                              (update s :cycles update-cycle cycle-id
                                      #(update % :learning-memory-ids conj record-id))))
            {:ok? true, :memory-ids #{record-id}})
          {:ok? false, :error :memory-write-failed, :details mem-result})))))

(defn learn!
  "Global wrapper for `learn-in!`."
  [cycle-id memory-ctx]
  (learn-in! (global-context) cycle-id memory-ctx))

;;; --- FUTURE_STATE updates from outcome ---

(defn update-future-state-from-outcome-in!
  "Update FUTURE_STATE based on cycle outcome.

   - Success: advance goals referenced in outcome's changed-goals to :complete.
   - Failed/blocked/aborted: add blockers from outcome evidence.

   Returns {:ok? true, :future-state updated-fs}."
  [ctx cycle-id]
  (let [state (get-state-in ctx)
        cycle (find-cycle (:cycles state) cycle-id)]
    (cond
      (nil? cycle)
      {:ok? false, :error :cycle-not-found}

      (nil? (:outcome cycle))
      {:ok? false, :error :no-outcome}

      :else
      (let [outcome (:outcome cycle)
            current-fs (or (:current-future-state state)
                           (future-state/initial-future-state))
            updated-fs (case (:status outcome)
                         :success
                         (future-state/advance-goals current-fs (:changed-goals outcome))

                         (:failed :blocked :aborted)
                         (future-state/add-blockers current-fs (:evidence outcome))

                         ;; Fallback: no change, just increment version
                         (future-state/next-version current-fs))]
        (swap-state-in! ctx assoc :current-future-state updated-fs)
        {:ok? true, :future-state updated-fs}))))

(defn update-future-state-from-outcome!
  "Global wrapper for `update-future-state-from-outcome-in!`."
  [cycle-id]
  (update-future-state-from-outcome-in! (global-context) cycle-id))

;;; --- Cycle finalization ---

(defn finalize-cycle-in!
  "Finalize a cycle: set ended-at, terminal status, return controller to idle.

   If outcome status is :success, cycle status becomes :completed.
   Otherwise cycle status becomes :failed.
   Controller status returns to :idle, paused-reason is cleared.

   Returns {:ok? true, :final-status :completed|:failed}."
  [ctx cycle-id]
  (let [state (get-state-in ctx)
        cycle (find-cycle (:cycles state) cycle-id)]
    (cond
      (nil? cycle)
      {:ok? false, :error :cycle-not-found}

      (nil? (:outcome cycle))
      {:ok? false, :error :no-outcome}

      :else
      (let [outcome (:outcome cycle)
            final-status (if (= :success (:status outcome))
                           :completed
                           :failed)]
        (swap-state-in! ctx
                        (fn [s]
                          (-> s
                              (assoc :status :idle)
                              (assoc :paused-reason nil)
                              (assoc :paused-checkpoint nil)
                              (update :cycles update-cycle cycle-id
                                      #(-> %
                                           (assoc :status final-status)
                                           (assoc :ended-at (java.time.Instant/now)))))))
        {:ok? true, :final-status final-status}))))

(defn finalize-cycle!
  "Global wrapper for `finalize-cycle-in!`."
  [cycle-id]
  (finalize-cycle-in! (global-context) cycle-id))

;;; --- Continue / resume an approved cycle ---

(defn continue-cycle-in!
  "Continue a non-terminal cycle from its current status through completion.

   Useful when a cycle is already in :executing/:verifying/:learning (e.g. after
   manual approval) and needs to run the remaining phases with default hooks.

   opts keys:
   - :memory-ctx    memory context (optional; defaults to memory/global-context)
   - :hook-executor execution hook fn
   - :check-runner  verification check fn"
  [ctx cycle-id {:keys [memory-ctx hook-executor check-runner]}]
  (let [state (get-state-in ctx)
        cycle (find-cycle (:cycles state) cycle-id)]
    (cond
      (nil? cycle)
      {:ok? false :error :cycle-not-found}

      (= :awaiting-approval (:status cycle))
      {:ok? false :error :awaiting-approval}

      (contains? #{:completed :failed :aborted :blocked} (:status cycle))
      {:ok? true :phase :terminal :status (:status cycle)}

      :else
      (let [execute-result (when (= :executing (:status cycle))
                             (if hook-executor
                               (execute-in! ctx cycle-id hook-executor)
                               (execute-in! ctx cycle-id)))
            cycle-after-exec (find-cycle (:cycles (get-state-in ctx)) cycle-id)
            verify-result (when (= :verifying (:status cycle-after-exec))
                            (if check-runner
                              (verify-in! ctx cycle-id check-runner)
                              (verify-in! ctx cycle-id)))
            cycle-after-verify (find-cycle (:cycles (get-state-in ctx)) cycle-id)
            learn-result (when (= :learning (:status cycle-after-verify))
                           (learn-in! ctx cycle-id (resolve-memory-ctx memory-ctx)))
            future-state-result (when (:ok? learn-result)
                                  (update-future-state-from-outcome-in! ctx cycle-id))
            finalize-result (when (:ok? future-state-result)
                              (finalize-cycle-in! ctx cycle-id))]
        (cond
          (and execute-result (not (:ok? execute-result)))
          {:ok? false :phase :execute :execute-result execute-result}

          (and verify-result (not (:ok? verify-result)))
          {:ok? false :phase :verify
           :execute-result execute-result
           :verify-result verify-result}

          (and learn-result (not (:ok? learn-result)))
          {:ok? false :phase :learn
           :execute-result execute-result
           :verify-result verify-result
           :learn-result learn-result}

          (and future-state-result (not (:ok? future-state-result)))
          {:ok? false :phase :future-state
           :execute-result execute-result
           :verify-result verify-result
           :learn-result learn-result
           :future-state-result future-state-result}

          (and finalize-result (not (:ok? finalize-result)))
          {:ok? false :phase :finalize
           :execute-result execute-result
           :verify-result verify-result
           :learn-result learn-result
           :future-state-result future-state-result
           :finalize-result finalize-result}

          :else
          {:ok? true
           :phase :completed
           :execute-result execute-result
           :verify-result verify-result
           :learn-result learn-result
           :future-state-result future-state-result
           :finalize-result finalize-result})))))

(defn continue-cycle!
  "Global wrapper for `continue-cycle-in!`."
  ([cycle-id]
   (continue-cycle! cycle-id {}))
  ([cycle-id opts]
   (continue-cycle-in! (global-context) cycle-id opts)))

;;; --- EQL resolver registration ---

(defn register-resolvers-in!
  "Register recursion resolvers into isolated query context `qctx`.
   Rebuilds query env by default."
  ([qctx]
   (register-resolvers-in! qctx true))
  ([qctx rebuild?]
   (let [resolvers (requiring-resolve 'psi.recursion.resolvers/all-resolvers)
         register-fn (requiring-resolve 'psi.query.core/register-resolver-in!)
         rebuild-fn (requiring-resolve 'psi.query.core/rebuild-env-in!)]
     (doseq [r @resolvers]
       (register-fn qctx r))
     (when rebuild?
       (rebuild-fn qctx))
     :ok)))

(defn register-mutations-in!
  "Register recursion mutations into isolated query context `qctx`.
   Rebuilds query env by default."
  ([qctx]
   (register-mutations-in! qctx true))
  ([qctx rebuild?]
   (let [mutations (requiring-resolve 'psi.recursion.resolvers/all-mutations)
         register-fn (requiring-resolve 'psi.query.core/register-mutation-in!)
         rebuild-fn (requiring-resolve 'psi.query.core/rebuild-env-in!)]
     (doseq [m @mutations]
       (register-fn qctx m))
     (when rebuild?
       (rebuild-fn qctx))
     :ok)))

(defn register-resolvers!
  "Register recursion resolvers and mutations into global query context."
  []
  (let [resolvers (requiring-resolve 'psi.recursion.resolvers/all-resolvers)
        mutations (requiring-resolve 'psi.recursion.resolvers/all-mutations)
        register-resolver-fn (requiring-resolve 'psi.query.core/register-resolver!)
        register-mutation-fn (requiring-resolve 'psi.query.core/register-mutation!)
        rebuild-fn (requiring-resolve 'psi.query.core/rebuild-env!)]
    (doseq [r @resolvers]
      (register-resolver-fn r))
    (doseq [m @mutations]
      (register-mutation-fn m))
    (rebuild-fn)
    :ok))
