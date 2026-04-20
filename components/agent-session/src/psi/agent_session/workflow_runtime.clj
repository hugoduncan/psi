(ns psi.agent-session.workflow-runtime
  "Pure canonical-root workflow state operations for deterministic workflow runs.

   Scope of this slice:
   - register workflow definitions in canonical root state
   - create workflow runs with immutable effective-definition snapshots
   - expose small pure lookup helpers for later dispatch/mutation/query layers"
  (:require
   [clojure.string :as str]
   [psi.agent-session.workflow-model :as workflow-model]
   [psi.agent-session.workflow-statechart :as workflow-statechart]))

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
  (when-not (workflow-model/valid-workflow-definition? definition)
    (throw (ex-info "Invalid workflow definition"
                    {:explanation (workflow-model/explain-workflow-definition definition)})))
  (let [definition-id (normalize-id (:definition-id definition))
        stored-definition (assoc definition :definition-id definition-id)]
    [(assoc-in state (definition-path definition-id) stored-definition)
     definition-id
     stored-definition]))

(defn- resolve-effective-definition
  [state {:keys [definition definition-id]}]
  (cond
    (some? definition)
    (do
      (when-not (workflow-model/valid-workflow-definition? definition)
        (throw (ex-info "Invalid inline workflow definition"
                        {:explanation (workflow-model/explain-workflow-definition definition)})))
      {:effective-definition (assoc definition :definition-id (normalize-id (:definition-id definition)))
       :source-definition-id nil})

    (some? definition-id)
    (let [resolved (workflow-definition-in state (normalize-id definition-id))]
      (when-not resolved
        (throw (ex-info "Workflow definition not found"
                        {:definition-id definition-id})))
      {:effective-definition resolved
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
        compiled (workflow-statechart/compile-definition effective-definition)
        run-id'  (normalize-id run-id)
        ts       (now)
        run      {:run-id run-id'
                  :status :pending
                  :effective-definition effective-definition
                  :source-definition-id source-definition-id
                  :workflow-input (or workflow-input {})
                  :current-step-id (:initial-step-id compiled)
                  :step-runs (initial-step-runs effective-definition)
                  :history [{:event :workflow/run-created
                             :timestamp ts
                             :data {:run-id run-id'
                                    :source-definition-id source-definition-id
                                    :current-step-id (:initial-step-id compiled)}}]
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
