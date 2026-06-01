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
   [psi.metrics.schema :as schema]
   [taoensso.timbre :as timbre]))

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

(defn- content->text
  "Extract human-readable text from tool result content.

   Content is canonically a vec of `{:type :text :text ...}` blocks (both the
   interactive/batch bridge and the plan path normalise via
   `tool-runtime/normalize-tool-content`). Join the `:text` of each block so the
   derived reason is the human-readable error text, not a stringified data
   structure. Falls back to `(str content)` for any non-block-vector value."
  [content]
  (if (and (sequential? content)
           (every? map? content))
    (str/join " " (keep :text content))
    (str content)))

(defn- error-reason
  "Derive a single-line, ≤80-char error reason key from tool result content."
  [content]
  (let [first-line (-> (content->text content) str/split-lines first str/trim)]
    (subs first-line 0 (min 80 (count first-line)))))

(defn- on-tool-result
  "Increment error counters when the tool result is an error."
  [payload]
  (when (and (:tool-name payload) (:is-error payload))
    (update-metrics! counters/inc-tool-error
                     (:tool-name payload)
                     (error-reason (:content payload))))
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
          (timbre/warn e "skipping token tracking for session" session-id))))
    nil))

(defn- on-provider-request-started
  [payload]
  (update-metrics! counters/inc-provider-request (:provider payload) (:model-id payload))
  nil)

(defn- on-provider-retry-scheduled
  [payload]
  (update-metrics! counters/inc-provider-retry (:provider payload) (:model-id payload) (:delay-ms payload))
  nil)

(defn- on-provider-request-finished
  [payload]
  (update-metrics! counters/record-provider-finish
                   (:provider payload)
                   (:model-id payload)
                   {:status (:status payload)
                    :final? (:final? payload)
                    :error-kind (:error-kind payload)})
  nil)

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

(defn- format-providers-section
  [providers]
  (when (seq providers)
    (let [rows (sort-by key providers)]
      (str "### Providers (" (count rows) " tracked)\n"
           "| Provider | Requests | Successes | Failures | Final Failures | Retries | Backoff |\n"
           "|----------|----------|-----------|----------|----------------|---------|---------|\n"
           (str/join "\n"
                     (map (fn [[provider {:keys [requests successes failures final-failures retries retry-backoff-ms]}]]
                            (str "| " provider
                                 " | " (format-number (or requests 0))
                                 " | " (format-number (or successes 0))
                                 " | " (format-number (or failures 0))
                                 " | " (format-number (or final-failures 0))
                                 " | " (format-number (or retries 0))
                                 " | " (format-number (or retry-backoff-ms 0)) "ms |"))
                          rows))
           "\n"))))

(defn- provider-model-rows
  [providers]
  (sort-by (juxt first second)
           (mapcat (fn [[provider {:keys [models]}]]
                     (map (fn [[model stats]] [provider model stats]) (sort-by key models)))
                   providers)))

(defn- format-provider-models-section
  [providers]
  (let [rows (provider-model-rows providers)]
    (when (seq rows)
      (str "### Provider Models\n"
           "| Provider | Model | Requests | Successes | Failures | Final Failures | Retries | Backoff |\n"
           "|----------|-------|----------|-----------|----------|----------------|---------|---------|\n"
           (str/join "\n"
                     (map (fn [[provider model {:keys [requests successes failures final-failures retries retry-backoff-ms]}]]
                            (str "| " provider
                                 " | " model
                                 " | " (format-number (or requests 0))
                                 " | " (format-number (or successes 0))
                                 " | " (format-number (or failures 0))
                                 " | " (format-number (or final-failures 0))
                                 " | " (format-number (or retries 0))
                                 " | " (format-number (or retry-backoff-ms 0)) "ms |"))
                          rows))
           "\n"))))

(defn- format-metrics-summary
  "Render the metrics map as a markdown string for the /metrics command."
  [metrics]
  (let [{:keys [tools workflows commands operations tokens providers updated-at]} metrics
        sections (keep identity
                       [(format-tools-section tools)
                        (format-tokens-section tokens)
                        (format-providers-section providers)
                        (format-provider-models-section providers)
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
    ((:on api) "tool_call"                 on-tool-call)
    ((:on api) "tool_result"               on-tool-result)
    ((:on api) "session_turn_finished"     (make-turn-finished-handler api))
    ((:on api) "provider_request_started"  on-provider-request-started)
    ((:on api) "provider_retry_scheduled"  on-provider-retry-scheduled)
    ((:on api) "provider_request_finished" on-provider-request-finished)
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
