(ns psi.workflow-coordination.cancellation-entry
  "Runtime coordination for workflow cancellation and ordinary-work entry.

   A D31 cancellation transition and the first ordinary side-effecting entry for
   a workflow run must be mutually ordered. The lock handle is runtime state: it
   is not queryable domain state and exists only to linearize cancel CAS updates
   against prompt-submit / deterministic-operation entry boundaries. The lock is
   held only for the entry linearization point, not for full ordinary work."
  (:import
   (java.util.concurrent.locks ReentrantReadWriteLock)))

(def lock-handle-key :workflow-cancellation-entry-locks-handle)

(defn- new-lock []
  (ReentrantReadWriteLock. true))

(defn lock-for
  [ctx run-id]
  (when (and ctx run-id)
    (let [locks* (get ctx lock-handle-key)]
      (when locks*
        (get (swap! locks*
                    (fn [locks]
                      (if (contains? locks run-id)
                        locks
                        (assoc locks run-id (new-lock)))))
             run-id)))))

(defn with-run-read-lock
  [ctx run-id f]
  (if-let [lock (lock-for ctx run-id)]
    (let [read-lock (.readLock ^ReentrantReadWriteLock lock)]
      (.lock read-lock)
      (try
        (f)
        (finally
          (.unlock read-lock))))
    (f)))

(defn- run-locks
  [ctx run-ids]
  (->> run-ids
       (remove nil?)
       distinct
       sort
       (keep #(lock-for ctx %))
       vec))

(defn with-run-write-locks
  [ctx run-ids f]
  (let [locks (run-locks ctx run-ids)
        write-locks (mapv #(.writeLock ^ReentrantReadWriteLock %) locks)]
    (doseq [lock write-locks]
      (.lock lock))
    (try
      (f)
      (finally
        (doseq [lock (reverse write-locks)]
          (.unlock lock))))))

(defn drop-lock!
  "Drop the runtime cancellation-entry lock for run-id.

   This is lifecycle cleanup for removed/forgotten workflow runs. Callers must
   only invoke it after the canonical run record is no longer retained, so a
   future ordinary entry cannot rely on the removed run's lock for ordering."
  [ctx run-id]
  (let [locks* (when (and ctx run-id) (get ctx lock-handle-key))
        found? (boolean (and locks* (contains? @locks* run-id)))]
    (when locks*
      (swap! locks* dissoc run-id))
    {:run-id run-id
     :found? found?
     :dropped? (boolean locks*)}))
