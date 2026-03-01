(ns psi.memory.core
  "Memory component scaffold.

   Establishes an isolated memory context (Nullable pattern), global wrappers,
   and query resolver registration hooks.

   This namespace intentionally provides only Step 10 scaffold behavior.
   Lifecycle, remember/recover logic, and graph history tracking are added in
   follow-up tasks."
  (:require
   [clojure.set :as set]
   [clojure.string :as str]
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

(defn- normalize-sources
  [sources]
  (let [normalized (->> (or sources [:session :history :graph])
                        (map keyword)
                        vec)]
    (if (seq normalized)
      normalized
      [:session :history :graph])))

(defn- source->record
  [record]
  (or (:source (:provenance record))
      (:source-type (:provenance record))
      :session))

(defn- normalize-query-text
  [query-text]
  (some-> query-text str str/lower-case str/trim))

(defn- text-relevance-score
  [query-text record]
  (let [q (normalize-query-text query-text)]
    (if (str/blank? q)
      0.0
      (let [haystack (str/lower-case (str (:content record) " " (or (:content-type record) "") " " (str/join " " (:tags record))))]
        (if (str/includes? haystack q) 1.0 0.0)))))

(defn- recency-score
  [now timestamp]
  (if (instance? java.time.Instant timestamp)
    (let [hours (Math/abs (double (.toHours (java.time.Duration/between timestamp now))))]
      (/ 1.0 (+ 1.0 hours)))
    0.0))

(defn- capability-proximity
  [requested-capability-ids record]
  (let [requested (set (map keyword (or requested-capability-ids [])))
        record-caps (set (map keyword (or (get-in record [:provenance :capabilityIds]) [])))]
    (if (or (empty? requested) (empty? record-caps))
      0.0
      (/ (double (count (set/intersection requested record-caps)))
         (double (count requested))))))

(defn- score-record
  [{:keys [query-text now capability-ids ranking-weights]} record]
  (let [{text-weight :text-relevance
         recency-weight :recency
         capability-weight :capability-proximity} ranking-weights
        tr (text-relevance-score query-text record)
        rs (recency-score now (:timestamp record))
        cp (capability-proximity capability-ids record)]
    (+ (* (/ text-weight 100.0) tr)
       (* (/ recency-weight 100.0) rs)
       (* (/ capability-weight 100.0) cp))))

(defn- dedupe-records
  [records]
  (vals
   (reduce (fn [acc record]
             (let [k (or (:record-id record)
                         [(:content-type record)
                          (:content record)
                          (:timestamp record)])]
               (if (contains? acc k)
                 acc
                 (assoc acc k record))))
           {}
           records)))

(defn- filter-record?
  [{:keys [tags capability-ids content-types since query-text]} record]
  (let [record-tags (set (:tags record))
        requested-tags (set (or tags []))
        record-caps (set (or (get-in record [:provenance :capabilityIds]) []))
        requested-caps (set (or capability-ids []))
        requested-types (set (or content-types []))
        timestamp (:timestamp record)
        text-q (normalize-query-text query-text)]
    (and
     (or (empty? requested-tags)
         (not-empty (set/intersection requested-tags record-tags)))
     (or (empty? requested-caps)
         (not-empty (set/intersection requested-caps record-caps)))
     (or (empty? requested-types)
         (contains? requested-types (:content-type record)))
     (or (nil? since)
         (and (instance? java.time.Instant timestamp)
              (not (neg? (compare timestamp since)))))
     (or (str/blank? text-q)
         (pos? (text-relevance-score text-q record))))))

(defn recover-in!
  "Recover memory records using required Step 10 source composition, filters, ranking and limit.

   Defaults:
   - required sources composed: :session, :history, :graph
   - limit: 50
   - ranking weights: 50/25/25 (must sum to 100)

   Returns map with :ok?, :results, :result-count, :resultsTruncated, :sources and :weights."
  [ctx {:keys [sources limit tags capability-ids content-types since query-text ranking-weights now]
        :or   {limit 50
               now (java.time.Instant/now)}}]
  (let [weights (or ranking-weights ranking/default-weights)]
    (if-not (ranking/weights-valid? weights)
      {:ok? false
       :error :invalid-ranking-weights
       :weights weights}
      (let [state             (get-state-in ctx)
            requested-sources (normalize-sources sources)]
        (if-not (= #{:session :history :graph} (set requested-sources))
          {:ok? false
           :error :required-sources-missing
           :sources requested-sources}
          (let [records           (:records state)
                session-records   (filter #(= :session (source->record %)) records)
                history-records   (filter #(contains? #{:history :git} (source->record %)) records)
                graph-records     (mapv (fn [snapshot]
                                          {:record-id (or (:snapshot-id snapshot)
                                                          (str (random-uuid)))
                                           :content-type :graph-snapshot
                                           :content (str "capability graph snapshot " (:fingerprint snapshot))
                                           :tags [:graph :snapshot]
                                           :timestamp (:timestamp snapshot)
                                           :provenance {:source :graph
                                                        :graphFingerprint (:fingerprint snapshot)
                                                        :capabilityIds (vec (or (:capability-ids snapshot) []))}
                                           :graph-snapshot snapshot})
                                        (:graph-snapshots state))
                records-by-source {:session session-records
                                   :history history-records
                                   :graph graph-records}
                merged            (->> requested-sources
                                       (mapcat #(get records-by-source % []))
                                       dedupe-records)
                filtered          (filter (partial filter-record? {:tags tags
                                                                   :capability-ids capability-ids
                                                                   :content-types content-types
                                                                   :since since
                                                                   :query-text query-text})
                                          merged)
                scored            (->> filtered
                                       (map (fn [record]
                                              (assoc record :recovery/score
                                                     (score-record {:query-text query-text
                                                                    :now now
                                                                    :capability-ids capability-ids
                                                                    :ranking-weights weights}
                                                                   record))))
                                       (sort-by :recovery/score >)
                                       vec)
                enforced-limit    (max 0 (or limit 50))
                total-count       (count scored)
                results           (vec (take enforced-limit scored))
                truncated?        (> total-count enforced-limit)
                recovery          {:recovery-id (str (random-uuid))
                                   :timestamp now
                                   :sources requested-sources
                                   :filters {:tags (vec (or tags []))
                                             :capability-ids (vec (or capability-ids []))
                                             :content-types (vec (or content-types []))
                                             :since since
                                             :query-text query-text}
                                   :weights weights
                                   :limit enforced-limit
                                   :result-count (count results)
                                   :resultsTruncated truncated?
                                   :results results}]
            (swap-state-in! ctx
                            (fn [s]
                              (-> s
                                  (assoc :search-results results)
                                  (update :recoveries (fnil conj []) recovery))))
            {:ok? true
             :sources requested-sources
             :weights weights
             :result-count (count results)
             :resultsTruncated truncated?
             :results results
             :recovery recovery}))))))

(defn recover!
  "Global wrapper for `recover-in!`."
  [recover-input]
  (recover-in! (global-context) recover-input))

(defn capture-graph-change-in!
  "Capture graph snapshot/delta into isolated `ctx` when fingerprint changes.

   Behavior:
   - no-op when fingerprint is missing
   - no-op when fingerprint matches latest snapshot
   - append snapshot on change
   - append delta when prior snapshot exists
   - enforce fixed-window retention (200 snapshots, 1000 deltas)
   - does not produce summary entities"
  ([ctx capability-graph]
   (capture-graph-change-in! ctx capability-graph (java.time.Instant/now)))
  ([ctx capability-graph timestamp]
   (if-let [snapshot (graph-history/make-snapshot capability-graph timestamp)]
     (let [state           (get-state-in ctx)
           latest-snapshot (last (:graph-snapshots state))
           duplicate?      (= (:fingerprint latest-snapshot)
                              (:fingerprint snapshot))]
       (if duplicate?
         {:ok? true
          :changed? false
          :reason :unchanged-fingerprint
          :snapshot-added? false
          :delta-added? false
          :snapshot-count (count (:graph-snapshots state))
          :delta-count (count (:graph-deltas state))}
         (let [delta (when latest-snapshot
                       (graph-history/make-delta latest-snapshot snapshot timestamp))]
           (swap-state-in! ctx
                           (fn [s]
                             (let [with-snapshot (update s :graph-snapshots
                                                         (fn [entries]
                                                           (graph-history/trim-window
                                                            (conj (vec (or entries [])) snapshot)
                                                            graph-history/snapshot-retention-limit)))]
                               (if delta
                                 (update with-snapshot :graph-deltas
                                         (fn [entries]
                                           (graph-history/trim-window
                                            (conj (vec (or entries [])) delta)
                                            graph-history/delta-retention-limit)))
                                 with-snapshot))))
           (let [updated (get-state-in ctx)]
             {:ok? true
              :changed? true
              :snapshot-added? true
              :delta-added? (some? delta)
              :snapshot snapshot
              :delta delta
              :snapshot-count (count (:graph-snapshots updated))
              :delta-count (count (:graph-deltas updated))}))))
     {:ok? false
      :changed? false
      :error :missing-fingerprint})))

(defn capture-graph-change!
  "Global wrapper for `capture-graph-change-in!`."
  ([capability-graph]
   (capture-graph-change-in! (global-context) capability-graph))
  ([capability-graph timestamp]
   (capture-graph-change-in! (global-context) capability-graph timestamp)))

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
