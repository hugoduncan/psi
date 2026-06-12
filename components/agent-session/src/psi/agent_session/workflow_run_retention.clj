(ns psi.agent-session.workflow-run-retention
  "Workflow-run retention + linked workflow-owned session cleanup.

   Owns the narrow policy/mechanics for retaining only the newest completed
   workflow runs per originating session and tree-closing linked workflow-owned
   child sessions for removed runs.

   Retention applies only to TOP-LEVEL delegated runs. Nested runs created by a
   `:delegate` workflow step (tagged with `:delegating-run-id`) belong to their
   delegating parent run, not the originating session's retention budget, so a
   single user delegation of a multi-step workflow does not count its own
   internal sub-runs against the per-session retention count (which would
   otherwise delete the user's just-delegated run and its sessions). Nested
   runs and their sessions are removed transitively when their top-level run is
   removed."
  (:require
   [psi.agent-session.session-close :as session-close]
   [psi.session-state.state :as ss]
   [psi.workflow-coordination.cancellation-entry :as cancellation-entry]
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

(defn top-level-run?
  "A run is top-level when it was not created by a `:delegate` workflow step.
   Nested delegate-step sub-runs carry `:delegating-run-id` and belong to their
   delegating parent run rather than the originating session's retention budget."
  [run]
  (nil? (:delegating-run-id run)))

(defn nested-run-ids
  "Return the transitive set of nested run-ids delegated (directly or
   indirectly) by `root-run-id`, via `:delegating-run-id` parent links."
  [state root-run-id]
  (let [all-runs (workflow-runtime/list-workflow-runs state)
        children-by-parent (reduce (fn [acc run]
                                     (if-let [parent (:delegating-run-id run)]
                                       (update acc parent (fnil conj []) (:run-id run))
                                       acc))
                                   {}
                                   all-runs)]
    (loop [frontier [root-run-id]
           acc #{}]
      (if-let [run-id (first frontier)]
        (let [children (get children-by-parent run-id [])
              new-children (remove acc children)]
          (recur (into (vec (rest frontier)) new-children)
                 (into acc new-children)))
        acc))))

(defn runs-to-retain-and-remove
  [state parent-session-id retention-count]
  (let [run-order (vec (get-in state [:workflows :run-order]))
        run-index (zipmap run-order (range))
        terminal-runs (->> (workflow-runtime/list-workflow-runs state)
                           (filter #(= parent-session-id (:parent-session-id %)))
                           (filter top-level-run?)
                           (filter #(retained-terminal-status? (:status %))))
        ordered-runs (->> terminal-runs
                          (sort-by (fn [run]
                                     [(:finished-at run)
                                      (get run-index (:run-id run) -1)])
                                   #(compare %2 %1))
                          vec)]
    {:kept-runs (into [] (take retention-count) ordered-runs)
     :removed-runs (into [] (drop retention-count) ordered-runs)}))

(defn- close-linked-workflow-owned-sessions!
  [ctx run]
  (doseq [session-id (linked-session-ids run)]
    (when-let [session-data (ss/get-session-data-in ctx session-id)]
      (when (:workflow-owned? session-data)
        (session-close/close-session-tree-in! ctx session-id)))))

(defn apply-retention-cleanup!
  [ctx trigger-run-id]
  (let [state @(:state* ctx)
        workflow-run (workflow-runtime/workflow-run-in state trigger-run-id)]
    (when (and workflow-run
               (top-level-run? workflow-run)
               (retained-terminal-status? (:status workflow-run))
               (:parent-session-id workflow-run))
      (let [retention-count (completed-workflow-run-retention-count ctx)
            {:keys [removed-runs]}
            (runs-to-retain-and-remove state (:parent-session-id workflow-run) retention-count)]
        (doseq [removed-run removed-runs]
          (let [current-state @(:state* ctx)
                nested-ids (nested-run-ids current-state (:run-id removed-run))
                nested-runs (keep #(workflow-runtime/workflow-run-in current-state %) nested-ids)]
            ;; Close the removed top-level run's sessions and the sessions of
            ;; its nested delegate sub-runs, then remove all of those runs.
            (close-linked-workflow-owned-sessions! ctx removed-run)
            (doseq [nested-run nested-runs]
              (close-linked-workflow-owned-sessions! ctx nested-run))
            (doseq [run-id (cons (:run-id removed-run) nested-ids)]
              (swap! (:state* ctx)
                     (fn [root-state]
                       (if (workflow-runtime/workflow-run-in root-state run-id)
                         (first (workflow-runtime/remove-run root-state run-id))
                         root-state)))
              (cancellation-entry/drop-lock! ctx run-id))))))))
