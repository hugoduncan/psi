(ns psi.workflow-runtime.statechart-runtime.state
  (:require
   [clojure.string :as str]
   [psi.workflow-runtime.core :as workflow-runtime]
   [psi.workflow-runtime.statechart :as workflow-statechart]))

(defn runtime-step-order
  [workflow-run]
  (workflow-statechart/effective-step-order (:effective-definition workflow-run)))

(defn runtime-step-def
  [workflow-run step-id]
  (get (workflow-statechart/effective-steps (:effective-definition workflow-run)) step-id))

(defn now []
  (java.time.Instant/now))

(defn- initial-step-outputs
  [workflow-run]
  (into {}
        (keep (fn [[step-id step-run]]
                (when-let [accepted (:accepted-result step-run)]
                  [step-id accepted])))
        (:step-runs workflow-run)))

(defn- initial-iteration-counts
  [workflow-run]
  (into {}
        (map (fn [[step-id step-run]]
               [step-id (or (:iteration-count step-run) 0)]))
        (:step-runs workflow-run)))

(defn- initial-attempt-counts
  [workflow-run]
  (into {}
        (map (fn [[step-id step-run]]
               [step-id (count (:attempts step-run))]))
        (:step-runs workflow-run)))

(defn- initial-attempt-ids
  [workflow-run]
  (into {}
        (keep (fn [[step-id step-run]]
                (when-let [attempt-id (:attempt-id (last (:attempts step-run)))]
                  [step-id attempt-id])))
        (:step-runs workflow-run)))

(defn- initial-sessions
  [workflow-run]
  (into {}
        (keep (fn [[step-id step-run]]
                (when-let [execution-session-id (:execution-session-id (last (:attempts step-run)))]
                  [step-id execution-session-id])))
        (:step-runs workflow-run)))

(defn workflow-stop-signal
  "Return the cooperative workflow stop signal for run-id, if any.

   Cancellation is authoritative in canonical workflow state. A missing run record
   is also a stop signal for workers that were woken after remove-run dropped the
   canonical record. Runtime handles/futures are intentionally not consulted here."
  [ctx run-id]
  (let [workflow-run (workflow-runtime/workflow-run-in @(:state* ctx) run-id)]
    (cond
      (nil? workflow-run) :removed
      (= :cancelled (:status workflow-run)) :cancelled
      :else nil)))

(defn workflow-stopped?
  [ctx run-id]
  (boolean (workflow-stop-signal ctx run-id)))

(defn create-working-memory
  [ctx parent-session-id run-id]
  (let [workflow-run (workflow-runtime/workflow-run-in @(:state* ctx) run-id)
        steps (workflow-statechart/effective-steps (:effective-definition workflow-run))]
    {:workflow-run-id run-id
     :parent-session-id parent-session-id
     :workflow-input (:workflow-input workflow-run)
     :step-outputs (initial-step-outputs workflow-run)
     :iteration-counts (initial-iteration-counts workflow-run)
     :judge-results {}
     :sessions (initial-sessions workflow-run)
     :attempt-ids (initial-attempt-ids workflow-run)
     :attempt-counts (initial-attempt-counts workflow-run)
     :actor-retries {}
     :actor-retry-limits (into {}
                               (map (fn [[step-id step-def]]
                                      [step-id (or (get-in step-def [:retry-policy :max-attempts]) 1)]))
                               steps)
     :judge-retries {}
     :blocked-step-id nil
     :pending-actor-result nil
     :pending-judge-result nil
     :pending-routing nil
     :current-step-id (:current-step-id workflow-run)
     :created-at (now)
     :updated-at (now)}))

(defn step-id-from-configuration
  [configuration]
  (some (fn [state-id]
          (when (keyword? state-id)
            (let [s (str state-id)]
              (when (str/starts-with? s ":step/")
                (let [suffix (subs s 6)]
                  (if-let [idx (str/index-of suffix ".")]
                    (subs suffix 0 idx)
                    suffix))))))
        configuration))

(defn run-status-from-configuration
  [configuration]
  (cond
    (contains? configuration :pending) :pending
    (contains? configuration :completed) :completed
    (contains? configuration :failed) :failed
    (contains? configuration :cancelled) :cancelled
    (some #(str/ends-with? (name %) ".blocked") configuration) :blocked
    :else :running))

(defn sync-run-projection!
  [ctx run-id working-memory* configuration]
  (let [status (run-status-from-configuration configuration)
        step-id (or (step-id-from-configuration configuration)
                    (:current-step-id @working-memory*))]
    (swap! (:state* ctx)
           (fn [state-map]
             (if-let [workflow-run (workflow-runtime/workflow-run-in state-map run-id)]
               (assoc-in state-map
                         (workflow-runtime/run-path run-id)
                         (if (and (= :cancelled (:status workflow-run))
                                  (not= :cancelled status))
                           workflow-run
                           (cond-> (assoc workflow-run
                                          :status status
                                          :current-step-id (case status
                                                             :completed nil
                                                             step-id)
                                          :updated-at (now))
                             (= status :blocked)
                             (assoc :blocked {:step-id (:blocked-step-id @working-memory*)})

                             (not= status :blocked)
                             (assoc :blocked nil)

                             (contains? #{:completed :failed :cancelled} status)
                             (assoc :finished-at (or (:finished-at workflow-run) (now))))))
               state-map)))))

(def max-drain-events 1000)

(defn terminal-configuration?
  [configuration]
  (boolean (some #{:completed :failed :cancelled} configuration)))
