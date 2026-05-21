(ns psi.root-registry.registry
  "Shared lower-level root-state registry substrate.

   Hosts multiple declared registries inside one root-state area with uniform
   id/owner semantics. The shared component owns only common storage,
   validation, and mutation/query contracts; consuming registries remain
   responsible for registry-specific canonicalization and validation before
   calling into this namespace.")

(declare declare-registry)

(def ^:private root-state-key
  :root-registries)

(defn empty-root-state
  "Return the empty shared root-registry host state."
  []
  {root-state-key {}})

(defn declared-root-state
  "Return an empty shared root-state host with `registry-ids` declared."
  [registry-ids]
  (reduce (fn [state registry-id]
            (declare-registry state registry-id))
          (empty-root-state)
          registry-ids))

(defn registry-area
  "Return the shared root-registry area from `root-state`.

   Missing host state reads as empty."
  [root-state]
  (get root-state root-state-key {}))

(defn declared-registry-state
  "Return the canonical empty state for one declared registry."
  []
  {:entries-by-id {}
   :ids-by-extension {}})

(defn registry-state
  "Return the registry state for `registry-id`, or nil when undeclared."
  [root-state registry-id]
  (get (registry-area root-state) registry-id))

(defn declared-registry?
  "Return true when `registry-id` is explicitly declared in `root-state`."
  [root-state registry-id]
  (contains? (registry-area root-state) registry-id))

(defn declare-registry
  "Declare `registry-id` in `root-state`.

   Declaration is idempotent."
  [root-state registry-id]
  (update root-state root-state-key
          #(assoc (or % {}) registry-id
                  (or (get % registry-id)
                      (declared-registry-state)))))

(defn list-registry-ids
  "Return the declared registry ids hosted in `root-state`."
  [root-state]
  (keys (registry-area root-state)))

(defn- result
  [operation registry-id ok? status & kvs]
  (into {:ok? ok?
         :status status
         :operation operation
         :registry-id registry-id}
        (apply array-map kvs)))

(defn- invalid-entry-result
  [registry-id entry message]
  (result :register registry-id false :failed
          :failure-kind :invalid-entry
          :entry entry
          :message message))

(defn- unknown-registry-result
  [operation registry-id]
  (result operation registry-id false :failed
          :failure-kind :unknown-registry
          :message (str "Unknown registry: " (pr-str registry-id))))

(defn- not-found-result
  [operation registry-id id]
  (result operation registry-id false :failed
          :failure-kind :not-found
          :change :miss
          :id id
          :message (str "Entry not found: " (pr-str id))))

(defn- extension-ids-for
  [root-state registry-id extension-id]
  (get-in root-state [root-state-key registry-id :ids-by-extension extension-id] #{}))

(defn- valid-entry?
  [entry]
  (and (map? entry)
       (contains? entry :id)
       (some? (:id entry))
       (contains? entry :extension-id)
       (some? (:extension-id entry))
       (contains? entry :value)))

(defn lookup
  "Lookup one canonical entry by id.

   Returns a success result with `:change :hit` or `:change :miss`. Unknown
   registries are treated as lookup misses and return nil value."
  [root-state registry-id id]
  (let [entry (get-in root-state [root-state-key registry-id :entries-by-id id])]
    {:root-state root-state
     :result (result :lookup registry-id true :ok
                     :change (if (some? entry) :hit :miss)
                     :id id
                     :value entry)}))

(defn list-entries
  "List the unordered entries for one declared registry.

   Unknown registries fail explicitly."
  [root-state registry-id]
  (if-not (declared-registry? root-state registry-id)
    {:root-state root-state
     :result (unknown-registry-result :list-entries registry-id)}
    (let [entries (vals (get-in root-state [root-state-key registry-id :entries-by-id] {}))]
      {:root-state root-state
       :result (result :list-entries registry-id true :ok
                       :entries entries
                       :count (count entries)
                       :value entries)})))

(defn register
  "Register one canonical shared entry into `registry-id`.

   Successful outcomes are `:insert` and `:replace`. Replacing an existing id
   owned by a different extension fails with `:ownership-conflict`."
  [root-state registry-id entry]
  (cond
    (not (declared-registry? root-state registry-id))
    {:root-state root-state
     :result (unknown-registry-result :register registry-id)}

    (not (valid-entry? entry))
    {:root-state root-state
     :result (invalid-entry-result registry-id entry
                                   "Entry must contain non-nil :id and :extension-id, and a :value field")}

    :else
    (let [id (:id entry)
          extension-id (:extension-id entry)
          previous-entry (get-in root-state [root-state-key registry-id :entries-by-id id])]
      (cond
        (and previous-entry
             (not= extension-id (:extension-id previous-entry)))
        {:root-state root-state
         :result (result :register registry-id false :failed
                         :failure-kind :ownership-conflict
                         :change :ownership-conflict
                         :id id
                         :extension-id extension-id
                         :entry entry
                         :previous-entry previous-entry
                         :message (str "Entry id already owned by another extension: " (pr-str id)))}

        :else
        (let [root-state' (-> root-state
                              (assoc-in [root-state-key registry-id :entries-by-id id] entry)
                              (update-in [root-state-key registry-id :ids-by-extension extension-id]
                                         (fnil conj #{}) id))
              change (if previous-entry :replace :insert)]
          {:root-state root-state'
           :result (cond-> (result :register registry-id true :ok
                                   :change change
                                   :id id
                                   :extension-id extension-id
                                   :entry entry
                                   :value entry)
                     previous-entry
                     (assoc :previous-entry previous-entry))})))))

(defn unregister
  "Remove one entry by canonical id.

   Misses return explicit failure info rather than throwing."
  [root-state registry-id id]
  (if-not (declared-registry? root-state registry-id)
    {:root-state root-state
     :result (unknown-registry-result :unregister registry-id)}
    (let [entry (get-in root-state [root-state-key registry-id :entries-by-id id])]
      (if-not entry
        {:root-state root-state
         :result (not-found-result :unregister registry-id id)}
        (let [extension-id (:extension-id entry)
              ids' (disj (extension-ids-for root-state registry-id extension-id) id)
              root-state' (cond-> (update-in root-state [root-state-key registry-id :entries-by-id] dissoc id)
                            (empty? ids')
                            (update-in [root-state-key registry-id :ids-by-extension] dissoc extension-id)
                            (not (empty? ids'))
                            (assoc-in [root-state-key registry-id :ids-by-extension extension-id] ids'))]
          {:root-state root-state'
           :result (result :unregister registry-id true :ok
                           :change :removed
                           :id id
                           :extension-id extension-id
                           :entry entry
                           :value entry)})))))

(defn clear-by-extension
  "Remove all entries in `registry-id` owned by `extension-id`.

   Returns removed ids/count on success. When nothing matches, returns an
   explicit miss-style failure result."
  [root-state registry-id extension-id]
  (if-not (declared-registry? root-state registry-id)
    {:root-state root-state
     :result (unknown-registry-result :clear-by-extension registry-id)}
    (let [ids (extension-ids-for root-state registry-id extension-id)]
      (if (empty? ids)
        {:root-state root-state
         :result (result :clear-by-extension registry-id false :failed
                         :failure-kind :not-found
                         :change :miss
                         :extension-id extension-id
                         :removed-count 0
                         :removed-ids []
                         :message (str "No entries owned by extension: " (pr-str extension-id)))}
        (let [removed-ids (vec ids)
              root-state' (-> root-state
                              (update-in [root-state-key registry-id :entries-by-id]
                                         (fn [entries]
                                           (apply dissoc entries ids)))
                              (update-in [root-state-key registry-id :ids-by-extension]
                                         dissoc extension-id))]
          {:root-state root-state'
           :result (result :clear-by-extension registry-id true :ok
                           :change :removed
                           :extension-id extension-id
                           :removed-count (count removed-ids)
                           :removed-ids removed-ids
                           :value removed-ids)})))))

(defn clear-registry
  "Remove all entries from one declared registry.

   Clearing an already empty known registry is a successful no-op."
  [root-state registry-id]
  (if-not (declared-registry? root-state registry-id)
    {:root-state root-state
     :result (unknown-registry-result :clear-registry registry-id)}
    (let [entries-by-id (get-in root-state [root-state-key registry-id :entries-by-id] {})
          removed-ids (vec (keys entries-by-id))
          root-state' (assoc-in root-state [root-state-key registry-id]
                                (declared-registry-state))]
      {:root-state root-state'
       :result (result :clear-registry registry-id true :ok
                       :change (if (seq removed-ids) :removed :noop)
                       :removed-count (count removed-ids)
                       :removed-ids removed-ids
                       :value removed-ids)})))
