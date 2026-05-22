(ns psi.deterministic-operation-registry.registry
  "Runtime-owned deterministic operation registry.

   Shared root-registry storage is authoritative for canonical operation ids
   used by workflow IR invoke steps. This adapter retains the runtime object
   boundary, deterministic-operation validation/normalization, duplicate throw
   behaviour, and invoke-time lookup-plus-execution semantics."
  (:require
   [psi.deterministic-operation-registry.defs :as defs]
   [psi.root-registry.registry :as root-registry]))

(def ^:private registry-id
  :deterministic-operations)

(defrecord DeterministicOperationRegistry [state])

(defn- ensure-root-registry-declared
  [state]
  (if (root-registry/declared-registry? state registry-id)
    state
    (root-registry/declare-registry state registry-id)))

(defn- state-in
  [reg]
  @(:state reg))

(def ^:private runtime-extension-id
  :runtime)

(defn- operation-entry
  [operation]
  {:id (:id operation)
   :extension-id (or (:ext-path operation) runtime-extension-id)
   :value operation})

(defn- lower-entry->operation
  [entry]
  (:value entry))

(defn create-registry
  []
  (->DeterministicOperationRegistry
   (atom {:root-state (root-registry/declared-root-state [registry-id])})))

(defn register-operation-in!
  [reg operation]
  (let [operation* (defs/normalize-operation-def operation)]
    (swap! (:state reg)
           (fn [state]
             (let [root-state (ensure-root-registry-declared (:root-state state))
                   {:keys [root-state result]}
                   (root-registry/insert root-state registry-id (operation-entry operation*))]
               (if (:ok? result)
                 (assoc state :root-state root-state)
                 (case (:failure-kind result)
                   :duplicate-id
                   (throw (ex-info "Deterministic operation id already registered"
                                   {:operation-id (:id operation*)
                                    :existing (lower-entry->operation (:previous-entry result))
                                    :new operation*
                                    :root-result result}))

                   (throw (ex-info "Failed to register deterministic operation"
                                   {:operation operation*
                                    :root-result result})))))))
    reg))

(defn unregister-operations-by-extension-in!
  [reg ext-path]
  (swap! (:state reg)
         (fn [state]
           (let [root-state (ensure-root-registry-declared (:root-state state))
                 {:keys [root-state result]}
                 (root-registry/clear-by-extension root-state registry-id ext-path)]
             (if (or (:ok? result)
                     (= :not-found (:failure-kind result)))
               (assoc state :root-state root-state)
               (throw (ex-info "Failed to unregister deterministic operations by extension"
                               {:ext-path ext-path
                                :root-result result}))))))
  reg)

(defn operation-ids-in
  [reg]
  (->> (root-registry/list-entries (ensure-root-registry-declared (:root-state (state-in reg)))
                                   registry-id)
       :result
       :entries
       (map :id)
       vec))

(defn operation-count-in
  [reg]
  (->> (root-registry/list-entries (ensure-root-registry-declared (:root-state (state-in reg)))
                                   registry-id)
       :result
       :count))

(defn get-operation-in
  [reg operation-id]
  (-> (root-registry/lookup (ensure-root-registry-declared (:root-state (state-in reg)))
                            registry-id
                            operation-id)
      :result
      :value
      lower-entry->operation))

(defn all-operations-in
  [reg]
  (->> (root-registry/list-entries (ensure-root-registry-declared (:root-state (state-in reg)))
                                   registry-id)
       :result
       :entries
       (mapv lower-entry->operation)))

(defn invoke-operation-in
  [reg operation-id invocation invoke-operation]
  (let [operation (get-operation-in reg operation-id)]
    (when-not operation
      (throw (ex-info "Deterministic operation not found"
                      {:type :missing-deterministic-operation
                       :operation-id operation-id})))
    (invoke-operation operation invocation)))
