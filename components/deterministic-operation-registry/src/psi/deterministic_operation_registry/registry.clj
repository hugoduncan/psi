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
   (atom {:operations {}
          :registration-order []})))

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
             (-> s
                 (assoc-in [:operations operation-id] operation*)
                 (update :registration-order conj operation-id))))
    reg))

(defn unregister-operations-by-extension-in!
  [reg ext-path]
  (swap! (:state reg)
         (fn [s]
           (let [remove-ids (->> (:registration-order s)
                                 (filter #(= ext-path (get-in s [:operations % :ext-path])))
                                 vec)]
             (-> s
                 (update :operations #(apply dissoc % remove-ids))
                 (update :registration-order (fn [order]
                                               (vec (remove (set remove-ids) order))))))))
  reg)

(defn operation-ids-in
  [reg]
  (:registration-order @(:state reg)))

(defn operation-count-in
  [reg]
  (count (operation-ids-in reg)))

(defn get-operation-in
  [reg operation-id]
  (get-in @(:state reg) [:operations operation-id]))

(defn all-operations-in
  [reg]
  (mapv #(get-operation-in reg %) (operation-ids-in reg)))

(defn invoke-operation-in
  [reg operation-id invocation invoke-operation]
  (let [operation (get-operation-in reg operation-id)]
    (when-not operation
      (throw (ex-info "Deterministic operation not found"
                      {:type :missing-deterministic-operation
                       :operation-id operation-id})))
    (invoke-operation operation invocation)))
