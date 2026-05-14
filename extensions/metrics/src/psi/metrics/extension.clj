(ns psi.metrics.extension
  "psi/metrics extension entry point.

   Subscribes to tool_call, tool_result, and session_turn_finished events
   and maintains persistent per-capability usage counters.

   Registers:
   - deterministic operation  metrics/summary
   - slash command            /metrics"
  (:require
   [clojure.string :as str]
   [psi.metrics.counters :as counters]
   [psi.metrics.persistence :as persist]
   [psi.metrics.schema :as schema]))

;;; State

(defonce store
  (atom nil))

(defonce writing?
  (atom false))

;;; Internal accessors

(defn- get-metrics
  "Return the current metrics map from the store."
  []
  (:metrics @store))

(defn- update-metrics!
  "Apply f to the current metrics map inside the store and mark dirty."
  [f & args]
  (swap! store update :metrics #(apply f % args))
  (persist/mark-dirty-and-persist! store writing?))

;;; Event handlers

(defn- on-tool-call
  "Increment the invocation counter for the named tool."
  [payload]
  (when-let [tool-name (:tool-name payload)]
    (update-metrics! counters/inc-tool-invocation tool-name))
  nil)

(defn- on-tool-result
  "Increment error counters when the tool result is an error."
  [payload]
  (when (and (:tool-name payload) (:is-error payload))
    (let [tool-name (:tool-name payload)
          content   (str (:content payload))
          reason    (-> content
                        (str/split-lines)
                        first
                        (or "")
                        (str/trim)
                        (subs 0 (min 80 (count (str/trim (first (str/split-lines content)))))))]
      (update-metrics! counters/inc-tool-error tool-name reason)))
  nil)

(defn- make-turn-finished-handler
  "Return a session_turn_finished handler closed over api for EQL queries."
  [api]
  (fn [payload]
    (when-let [session-id (:session-id payload)]
      (try
        (let [result    ((:query-session api)
                         session-id
                         [:psi.agent-session/usage-input
                          :psi.agent-session/usage-output
                          :psi.agent-session/usage-cache-read
                          :psi.agent-session/usage-cache-write
                          :psi.agent-session/model-id])
              model-id  (:psi.agent-session/model-id result)
              cur-usage (select-keys result [:psi.agent-session/usage-input
                                             :psi.agent-session/usage-output
                                             :psi.agent-session/usage-cache-read
                                             :psi.agent-session/usage-cache-write])
              prev-usage (get-in @store [:session-usage-cache session-id])
              delta      (counters/compute-token-delta cur-usage prev-usage)]
          ;; Update the per-session usage cache (transient, not persisted).
          (swap! store assoc-in [:session-usage-cache session-id] cur-usage)
          (update-metrics! counters/add-token-delta model-id delta))
        (catch Exception e
          (println (str "DEBUG [psi/metrics] skipping token tracking for session "
                        session-id ": " (ex-message e))))))
    nil))

;;; Formatting

(defn- format-number
  "Format an integer with thousands separators."
  [n]
  (format "%,d" (long n)))

(defn- format-tools-section
  [tools]
  (when (seq tools)
    (let [rows (sort-by key tools)]
      (str "### Tools (" (count rows) " tracked)\n"
           "| Tool | Invocations | Errors |\n"
           "|------|-------------|--------|\n"
           (str/join "\n"
                     (map (fn [[name {:keys [invocations errors]}]]
                            (str "| " name " | " (format-number invocations)
                                 " | " (format-number (or errors 0)) " |"))
                          rows))
           "\n"))))

(defn- format-tokens-section
  [tokens]
  (when (seq tokens)
    (let [rows (sort-by key tokens)]
      (str "### Token Usage (by model)\n"
           "| Model | Input | Output | Cache Read | Cache Write |\n"
           "|-------|-------|--------|------------|-------------|\n"
           (str/join "\n"
                     (map (fn [[model {:keys [input output cache-read cache-write]}]]
                            (str "| " model
                                 " | " (format-number (or input 0))
                                 " | " (format-number (or output 0))
                                 " | " (format-number (or cache-read 0))
                                 " | " (format-number (or cache-write 0)) " |"))
                          rows))
           "\n"))))

(defn- format-simple-section
  [title items]
  (when (seq items)
    (let [rows (sort-by key items)]
      (str "### " title " (" (count rows) " tracked)\n"
           "| Name | Invocations |\n"
           "|------|-------------|\n"
           (str/join "\n"
                     (map (fn [[name {:keys [invocations]}]]
                            (str "| " name " | " (format-number invocations) " |"))
                          rows))
           "\n"))))

(defn- format-metrics-summary
  "Render the metrics map as a markdown string for the /metrics command."
  [metrics]
  (let [{:keys [tools workflows commands operations tokens updated-at]} metrics
        sections (keep identity
                       [(format-tools-section tools)
                        (format-tokens-section tokens)
                        (format-simple-section "Workflows" workflows)
                        (format-simple-section "Commands" commands)
                        (format-simple-section "Operations" operations)])]
    (str "## Usage Metrics\n\n"
         (if (seq sections)
           (str/join "\n" sections)
           "_No metrics recorded yet._\n")
         (when updated-at
           (str "\n_Updated: " updated-at "_\n")))))

;;; Operation handler

(defn- invoke-summary
  "Deterministic operation handler for metrics/summary."
  [{:keys [_args]}]
  (update-metrics! counters/inc-operation-invocation "metrics/summary")
  {:status :ok
   :data   (get-metrics)})

;;; Command handler

(defn- metrics-command-handler
  "Slash command handler for /metrics."
  [_args api]
  (update-metrics! counters/inc-command-invocation "metrics")
  (persist/maybe-persist! store writing?)
  ((:notify api)
   (format-metrics-summary (get-metrics))
   {:role "assistant" :custom-type :metrics-summary}))

;;; Init

(defn init
  "Extension entry point. Called by the psi runtime on load and reload.

   On first init: loads persisted metrics from disk, initialises the store atom.
   On reload: preserves accumulated counters; updates worktree-path only."
  [api]
  (let [worktree-path (get ((:query api) [:psi.agent-session/worktree-path])
                           :psi.agent-session/worktree-path)]
    (if @store
      ;; Reload: preserve counters, update worktree-path in case it changed.
      (swap! store assoc :worktree-path worktree-path)
      ;; First init: load persisted metrics (or start empty).
      (let [initial (persist/load-metrics worktree-path)]
        (reset! store (persist/empty-store worktree-path initial))))
    ((:on api) "tool_call"             on-tool-call)
    ((:on api) "tool_result"           on-tool-result)
    ((:on api) "session_turn_finished" (make-turn-finished-handler api))
    ((:register-operation api)
     {:id          "metrics/summary"
      :description "Return current usage metrics for all tracked capabilities"
      :handler     invoke-summary})
    (when-let [register-command (:register-command api)]
      (register-command "metrics"
                        {:description "Display usage metrics summary"
                         :handler     (fn [args] (metrics-command-handler args api))}))
    nil))

;;; Schema re-export for consumers

(def metrics-schema schema/metrics-schema)
