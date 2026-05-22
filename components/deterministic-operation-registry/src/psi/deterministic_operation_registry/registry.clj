(ns psi.deterministic-operation-registry.registry
  "Runtime-owned deterministic operation registry.

   The registry is authoritative for stable operation ids used by workflow IR
   invoke steps. Extensions may contribute implementations, but workflow authors
   target ids, not vars/functions."
  (:require
   [psi.deterministic-operation-registry.defs :as defs]))

(defrecord DeterministicOperationRegistry [state])

(defn create-registry
  []
  (->DeterministicOperationRegistry
   (atom {:operations {}})))

(defn register-operation-in!
  [reg operation]
  (let [operation* (defs/normalize-operation-def operation)
        operation-id (:id operation*)]
    (swap! (:state reg)
           (fn [s]
             (when-let [existing (get-in s [:operations operation-id])]
               (throw (ex-info "Deterministic operation id already registered"
                               {:operation-id operation-id
                                :existing existing
                                :new operation*})))
             (assoc-in s [:operations operation-id] operation*)))
    reg))

(defn unregister-operations-by-extension-in!
  [reg ext-path]
  (swap! (:state reg)
         (fn [s]
           (update s :operations
                   (fn [operations]
                     (into {}
                           (remove (fn [[_ operation]]
                                     (= ext-path (:ext-path operation))))
                           operations)))))
  reg)

(defn operation-ids-in
  [reg]
  (-> @(:state reg) :operations keys vec))

(defn operation-count-in
  [reg]
  (-> @(:state reg) :operations count))

(defn get-operation-in
  [reg operation-id]
  (get-in @(:state reg) [:operations operation-id]))

(defn all-operations-in
  [reg]
  (-> @(:state reg) :operations vals vec))

(defn invoke-operation-in
  [reg operation-id invocation invoke-operation]
  (let [operation (get-operation-in reg operation-id)]
    (when-not operation
      (throw (ex-info "Deterministic operation not found"
                      {:type :missing-deterministic-operation
                       :operation-id operation-id})))
    (invoke-operation operation invocation)))
