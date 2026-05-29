(ns psi.agent-session.workflow-run-retention
  "Workflow-run retention + linked workflow-owned session cleanup.

   Owns the narrow policy/mechanics for retaining only the newest completed
   workflow runs per originating session and tree-closing linked workflow-owned
   child sessions for removed runs."
  (:require
   [psi.agent-session.session-close :as session-close]
   [psi.session-state.state :as ss]
   [psi.workflow-runtime.core :as workflow-runtime]))

(def ^:private retained-terminal-statuses
  #{:completed :failed :cancelled})

(defn retained-terminal-status?
  [status]
  (contains? retained-terminal-statuses status))

(defn completed-workflow-run-retention-count
  [ctx]
  (let [value (get-in ctx [:config :completed-workflow-run-retention-count] 1)]
    (when (neg? value)
      (throw (ex-info "Invalid completed workflow run retention count"
                      {:completed-workflow-run-retention-count value})))
    value))

(defn linked-session-ids
  [workflow-run]
  (->> (:step-runs workflow-run)
       vals
       (mapcat :attempts)
       (mapcat (juxt :execution-session-id :judge-session-id))
       (remove nil?)
       distinct
       vec))

(defn runs-to-retain-and-remove
  [state parent-session-id retention-count]
  (let [run-order (vec (get-in state [:workflows :run-order]))
        run-index (zipmap run-order (range))
        terminal-runs (->> (workflow-runtime/list-workflow-runs state)
                           (filter #(= parent-session-id (:parent-session-id %)))
                           (filter #(retained-terminal-status? (:status %))))
        ordered-runs (->> terminal-runs
                          (sort-by (fn [run]
                                     [(:finished-at run)
                                      (get run-index (:run-id run) -1)])
                                   #(compare %2 %1))
                          vec)]
    {:kept-runs (into [] (take retention-count) ordered-runs)
     :removed-runs (into [] (drop retention-count) ordered-runs)}))

(defn apply-retention-cleanup!
  [ctx trigger-run-id]
  (let [state @(:state* ctx)
        workflow-run (workflow-runtime/workflow-run-in state trigger-run-id)]
    (when (and workflow-run
               (retained-terminal-status? (:status workflow-run))
               (:parent-session-id workflow-run))
      (let [retention-count (completed-workflow-run-retention-count ctx)
            {:keys [removed-runs]}
            (runs-to-retain-and-remove state (:parent-session-id workflow-run) retention-count)]
        (doseq [removed-run removed-runs]
          (doseq [session-id (linked-session-ids removed-run)]
            (when-let [session-data (ss/get-session-data-in ctx session-id)]
              (when (:workflow-owned? session-data)
                (session-close/close-session-tree-in! ctx session-id))))
          (swap! (:state* ctx)
                 (fn [root-state]
                   (first (workflow-runtime/remove-run root-state (:run-id removed-run))))))))))