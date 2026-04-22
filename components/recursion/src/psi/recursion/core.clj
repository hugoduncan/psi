(ns psi.recursion.core
  (:require
   [clojure.string :as str]
   [psi.recursion.future-state :as future-state]
   [psi.recursion.observation-planning :as op]
   [psi.recursion.policy :as policy]
   [psi.recursion.query-registration :as query-registration]))
(defrecord RecursionContext [state-atom config host-ctx host-path])
(defn initial-state
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
(defn- hooks-from-config
  [config]
  (let [accepted (:accepted-trigger-types config)
        enabled  (:enabled-trigger-hooks config)]
    (mapv (fn [t]
            {:id           (str "hook-" (name t))
             :trigger-type t
             :enabled      (contains? enabled t)
             :timeout-ms   nil})
          (sort-by name accepted))))
(defn create-context
  ([]
   (create-context {}))
  ([{:keys [state-overrides config-overrides]
     :or   {state-overrides {}
            config-overrides {}}}]
   (let [base-state    (initial-state)
         merged-config (merge (policy/default-config) config-overrides)
         state         (merge base-state
                              {:config merged-config
                               :hooks  (hooks-from-config merged-config)}
                              state-overrides)]
     (->RecursionContext
      (atom state)
      merged-config
      nil
      nil))))
(defonce ^:private global-ctx (atom nil))
(defn- ensure-global-ctx!
  []
  (or @global-ctx
      (let [ctx (create-context)]
        (reset! global-ctx ctx)
        ctx)))
(defn global-context
  []
  (ensure-global-ctx!))
(defn reset-global-context!
  []
  (reset! global-ctx nil))
(defn create-hosted-context
  [host-ctx host-path]
  (let [existing (get-in @(:state* host-ctx) host-path)
        state    (or existing (initial-state))]
    (swap! (:state* host-ctx) assoc-in host-path state)
    (->RecursionContext
     nil
     (:config state)
     host-ctx
     host-path)))
(defn get-state-in
  [ctx]
  (if-let [state-atom (:state-atom ctx)]
    @state-atom
    (get-in @(:state* (:host-ctx ctx)) (:host-path ctx))))
(defn swap-state-in!
  [ctx f & args]
  (if-let [state-atom (:state-atom ctx)]
    (apply swap! state-atom f args)
    (apply swap! (:state* (:host-ctx ctx)) update-in (:host-path ctx) f args)))
(defn get-state
  []
  (get-state-in (global-context)))
(defn swap-state!
  [f & args]
  (apply swap-state-in! (global-context) f args))
(defn register-hooks-in!
  [ctx]
  (let [hooks (hooks-from-config (:config (get-state-in ctx)))]
    (swap-state-in! ctx assoc :hooks hooks)
    hooks))
(defn register-hooks!
  []
  (register-hooks-in! (global-context)))
(def remember-manual-trigger-prompt-name
  "remember-manual-trigger")
(def remember-manual-trigger-prompt
  "Trigger a manual remember cycle.")
(defn manual-trigger-signal
  ([reason]
   (manual-trigger-signal reason {}))
  ([reason {:keys [actor source extra-payload]
            :or {actor "operator" source :unknown extra-payload {}}}]
   {:type :manual
    :reason (or reason "manual-trigger")
    :payload (merge {:prompt-name remember-manual-trigger-prompt-name
                     :prompt-body remember-manual-trigger-prompt
                     :actor actor
                     :source source}
                    extra-payload)
    :timestamp (java.time.Instant/now)}))
(defn- new-cycle
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
  [cycle]
  (not (contains? #{:completed :failed :aborted :blocked} (:status cycle))))
(defn- readiness-ok?
  [system-state]
  (and (:query-ready system-state)
       (:graph-ready system-state)
       (:introspection-ready system-state)
       (:memory-ready system-state)))
(defn handle-trigger-in!
  [ctx trigger-signal system-state]
  (let [state    (get-state-in ctx)
        config   (:config state)
        ttype    (:type trigger-signal)
        accepted (:accepted-trigger-types config)
        enabled  (:enabled-trigger-hooks config)]
    (cond
      (not (contains? accepted ttype))
      {:result :rejected, :reason :unknown-trigger-type}
      (not (contains? enabled ttype))
      {:result :ignored}
      (or (not= :idle (:status state))
          (some active-cycle? (:cycles state)))
      {:result :rejected, :reason :controller-busy}
      (not (readiness-ok? system-state))
      (let [cycle (new-cycle trigger-signal :blocked)]
        (swap-state-in! ctx (fn [s]
                              (-> s
                                  (assoc :status :paused)
                                  (assoc :paused-reason "recursion_prerequisites_not_ready")
                                  (update :cycles conj cycle))))
        {:result :blocked, :cycle-id (:cycle-id cycle)})
      :else
      (let [cycle (new-cycle trigger-signal :observing)]
        (swap-state-in! ctx (fn [s]
                              (-> s
                                  (assoc :status :observing)
                                  (update :cycles conj cycle))))
        {:result :accepted, :cycle-id (:cycle-id cycle)}))))
(defn handle-trigger!
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
(defn- orchestration-result
  [ok? phase cycle-id steps]
  (merge {:ok? ok?
          :phase phase
          :cycle-id cycle-id}
         steps))
(defn- approval-step-result
  [ctx cycle-id gate-result decision approver approval-notes]
  (cond
    (not (contains? #{:manual :auto-approved} (:gate gate-result)))
    {:status :error
     :result (orchestration-result false :approval-gate cycle-id {:gate-result gate-result})}
    (and (= :manual (:gate gate-result)) (nil? decision))
    {:status :awaiting
     :result (orchestration-result true :awaiting-approval cycle-id
                                   {:gate-result gate-result
                                    :approval {:required? true
                                               :pending? true
                                               :decision nil}})}
    (= :manual (:gate gate-result))
    {:status :continue
     :approval-result (case decision
                        :approve (approve-proposal-in! ctx cycle-id approver approval-notes)
                        :reject (reject-proposal-in! ctx cycle-id approver approval-notes)
                        {:ok? false :error :approval-decision-required})}
    :else
    {:status :continue
     :approval-result nil}))
(defn- cycle-status-in
  [ctx cycle-id]
  (some-> (get-state-in ctx) :cycles (find-cycle cycle-id) :status))
(defn- run-execute-step
  [ctx cycle-id hook-executor]
  (when (= :executing (cycle-status-in ctx cycle-id))
    (if hook-executor
      (execute-in! ctx cycle-id hook-executor)
      (execute-in! ctx cycle-id))))
(defn- run-verify-step
  [ctx cycle-id check-runner]
  (when (= :verifying (cycle-status-in ctx cycle-id))
    (if check-runner
      (verify-in! ctx cycle-id check-runner)
      (verify-in! ctx cycle-id))))
(defn- run-learn-step
  [ctx cycle-id memory-ctx]
  (when (= :learning (cycle-status-in ctx cycle-id))
    (learn-in! ctx cycle-id (resolve-memory-ctx memory-ctx))))
(defn- run-orchestration-steps
  [ctx cycle-id {:keys [hook-executor check-runner memory-ctx]}]
  (let [execute-result      (run-execute-step ctx cycle-id hook-executor)
        verify-result       (run-verify-step ctx cycle-id check-runner)
        learn-result        (run-learn-step ctx cycle-id memory-ctx)
        future-state-result (when (:ok? learn-result)
                              (update-future-state-from-outcome-in! ctx cycle-id))
        finalize-result     (when (:ok? future-state-result)
                              (finalize-cycle-in! ctx cycle-id))]
    {:execute-result execute-result
     :verify-result verify-result
     :learn-result learn-result
     :future-state-result future-state-result
     :finalize-result finalize-result}))
(defn- failed-approval-step [steps]
  (when (and (:approval-result steps) (not (:ok? (:approval-result steps))))
    [:approval (:approval-result steps)]))
(defn- failed-execute-step [steps]
  (when (and (:execute-result steps) (not (:ok? (:execute-result steps))))
    [:execute (:execute-result steps)]))
(defn- failed-verify-step [steps]
  (when (and (:verify-result steps) (not (:ok? (:verify-result steps))))
    [:verify (:verify-result steps)]))
(defn- failed-learn-step [steps]
  (when (and (:learn-result steps) (not (:ok? (:learn-result steps))))
    [:learn (:learn-result steps)]))
(defn- failed-future-state-step [steps]
  (when (and (:future-state-result steps) (not (:ok? (:future-state-result steps))))
    [:future-state (:future-state-result steps)]))
(defn- failed-finalize-step [steps]
  (when (and (:finalize-result steps) (not (:ok? (:finalize-result steps))))
    [:finalize (:finalize-result steps)]))
(defn- failed-step
  [steps]
  (or (failed-approval-step steps)
      (failed-execute-step steps)
      (failed-verify-step steps)
      (failed-learn-step steps)
      (failed-future-state-step steps)
      (failed-finalize-step steps)))
(defn- invalid-approval-result
  [trigger-result approval-decision]
  {:ok? false
   :error :invalid-approval-decision
   :approval-decision approval-decision
   :expected #{:approve :reject nil}
   :trigger-result trigger-result})
(defn- pre-approval-steps
  [ctx cycle-id system-state graph-state memory-state trigger-result]
  (let [observe-result (observe-in! ctx cycle-id system-state graph-state memory-state)
        plan-result    (when (:ok? observe-result)
                         (plan-in! ctx cycle-id))
        gate-result    (when (:ok? plan-result)
                         (apply-approval-gate-in! ctx cycle-id))]
    {:trigger-result trigger-result
     :observe-result observe-result
     :plan-result plan-result
     :gate-result gate-result}))
(defn- pre-approval-failure
  [cycle-id {:keys [observe-result plan-result] :as base-steps}]
  (cond
    (not (:ok? observe-result))
    (orchestration-result false :observe cycle-id base-steps)
    (not (:ok? plan-result))
    (orchestration-result false :plan cycle-id base-steps)
    :else nil))
(defn- continue-after-approval
  [ctx cycle-id base-steps approval-result hook-executor check-runner memory-ctx]
  (let [step-results (merge base-steps
                            {:approval-result approval-result}
                            (run-orchestration-steps ctx cycle-id {:hook-executor hook-executor
                                                                   :check-runner check-runner
                                                                   :memory-ctx memory-ctx}))]
    (if-let [[phase _failed-result] (failed-step step-results)]
      (orchestration-result false phase cycle-id step-results)
      (orchestration-result true :completed cycle-id step-results))))
(defn- approval-phase-result
  [ctx cycle-id gate-result decision approver approval-notes base-steps hook-executor check-runner memory-ctx]
  (let [{:keys [status result approval-result]}
        (approval-step-result ctx cycle-id gate-result decision approver approval-notes)]
    (case status
      :error (merge base-steps result)
      :awaiting (merge base-steps result)
      :continue (continue-after-approval ctx cycle-id base-steps approval-result hook-executor check-runner memory-ctx))))
(defn orchestrate-manual-trigger-in!
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
        decision       (normalize-approval-decision approval-decision)]
    (cond
      (not= :accepted (:result trigger-result))
      {:ok? true
       :trigger-result trigger-result
       :phase :trigger}
      (= ::invalid decision)
      (invalid-approval-result trigger-result approval-decision)
      :else
      (let [cycle-id   (:cycle-id trigger-result)
            base-steps (pre-approval-steps ctx cycle-id system-state graph-state memory-state trigger-result)]
        (or (pre-approval-failure cycle-id base-steps)
            (approval-phase-result ctx cycle-id (:gate-result base-steps) decision approver approval-notes
                                   base-steps hook-executor check-runner memory-ctx))))))
(defn orchestrate-manual-trigger!
  ([trigger-signal]
   (orchestrate-manual-trigger! trigger-signal {}))
  ([trigger-signal opts]
   (orchestrate-manual-trigger-in! (global-context) trigger-signal opts)))
(defn- find-cycle
  [cycles cycle-id]
  (op/find-cycle cycles cycle-id))
(defn- update-cycle
  [cycles cycle-id f]
  (op/update-cycle cycles cycle-id f))
(defn observe-in!
  [ctx cycle-id system-state graph-state memory-state]
  (op/observe-in! get-state-in swap-state-in! ctx cycle-id system-state graph-state memory-state))
(defn observe!
  [cycle-id system-state graph-state memory-state]
  (observe-in! (global-context) cycle-id system-state graph-state memory-state))
(defn plan-in!
  [ctx cycle-id]
  (op/plan-in! get-state-in swap-state-in! ctx cycle-id))
(defn plan!
  [cycle-id]
  (plan-in! (global-context) cycle-id))
(defn apply-approval-gate-in!
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
          (do
            (swap-state-in! ctx
                            (fn [s]
                              (-> s
                                  (assoc :status :awaiting-approval)
                                  (update :cycles update-cycle cycle-id
                                          #(assoc % :status :awaiting-approval)))))
            {:gate :manual})
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
  [cycle-id]
  (apply-approval-gate-in! (global-context) cycle-id))
(defn approve-proposal-in!
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
  [cycle-id approver notes]
  (approve-proposal-in! (global-context) cycle-id approver notes))
(defn reject-proposal-in!
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
  [cycle-id approver notes]
  (reject-proposal-in! (global-context) cycle-id approver notes))
(defn- default-hook-executor
  [_action]
  {:status :success, :output-summary "hook-not-implemented"})
(defn execute-in!
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
  ([cycle-id]
   (execute-in! (global-context) cycle-id))
  ([cycle-id hook-executor]
   (execute-in! (global-context) cycle-id hook-executor)))
(defn- default-check-runner
  [_check-name]
  {:passed true, :details "check-not-implemented"})
(defn- failed-check-names
  [checks]
  (into #{} (comp (remove :passed) (map :name)) checks))
(defn rollback-in!
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
  [cycle-id]
  (rollback-in! (global-context) cycle-id))
(defn verify-in!
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
           (let [failed (failed-check-names checks)
                 rollback? (:rollback-on-verification-failure policy)
                 summary (if rollback?
                           "verification_failed_rolled_back"
                           "verification_failed")
                 outcome {:status :failed
                          :summary summary
                          :evidence failed
                          :changed-goals #{}}]
             (when rollback?
               (rollback-in! ctx cycle-id))
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
  ([cycle-id]
   (verify-in! (global-context) cycle-id))
  ([cycle-id check-runner]
   (verify-in! (global-context) cycle-id check-runner)))
(defn- build-success-outcome
  [cycle future-state]
  (let [action-titles (into #{} (map :title) (get-in cycle [:proposal :actions]))
        changed-goals (into #{} (map :id) (:goals future-state))]
    {:status :success
     :summary "cycle_completed_successfully"
     :evidence action-titles
     :changed-goals changed-goals}))
(defn- build-memory-content
  [cycle-id outcome cycle]
  (let [action-titles (mapv :title (get-in cycle [:proposal :actions]))]
    (str "Remember cycle " cycle-id ": "
         (name (:status outcome)) ". "
         (:summary outcome) ". "
         "Actions: " (pr-str action-titles) ".")))
(defn learn-in!
  [ctx cycle-id memory-ctx]
  (let [state (get-state-in ctx)
        cycle (find-cycle (:cycles state) cycle-id)]
    (cond
      (nil? cycle)
      {:ok? false, :error :cycle-not-found}
      (not= :learning (:status cycle))
      {:ok? false, :error :wrong-cycle-status, :status (:status cycle)}
      :else
      (let [outcome (or (:outcome cycle)
                        (build-success-outcome cycle (:current-future-state state)))
            _ (when (nil? (:outcome cycle))
                (swap-state-in! ctx
                                (fn [s]
                                  (update s :cycles update-cycle cycle-id
                                          #(assoc % :outcome outcome)))))
            content (build-memory-content cycle-id outcome cycle)
            trigger-type (get-in cycle [:trigger :type])
            remember-fn (requiring-resolve 'psi.memory.core/remember-in!)
            mem-result (remember-fn memory-ctx
                                    {:content-type :discovery
                                     :content content
                                     :tags ["remember" "cycle" "step-11"]
                                     :provenance {:source :remember
                                                  :cycle-id cycle-id
                                                  :trigger-type trigger-type
                                                  :outcome-status (:status outcome)}})
            record-id (get-in mem-result [:record :record-id])]
        (if (:ok? mem-result)
          (do
            (swap-state-in! ctx
                            (fn [s]
                              (update s :cycles update-cycle cycle-id
                                      #(update % :learning-memory-ids conj record-id))))
            {:ok? true, :memory-ids #{record-id}})
          {:ok? false, :error :memory-write-failed, :details mem-result})))))
(defn learn!
  [cycle-id memory-ctx]
  (learn-in! (global-context) cycle-id memory-ctx))
(defn update-future-state-from-outcome-in!
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
                         (future-state/next-version current-fs))]
        (swap-state-in! ctx assoc :current-future-state updated-fs)
        {:ok? true, :future-state updated-fs}))))
(defn update-future-state-from-outcome!
  [cycle-id]
  (update-future-state-from-outcome-in! (global-context) cycle-id))
(defn finalize-cycle-in!
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
  [cycle-id]
  (finalize-cycle-in! (global-context) cycle-id))
(defn- continue-cycle-precheck
  [cycle]
  (cond
    (nil? cycle)
    {:ok? false :error :cycle-not-found}
    (= :awaiting-approval (:status cycle))
    {:ok? false :error :awaiting-approval}
    (contains? #{:completed :failed :aborted :blocked} (:status cycle))
    {:ok? true :phase :terminal :status (:status cycle)}
    :else nil))
(defn- continue-cycle-execute-step
  [ctx cycle-id cycle hook-executor]
  (when (= :executing (:status cycle))
    (if hook-executor
      (execute-in! ctx cycle-id hook-executor)
      (execute-in! ctx cycle-id))))
(defn- continue-cycle-verify-step
  [ctx cycle-id check-runner]
  (let [cycle-after-exec (find-cycle (:cycles (get-state-in ctx)) cycle-id)]
    (when (= :verifying (:status cycle-after-exec))
      (if check-runner
        (verify-in! ctx cycle-id check-runner)
        (verify-in! ctx cycle-id)))))
(defn- continue-cycle-learn-step
  [ctx cycle-id memory-ctx]
  (let [cycle-after-verify (find-cycle (:cycles (get-state-in ctx)) cycle-id)]
    (when (= :learning (:status cycle-after-verify))
      (learn-in! ctx cycle-id (resolve-memory-ctx memory-ctx)))))
(defn- continue-cycle-results
  [ctx cycle-id cycle {:keys [memory-ctx hook-executor check-runner]}]
  (let [execute-result      (continue-cycle-execute-step ctx cycle-id cycle hook-executor)
        verify-result       (continue-cycle-verify-step ctx cycle-id check-runner)
        learn-result        (continue-cycle-learn-step ctx cycle-id memory-ctx)
        future-state-result (when (:ok? learn-result)
                              (update-future-state-from-outcome-in! ctx cycle-id))
        finalize-result     (when (:ok? future-state-result)
                              (finalize-cycle-in! ctx cycle-id))]
    {:execute-result execute-result
     :verify-result verify-result
     :learn-result learn-result
     :future-state-result future-state-result
     :finalize-result finalize-result}))
(defn- continue-cycle-failure
  [{:keys [execute-result verify-result learn-result future-state-result finalize-result]}]
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
    :else nil))
(defn continue-cycle-in!
  [ctx cycle-id opts]
  (let [state (get-state-in ctx)
        cycle (find-cycle (:cycles state) cycle-id)]
    (or (continue-cycle-precheck cycle)
        (let [results (continue-cycle-results ctx cycle-id cycle opts)]
          (or (continue-cycle-failure results)
              (merge {:ok? true
                      :phase :completed}
                     results))))))
(defn continue-cycle!
  ([cycle-id]
   (continue-cycle! cycle-id {}))
  ([cycle-id opts]
   (continue-cycle-in! (global-context) cycle-id opts)))
(defn register-resolvers-in!
  ([qctx]
   (register-resolvers-in! qctx true))
  ([qctx rebuild?]
   (query-registration/register-resolvers-in! qctx rebuild?)))
(defn register-mutations-in!
  ([qctx]
   (register-mutations-in! qctx true))
  ([qctx rebuild?]
   (query-registration/register-mutations-in! qctx rebuild?)))
(defn register-resolvers!
  []
  (query-registration/register-resolvers!))
