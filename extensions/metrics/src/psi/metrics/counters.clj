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
