(ns psi.agent-session.workflow-runtime
  "Pure canonical-root workflow state operations for deterministic workflow runs.

   Scope of this slice:
   - register workflow definitions in canonical root state
   - create workflow runs with immutable effective-definition snapshots
   - expose small pure lookup helpers for later dispatch/mutation/query layers"
  (:require
   [clojure.string :as str]
   [psi.agent-session.workflow-current-ir-compiler :as workflow-current-ir-compiler]
   [psi.agent-session.workflow-model :as workflow-model]
   [psi.agent-session.workflow-statechart :as workflow-statechart]
   [psi.agent-session.workflow-target-ir-compiler :as workflow-target-ir-compiler]))

(defn- now []
  (java.time.Instant/now))

(defn- blankish? [x]
  (or (nil? x)
      (and (string? x) (str/blank? x))))

(defn normalize-id
  [id]
  (cond
    (blankish? id) (str (java.util.UUID/randomUUID))
    (keyword? id)  (name id)
    :else          (str id)))

(defn definitions-path [] [:workflows :definitions])
(defn runs-path [] [:workflows :runs])
(defn run-order-path [] [:workflows :run-order])
(defn definition-path [definition-id] [:workflows :definitions definition-id])
(defn run-path [run-id] [:workflows :runs run-id])

(defn workflow-definition-in
  [state definition-id]
  (get-in state (definition-path definition-id)))

(defn workflow-run-in
  [state run-id]
  (get-in state (run-path run-id)))

(defn list-workflow-runs
  [state]
  (let [runs (get-in state (runs-path))
        order (get-in state (run-order-path))]
    (mapv #(get runs %) order)))

(defn register-definition
  "Return [state definition-id stored-definition] after validating and storing definition."
  [state definition]
  (when-not (or (workflow-model/valid-workflow-definition? definition)
                (workflow-target-ir-compiler/target-authored-workflow-definition? definition))
    (throw (ex-info "Invalid workflow definition"
                    {:explanation (workflow-model/explain-workflow-definition definition)})))
  (let [definition-id (normalize-id (:definition-id definition))
        stored-definition (assoc definition :definition-id definition-id)]
    [(assoc-in state (definition-path definition-id) stored-definition)
     definition-id
     stored-definition]))

(defn remove-definition
  "Return [state removed-definition] after removing a registered workflow definition."
  [state definition-id]
  (let [definition-id' (normalize-id definition-id)
        definition (workflow-definition-in state definition-id')]
    (when-not definition
      (throw (ex-info "Workflow definition not found" {:definition-id definition-id'})))
    [(update-in state (definitions-path) dissoc definition-id')
     definition]))

(defn- workflow-definition-source
  [definition-id]
  (if definition-id
    :registered-definition
    :inline-definition))

(defn- compile-definition-to-ir!
  [definition source]
  (let [target-authored? (workflow-target-ir-compiler/target-authored-workflow-definition? definition)
        {:keys [valid? ir structural-errors semantic-errors compile-error]}
        (if target-authored?
          (workflow-target-ir-compiler/compile-and-validate-workflow-definition definition)
          (workflow-current-ir-compiler/compile-and-validate-workflow-definition definition))]
    (when-not valid?
      (throw (ex-info "Workflow definition does not compile to execution-valid canonical IR"
                      {:source source
                       :definition-id (:definition-id definition)
                       :authored-grammar (if target-authored? :target :current)
                       :compile-error compile-error
                       :structural-errors structural-errors
                       :semantic-errors semantic-errors})))
    ir))

(def ^:private target-compat-retry-policy
  {:max-attempts 1
   :retry-on #{:execution-failed :validation-failed}})

(defn- target-step->compat-step
  [step]
  (merge {:executor {:type :agent}
          :result-schema [:map]
          :retry-policy target-compat-retry-policy}
         step))

(defn- normalize-effective-definition
  [definition source]
  (let [canonical-ir (compile-definition-to-ir! definition source)
        target-authored? (workflow-target-ir-compiler/target-authored-workflow-definition? definition)]
    (cond-> (assoc definition
                   :definition-id (normalize-id (:definition-id definition))
                   :canonical-ir canonical-ir)
      target-authored?
      (assoc :step-order (mapv :name (:steps canonical-ir))
             :steps (into {}
                          (map (fn [step]
                                 [(:name step) (target-step->compat-step step)]))
                          (:steps definition))))))

(defn- resolve-effective-definition
  [state {:keys [definition definition-id]}]
  (cond
    (some? definition)
    (do
      (when-not (or (workflow-model/valid-workflow-definition? definition)
                    (workflow-target-ir-compiler/target-authored-workflow-definition? definition))
        (throw (ex-info "Invalid inline workflow definition"
                        {:explanation (workflow-model/explain-workflow-definition definition)})))
      {:effective-definition (normalize-effective-definition definition
                                                             (workflow-definition-source nil))
       :source-definition-id nil})

    (some? definition-id)
    (let [resolved (workflow-definition-in state (normalize-id definition-id))]
      (when-not resolved
        (throw (ex-info "Workflow definition not found"
                        {:definition-id definition-id})))
      {:effective-definition (normalize-effective-definition resolved
                                                             (workflow-definition-source definition-id))
       :source-definition-id (:definition-id resolved)})

    :else
    (throw (ex-info "Workflow run creation requires :definition or :definition-id" {}))))

(defn- initial-step-runs
  [definition]
  (into {}
        (map (fn [step-id]
               [step-id {:step-id step-id
                         :attempts []}])
             (:step-order definition))))

(defn create-run
  "Return [state run-id workflow-run] after creating a new canonical workflow run."
  [state {:keys [run-id workflow-input] :as opts}]
  (let [{:keys [effective-definition source-definition-id]}
        (resolve-effective-definition state opts)
        initial-step-id (workflow-statechart/initial-step-id effective-definition)
        run-id'         (normalize-id run-id)
        ts              (now)
        run             {:run-id run-id'
                         :status :pending
                         :effective-definition effective-definition
                         :source-definition-id source-definition-id
                         :workflow-input (or workflow-input {})
                         :current-step-id initial-step-id
                         :step-runs (initial-step-runs effective-definition)
                         :history [{:event :workflow/run-created
                                    :timestamp ts
                                    :data {:run-id run-id'
                                           :source-definition-id source-definition-id
                                           :current-step-id initial-step-id}}]
                         :created-at ts
                         :updated-at ts}]
    (when-not (workflow-model/valid-workflow-run? run)
      (throw (ex-info "Invalid workflow run"
                      {:explanation (workflow-model/explain-workflow-run run)})))
    [(-> state
         (assoc-in (run-path run-id') run)
         (update-in (run-order-path) (fnil conj []) run-id'))
     run-id'
     run]))

(defn update-run-workflow-input
  "Return [state updated-run] after replacing a run's workflow input.

   Intended for continue/resume flows that need to push a new top-level prompt
   into an existing blocked run before re-executing the current step."
  [state run-id workflow-input]
  (let [run (workflow-run-in state run-id)]
    (when-not run
      (throw (ex-info "Workflow run not found" {:run-id run-id})))
    (let [updated-run (-> run
                          (assoc :workflow-input (or workflow-input {}))
                          (assoc :updated-at (now))
                          (update :history (fnil conj [])
                                  {:event :workflow/input-updated
                                   :timestamp (now)
                                   :data {:run-id run-id
                                          :workflow-input (or workflow-input {})}}))]
      [(assoc-in state (run-path run-id) updated-run)
       updated-run])))

(defn resume-run
  "Return [state resumed-run] after clearing a blocked run back to :running.

   Resume does not mutate the blocked attempt; callers should create a new attempt
   afterwards before re-executing the current step."
  [state run-id]
  (let [run (workflow-run-in state run-id)]
    (when-not run
      (throw (ex-info "Workflow run not found" {:run-id run-id})))
    (let [resumed-run (-> run
                          (assoc :status :running
                                 :blocked nil
                                 :updated-at (now))
                          (update :history (fnil conj [])
                                  {:event :workflow/resume
                                   :timestamp (now)
                                   :data {:run-id run-id
                                          :step-id (:current-step-id run)}}))]
      [(assoc-in state (run-path run-id) resumed-run)
       resumed-run])))

(defn cancel-run
  "Return [state cancelled-run] after cancelling a non-terminal workflow run."
  [state run-id reason]
  (let [run (workflow-run-in state run-id)]
    (when-not run
      (throw (ex-info "Workflow run not found" {:run-id run-id})))
    (let [cancelled-run (-> run
                            (assoc :status :cancelled
                                   :blocked nil
                                   :current-step-id (:current-step-id run)
                                   :updated-at (now)
                                   :finished-at (now)
                                   :terminal-outcome {:outcome :cancelled
                                                      :reason reason
                                                      :step-id (:current-step-id run)})
                            (update :history (fnil conj [])
                                    {:event :workflow/cancel
                                     :timestamp (now)
                                     :data {:run-id run-id
                                            :step-id (:current-step-id run)
                                            :reason reason}}))]
      [(assoc-in state (run-path run-id) cancelled-run)
       cancelled-run])))

(defn remove-run
  "Return [state removed-run] after removing a workflow run from canonical state."
  [state run-id]
  (let [run (workflow-run-in state run-id)]
    (when-not run
      (throw (ex-info "Workflow run not found" {:run-id run-id})))
    [(-> state
         (update-in (runs-path) dissoc run-id)
         (update-in (run-order-path)
                    (fn [order]
                      (->> (or order [])
                           (remove #(= run-id %))
                           vec))))
     run]))
