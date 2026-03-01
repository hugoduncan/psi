(ns psi.memory.core
  "Memory component scaffold.

   Establishes an isolated memory context (Nullable pattern), global wrappers,
   and query resolver registration hooks.

   This namespace intentionally provides only Step 10 scaffold behavior.
   Lifecycle, remember/recover logic, and graph history tracking are added in
   follow-up tasks."
  (:require
   [psi.memory.graph-history :as graph-history]
   [psi.memory.ranking :as ranking]
   [psi.memory.resolvers :as resolvers]
   [psi.query.core :as query]))

(defrecord MemoryContext [state-atom config])

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
