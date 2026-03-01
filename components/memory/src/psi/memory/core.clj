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

(defn- resolve-content-type
  [remember-input]
  (or (:content-type remember-input)
      (:contentType remember-input)))

(defn- resolve-timestamp
  [remember-input]
  (or (:timestamp remember-input)
      (java.time.Instant/now)))

(defn- normalize-tags
  [tags]
  (->> (or tags [])
       (remove nil?)
       (distinct)
       (vec)))

(defn- enrich-provenance-with-graph
  [provenance capability-graph]
  (let [graph-fingerprint (or (:fingerprint capability-graph)
                              (:graph-fingerprint capability-graph)
                              (:graphFingerprint capability-graph))
        capability-ids    (or (:relevant-capability-ids capability-graph)
                              (:capability-ids capability-graph)
                              (:capabilityIds capability-graph)
                              (some->> (:capabilities capability-graph)
                                       (keep :id)
                                       vec))]
    (cond-> (or provenance {})
      graph-fingerprint (assoc :graphFingerprint graph-fingerprint)
      (seq capability-ids) (assoc :capabilityIds (vec capability-ids)))))

(defn- remember-validation-error
  [ctx remember-input]
  (let [content-type          (resolve-content-type remember-input)
        content               (:content remember-input)
        require-provenance?   (true? (get-in ctx [:config :require-provenance-on-write?]))
        has-provenance?       (some? (:provenance remember-input))]
    (cond
      (nil? content-type) :missing-content-type
      (nil? content) :missing-content
      (and require-provenance? (not has-provenance?)) :missing-provenance
      :else nil)))

(defn- update-index-stats
  [index-stats {:keys [content-type tags provenance]}]
  (let [source (or (:source provenance)
                   (:source-type provenance)
                   :unknown)]
    (-> index-stats
        (update :entry-count (fnil inc 0))
        (update-in [:by-type content-type] (fnil inc 0))
        (update-in [:by-source source] (fnil inc 0))
        ((fn [stats]
           (reduce (fn [acc tag]
                     (update-in acc [:by-tag tag] (fnil inc 0)))
                   stats
                   tags))))))

(defn remember-in!
  "Remember a record in isolated `ctx`.

   Required inputs:
   - :content-type (or :contentType)
   - :content
   - :tags
   - :provenance (required when :require-provenance-on-write? is true)

   Optional inputs:
   - :timestamp (defaults to now)
   - :capability-graph to enrich provenance with graph fingerprint and capability ids"
  [ctx {:keys [content tags provenance capability-graph] :as remember-input}]
  (if-let [error (remember-validation-error ctx remember-input)]
    {:ok? false
     :error error}
    (let [content-type       (resolve-content-type remember-input)
          normalized-tags    (normalize-tags tags)
          record-timestamp   (resolve-timestamp remember-input)
          full-provenance    (enrich-provenance-with-graph provenance capability-graph)
          memory-record      {:record-id (str (random-uuid))
                              :content-type content-type
                              :content content
                              :tags normalized-tags
                              :timestamp record-timestamp
                              :provenance full-provenance}]
      (swap-state-in! ctx
                      (fn [state]
                        (-> state
                            (update :records (fnil conj []) memory-record)
                            (update :index-stats update-index-stats memory-record))))
      {:ok? true
       :record memory-record
       :entry-count (get-in (get-state-in ctx) [:index-stats :entry-count])})))

(defn remember!
  "Global wrapper for `remember-in!`."
  [remember-input]
  (remember-in! (global-context) remember-input))

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
