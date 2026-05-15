(ns psi.metrics.counters
  "Pure counter update functions for the metrics extension.
   All functions take and return plain maps — no atoms, no I/O.")

;;; Helpers

(defn- now-str
  "Return the current instant as an ISO-8601 string."
  []
  (str (java.time.Instant/now)))

(defn empty-metrics
  "Return a fresh, schema-conforming empty metrics map."
  []
  {:tools      {}
   :workflows  {}
   :commands   {}
   :operations {}
   :tokens     {}
   :providers  {}
   :updated-at nil})

;;; Tool counters

(defn inc-tool-invocation
  "Increment the invocation counter for tool-name."
  [metrics tool-name]
  (-> metrics
      (update-in [:tools tool-name :invocations] (fnil inc 0))
      (update-in [:tools tool-name :errors] (fnil identity 0))
      (update-in [:tools tool-name :error-reasons] (fnil identity {}))
      (assoc :updated-at (now-str))))

(defn inc-tool-error
  "Increment the error counter for tool-name and record the error reason."
  [metrics tool-name reason]
  (-> metrics
      (update-in [:tools tool-name :errors] (fnil inc 0))
      (update-in [:tools tool-name :error-reasons reason] (fnil inc 0))
      (assoc :updated-at (now-str))))

;;; Workflow / command / operation counters

(defn inc-workflow-invocation
  "Increment the invocation counter for workflow-id."
  [metrics workflow-id]
  (-> metrics
      (update-in [:workflows workflow-id :invocations] (fnil inc 0))
      (assoc :updated-at (now-str))))

(defn inc-command-invocation
  "Increment the invocation counter for command-name."
  [metrics command-name]
  (-> metrics
      (update-in [:commands command-name :invocations] (fnil inc 0))
      (assoc :updated-at (now-str))))

(defn inc-operation-invocation
  "Increment the invocation counter for operation-id."
  [metrics operation-id]
  (-> metrics
      (update-in [:operations operation-id :invocations] (fnil inc 0))
      (assoc :updated-at (now-str))))

;;; Token counters

(defn add-token-delta
  "Add a per-model token delta to the metrics token counters.
   delta is a map with :input :output :cache-read :cache-write keys (all ints).
   model-id is a string; uses \"unknown\" when nil."
  [metrics model-id delta]
  (let [mid (or model-id "unknown")]
    (-> metrics
        (update-in [:tokens mid :input]       (fnil + 0) (or (:input delta) 0))
        (update-in [:tokens mid :output]      (fnil + 0) (or (:output delta) 0))
        (update-in [:tokens mid :cache-read]  (fnil + 0) (or (:cache-read delta) 0))
        (update-in [:tokens mid :cache-write] (fnil + 0) (or (:cache-write delta) 0))
        (assoc :updated-at (now-str)))))

(defn compute-token-delta
  "Compute the incremental token delta for a session turn.

   current-totals is the map of usage attrs returned by EQL for this turn:
     {:psi.agent-session/usage-input N
      :psi.agent-session/usage-output N
      :psi.agent-session/usage-cache-read N
      :psi.agent-session/usage-cache-write N}

   prev-totals is the last-seen totals map for this session (nil on first turn).

   Returns a delta map {:input N :output N :cache-read N :cache-write N}."
  [current-totals prev-totals]
  (let [cur-in    (or (:psi.agent-session/usage-input current-totals) 0)
        cur-out   (or (:psi.agent-session/usage-output current-totals) 0)
        cur-cr    (or (:psi.agent-session/usage-cache-read current-totals) 0)
        cur-cw    (or (:psi.agent-session/usage-cache-write current-totals) 0)
        prev-in   (or (:psi.agent-session/usage-input prev-totals) 0)
        prev-out  (or (:psi.agent-session/usage-output prev-totals) 0)
        prev-cr   (or (:psi.agent-session/usage-cache-read prev-totals) 0)
        prev-cw   (or (:psi.agent-session/usage-cache-write prev-totals) 0)]
    {:input       (max 0 (- cur-in prev-in))
     :output      (max 0 (- cur-out prev-out))
     :cache-read  (max 0 (- cur-cr prev-cr))
     :cache-write (max 0 (- cur-cw prev-cw))}))

(defn- provider-key
  [provider]
  (or provider "unknown"))

(defn- model-key
  [model-id]
  (or model-id "unknown"))

(defn- ensure-provider-branch
  [metrics provider model-id]
  (let [provider* (provider-key provider)
        model*    (model-key model-id)]
    (-> metrics
        (update-in [:providers provider* :requests] (fnil identity 0))
        (update-in [:providers provider* :successes] (fnil identity 0))
        (update-in [:providers provider* :failures] (fnil identity 0))
        (update-in [:providers provider* :final-failures] (fnil identity 0))
        (update-in [:providers provider* :retries] (fnil identity 0))
        (update-in [:providers provider* :retry-backoff-ms] (fnil identity 0))
        (update-in [:providers provider* :error-types] (fnil identity {}))
        (update-in [:providers provider* :models] (fnil identity {}))
        (update-in [:providers provider* :models model* :requests] (fnil identity 0))
        (update-in [:providers provider* :models model* :successes] (fnil identity 0))
        (update-in [:providers provider* :models model* :failures] (fnil identity 0))
        (update-in [:providers provider* :models model* :final-failures] (fnil identity 0))
        (update-in [:providers provider* :models model* :retries] (fnil identity 0))
        (update-in [:providers provider* :models model* :retry-backoff-ms] (fnil identity 0))
        (update-in [:providers provider* :models model* :error-types] (fnil identity {})))))

(defn inc-provider-request
  [metrics provider model-id]
  (let [provider* (provider-key provider)
        model*    (model-key model-id)]
    (-> (ensure-provider-branch metrics provider* model*)
        (update-in [:providers provider* :requests] (fnil inc 0))
        (update-in [:providers provider* :models model* :requests] (fnil inc 0))
        (assoc :updated-at (now-str)))))

(defn inc-provider-retry
  [metrics provider model-id delay-ms]
  (let [provider* (provider-key provider)
        model*    (model-key model-id)
        delay     (or delay-ms 0)]
    (-> (ensure-provider-branch metrics provider* model*)
        (update-in [:providers provider* :retries] (fnil inc 0))
        (update-in [:providers provider* :retry-backoff-ms] (fnil + 0) delay)
        (update-in [:providers provider* :models model* :retries] (fnil inc 0))
        (update-in [:providers provider* :models model* :retry-backoff-ms] (fnil + 0) delay)
        (assoc :updated-at (now-str)))))

(defn record-provider-finish
  [metrics provider model-id {:keys [status final? error-kind]}]
  (let [provider*  (provider-key provider)
        model*     (model-key model-id)
        error-key  (some-> error-kind name)
        success?   (= :succeeded status)
        failure?   (= :failed status)]
    (cond-> (ensure-provider-branch metrics provider* model*)
      success?
      (-> (update-in [:providers provider* :successes] (fnil inc 0))
          (update-in [:providers provider* :models model* :successes] (fnil inc 0)))

      failure?
      (-> (update-in [:providers provider* :failures] (fnil inc 0))
          (update-in [:providers provider* :models model* :failures] (fnil inc 0)))

      (and failure? final?)
      (-> (update-in [:providers provider* :final-failures] (fnil inc 0))
          (update-in [:providers provider* :models model* :final-failures] (fnil inc 0)))

      (and failure? error-key)
      (-> (update-in [:providers provider* :error-types error-key] (fnil inc 0))
          (update-in [:providers provider* :models model* :error-types error-key] (fnil inc 0)))

      :always
      (assoc :updated-at (now-str)))))
