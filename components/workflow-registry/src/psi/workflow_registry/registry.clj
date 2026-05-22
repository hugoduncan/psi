(ns psi.workflow-registry.registry
  "Workflow-specific registry adapter over the shared root-registry substrate.

   Owns workflow-definition-specific path helpers, identity normalization,
   validation, sorted projection, tuple-shaped pure operation contracts, and
   canonical persisted compatibility projection at [:workflows :definitions]."
  (:require
   [clojure.string :as str]
   [psi.root-registry.registry :as root-registry]
   [psi.workflow-registry.definition :as definition]))

(def ^:private registry-id
  :workflow-definitions)

(def ^:private compatibility-extension-id
  :workflow-definition)

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

(defn- ensure-root-registry-declared
  [state]
  (if (root-registry/declared-registry? state registry-id)
    state
    (root-registry/declare-registry state registry-id)))

(defn- ensure-valid-definition!
  [definition]
  (when-not (definition/target-authored-workflow-definition? definition)
    (throw (ex-info "Invalid target-authored workflow definition"
                    {:definition definition})))
  definition)

(defn- root-entry->stored-definition
  [entry]
  (:value entry))

(defn- definition-entry
  [stored-definition]
  {:id (:definition-id stored-definition)
   :extension-id compatibility-extension-id
   :value stored-definition})

(defn- definitions-by-id
  [state]
  (let [root-state (ensure-root-registry-declared state)
        result (:result (root-registry/list-entries root-state registry-id))]
    (into {}
          (map (juxt :definition-id identity))
          (map root-entry->stored-definition (:entries result)))))

(defn- sync-compatibility-path
  [state]
  (assoc-in state (definitions-path) (definitions-by-id state)))

(defn workflow-definition
  "Return the registered workflow definition for `definition-id`, or nil.

   Public lookup normalizes caller-provided ids before lookup."
  [state definition-id]
  (-> (root-registry/lookup (ensure-root-registry-declared state)
                            registry-id
                            (normalize-id definition-id))
      :result
      :value
      root-entry->stored-definition))

(defn list-definitions
  "Return all registered workflow definitions sorted by canonical :definition-id."
  [state]
  (->> (definitions-by-id state)
       vals
       (sort-by :definition-id)
       vec))

(defn definition-ids
  "Return the sorted registered workflow definition ids."
  [state]
  (mapv :definition-id (list-definitions state)))

(defn register-definition
  "Return [state definition-id stored-definition] after validating and storing
   a target-authored definition."
  [state definition]
  (let [definition (ensure-valid-definition! definition)
        definition-id (normalize-id (:definition-id definition))
        stored-definition (assoc definition :definition-id definition-id)
        root-result (root-registry/register (ensure-root-registry-declared state)
                                            registry-id
                                            (definition-entry stored-definition))]
    [(-> (:root-state root-result)
         sync-compatibility-path)
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
    (let [root-result (root-registry/unregister (ensure-root-registry-declared state)
                                                registry-id
                                                definition-id')]
      [(-> (:root-state root-result)
           sync-compatibility-path)
       definition])))
