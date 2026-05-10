(ns psi.workflow-registry.registry
  "Canonical workflow-definition registry ownership over root workflow state.

   Owns workflow-definition-specific path helpers, identity normalization,
   validation, registration/removal semantics, and public query helpers."
  (:require
   [clojure.string :as str]
   [psi.workflow-registry.definition :as definition]))

(defn- blankish?
  [x]
  (or (nil? x)
      (and (string? x) (str/blank? x))))

(defn normalize-id
  [id]
  (cond
    (blankish? id) (str (java.util.UUID/randomUUID))
    (keyword? id)  (name id)
    :else          (str id)))

(defn definitions-path
  []
  [:workflows :definitions])

(defn definition-path
  [definition-id]
  [:workflows :definitions definition-id])

(defn workflow-definition
  "Return the registered workflow definition for `definition-id`, or nil.

   Public lookup normalizes caller-provided ids before lookup."
  [state definition-id]
  (get-in state (definition-path (normalize-id definition-id))))

(defn list-definitions
  "Return all registered workflow definitions sorted by canonical :definition-id."
  [state]
  (->> (get-in state (definitions-path))
       vals
       (sort-by :definition-id)
       vec))

(defn definition-ids
  "Return the sorted registered workflow definition ids."
  [state]
  (mapv :definition-id (list-definitions state)))

(defn- ensure-valid-definition!
  [definition]
  (when-not (definition/target-authored-workflow-definition? definition)
    (throw (ex-info "Invalid target-authored workflow definition"
                    {:definition definition})))
  definition)

(defn register-definition
  "Return [state definition-id stored-definition] after validating and storing
   a target-authored definition."
  [state definition]
  (let [definition (ensure-valid-definition! definition)
        definition-id (normalize-id (:definition-id definition))
        stored-definition (assoc definition :definition-id definition-id)]
    [(assoc-in state (definition-path definition-id) stored-definition)
     definition-id
     stored-definition]))

(defn remove-definition
  "Return [state removed-definition] after removing a registered workflow
   definition. Public removal normalizes caller-provided ids first."
  [state definition-id]
  (let [definition-id' (normalize-id definition-id)
        definition (workflow-definition state definition-id')]
    (when-not definition
      (throw (ex-info "Workflow definition not found" {:definition-id definition-id'})))
    [(update-in state (definitions-path) dissoc definition-id')
     definition]))
