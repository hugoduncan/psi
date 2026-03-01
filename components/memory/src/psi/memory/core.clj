(ns psi.memory.core
  "Memory component scaffold.

   Establishes an isolated memory context (Nullable pattern), global wrappers,
   and query resolver registration hooks.

   This namespace intentionally provides only Step 10 scaffold behavior.
   Lifecycle, remember/recover logic, and graph history tracking are added in
   follow-up tasks."
  (:require
   [psi.engine.core :as engine]
   [psi.history.git :as git]
   [psi.memory.graph-history :as graph-history]
   [psi.memory.ranking :as ranking]
   [psi.memory.resolvers :as resolvers]
   [psi.query.core :as query]))

(defrecord MemoryContext [state-atom config])

(defn- graph-status-ready?
  "Step 10 gate: capability graph status is acceptable when stable or expanding."
  [graph-status]
  (contains? #{:stable :expanding} graph-status))

(defn- initial-index-stats
  []
  {:entry-count 0
   :by-type {}
   :by-tag {}
   :by-source {}})

(defn initial-state
  "Return initial in-memory scaffold state used by Step 10 memory context."
  []
  {:status :initializing
   :sessions []
   :records []
   :recoveries []
   :graph-snapshots []
   :graph-deltas []
   :search-results []
   :capability-history []
   :index-stats (initial-index-stats)
   :retention {:snapshots graph-history/snapshot-retention-limit
               :deltas graph-history/delta-retention-limit}
   :ranking-defaults ranking/default-weights})

(defn create-context
  "Create an isolated MemoryContext.

   Options:
   - :state-overrides                map merged over initial memory state
   - :require-provenance-on-write?   feature flag for follow-up tasks
                                     (default true)"
  ([]
   (create-context {}))
  ([{:keys [state-overrides require-provenance-on-write?]
     :or   {state-overrides {}
            require-provenance-on-write? true}}]
   (->MemoryContext
    (atom (merge (initial-state) state-overrides))
    {:require-provenance-on-write? require-provenance-on-write?})))

(defonce ^:private global-ctx (atom nil))

(defn- ensure-global-ctx!
  []
  (or @global-ctx
      (let [ctx (create-context)]
        (reset! global-ctx ctx)
        ctx)))

(defn global-context
  "Return the global memory context singleton, creating it when absent."
  []
  (ensure-global-ctx!))

(defn get-state-in
  "Return the full memory state map from `ctx`."
  [ctx]
  @(:state-atom ctx))

(defn swap-state-in!
  "Apply `f` to memory state atom in `ctx`."
  [ctx f & args]
  (apply swap! (:state-atom ctx) f args))

(defn get-state
  "Global wrapper for `get-state-in`."
  []
  (get-state-in (global-context)))

(defn swap-state!
  "Global wrapper for `swap-state-in!`."
  [f & args]
  (apply swap-state-in! (global-context) f args))

(defn activation-gates-in
  "Compute Step 10 activation gates.

   Required gates:
   - query env built
   - git repository has history
   - capability graph status in #{:stable :expanding}"
  [ctx {:keys [query-ctx git-ctx capability-graph-status]
        :or   {git-ctx (git/create-context)}}]
  (let [query-summary   (query/graph-summary-in query-ctx)
        commits         (try
                          (git/log git-ctx {:n 1})
                          (catch Exception _
                            []))
        has-git-history (pos? (count commits))
        env-built?      (true? (:env-built? query-summary))
        graph-ready?    (graph-status-ready? capability-graph-status)]
    {:query-env-built? env-built?
     :has-git-history? has-git-history
     :graph-status capability-graph-status
     :graph-status-ready? graph-ready?
     :ready? (and env-built? has-git-history graph-ready?)}))

(defn activate-in!
  "Run Step 10 activation lifecycle for isolated `ctx`.

   On success:
   - memory status => :ready
   - engine readiness flags => history/knowledge/memory true

   On failure:
   - memory status => :error
   - memory-ready false
   - do not force unrelated readiness flags true"
  [ctx {:keys [engine-ctx query-ctx git-ctx capability-graph-status]
        :or   {capability-graph-status :stable}
        :as   opts}]
  (let [gates (activation-gates-in ctx {:query-ctx query-ctx
                                        :git-ctx git-ctx
                                        :capability-graph-status capability-graph-status})
        ready? (:ready? gates)]
    (swap-state-in! ctx assoc :status (if ready? :ready :error))
    (when engine-ctx
      (if ready?
        (do
          (engine/update-system-component-in! engine-ctx :history-ready true)
          (engine/update-system-component-in! engine-ctx :knowledge-ready true)
          (engine/update-system-component-in! engine-ctx :memory-ready true))
        (engine/update-system-component-in! engine-ctx :memory-ready false)))
    (assoc gates
           :memory-status (:status (get-state-in ctx))
           :options (select-keys opts [:capability-graph-status]))))

(defn activate!
  "Global wrapper for `activate-in!`.

   Requires :query-ctx. Optionally accepts :engine-ctx, :git-ctx,
   and :capability-graph-status."
  [{:keys [query-ctx] :as opts}]
  (activate-in! (global-context) (assoc opts :query-ctx query-ctx)))

(defn register-resolvers-in!
  "Register memory resolvers into isolated query context `qctx`.
   Rebuilds query env by default."
  ([qctx]
   (register-resolvers-in! qctx true))
  ([qctx rebuild?]
   (doseq [r resolvers/all-resolvers]
     (query/register-resolver-in! qctx r))
   (when rebuild?
     (query/rebuild-env-in! qctx))
   :ok))

(defn register-resolvers!
  "Register memory resolvers into global query context and rebuild env once."
  []
  (doseq [r resolvers/all-resolvers]
    (query/register-resolver! r))
  (query/rebuild-env!)
  :ok)
