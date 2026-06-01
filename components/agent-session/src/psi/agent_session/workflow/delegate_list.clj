(ns psi.agent-session.workflow.delegate-list
  "Pure delegate-list projection over canonical workflow runs and delegate background jobs."
  (:require
   [clojure.string :as str]
   [psi.agent-session.background-jobs :as background-jobs]
   [psi.agent-session.workflow.orchestration :as orchestration]))

(def workflow-provenance-id orchestration/workflow-provenance-id)

(defn- present-string?
  [x]
  (and (string? x) (not (str/blank? x))))

(defn- projection-error
  [reason message details]
  {:status :error
   :reason reason
   :message message
   :details details})

(defn- valid-provenance?
  [job]
  (= workflow-provenance-id (:workflow-ext-path job)))

(defn- eligible-session-job?
  [session-id job]
  (= (str session-id) (:thread-id job)))

(defn- terminal-job?
  [job]
  (background-jobs/terminal-status? (:status job)))

(defn- non-terminal-job?
  [job]
  (background-jobs/non-terminal-status? (:status job)))

(defn- invalid-delegate-workflow-job
  [job]
  (projection-error
   :malformed-delegate-workflow-job
   "Delegate workflow background job has missing or foreign workflow provenance"
   {:job-id (:job-id job)
    :workflow-ext-path (:workflow-ext-path job)}))

(defn- malformed-workflow-id-error
  [job]
  (projection-error
   :malformed-delegate-workflow-id
   "Non-terminal delegate workflow background job has no manageable workflow id"
   {:job-id (:job-id job)
    :workflow-id (:workflow-id job)}))

(defn- missing-canonical-run-error
  [job]
  (projection-error
   :missing-canonical-workflow-run
   "Non-terminal delegate workflow background job references a missing canonical workflow run"
   {:job-id (:job-id job)
    :workflow-id (:workflow-id job)}))

(defn- duplicate-non-terminal-error
  [workflow-id jobs]
  (projection-error
   :duplicate-non-terminal-delegate-jobs
   "Multiple non-terminal delegate workflow background jobs reference the same canonical workflow run"
   {:workflow-id workflow-id
    :job-ids (mapv :job-id jobs)}))

(defn- malformed-projection-shape-error
  [job]
  (projection-error
   :malformed-background-job-shape
   "Delegate-list projection expects unqualified background-job maps"
   {:job-keys (->> (keys job) (mapv str) sort)}))

(defn- compare-present
  [a b]
  (cond
    (and (some? a) (nil? b)) 1
    (and (nil? a) (some? b)) -1
    (and (nil? a) (nil? b)) 0
    :else (compare a b)))

(defn- compare-fields
  [field-fns a b]
  (loop [[field-fn & more] field-fns]
    (if field-fn
      (let [c (compare-present (field-fn a) (field-fn b))]
        (if (zero? c)
          (recur more)
          c))
      0)))

(defn- newest-terminal-job
  [jobs]
  (last (sort #(compare-fields [:completed-at :completed-seq :job-seq :job-id] %1 %2) jobs)))

(defn- newest-row-first
  [row-a row-b]
  (- (compare-fields [(comp :started-at :delegate-job)
                      (comp :job-seq :delegate-job)
                      (comp :job-id :delegate-job)
                      :run-id]
                     row-a
                     row-b)))

(defn- row-for
  [run job]
  {:run-id (:run-id run)
   :workflow-id (:workflow-id job)
   :source-definition-id (:source-definition-id run)
   :definition-id (or (:source-definition-id run) (:definition-id run))
   :status (:status run)
   :workflow-status (:status run)
   :delegate-status (:status job)
   :background-status (:status job)
   :current-step-id (:current-step-id run)
   :created-at (:created-at run)
   :started-at (:started-at job)
   :completed-at (:completed-at job)
   :job-id (:job-id job)
   :job-seq (:job-seq job)
   :delegate-job job
   :run run})

(defn- invalid-shape?
  [job]
  (some namespace (keys job)))

(defn- validate-and-filter-jobs
  [session-id jobs]
  (loop [[job & more] jobs
         visible []]
    (if (nil? job)
      {:status :ok :jobs visible}
      (cond
        (invalid-shape? job)
        (malformed-projection-shape-error job)

        (not (eligible-session-job? session-id job))
        (recur more visible)

        (not= "delegate" (:tool-name job))
        (recur more visible)

        (not= :workflow (:job-kind job))
        (recur more visible)

        (not (valid-provenance? job))
        (invalid-delegate-workflow-job job)

        (not (present-string? (:workflow-id job)))
        (if (non-terminal-job? job)
          (malformed-workflow-id-error job)
          (recur more visible))

        :else
        (recur more (conj visible job))))))

(defn- visible-group-row
  [run-by-id workflow-id jobs]
  (let [non-terminal-jobs (filterv non-terminal-job? jobs)
        terminal-jobs (filterv terminal-job? jobs)
        run (get run-by-id workflow-id)]
    (cond
      (> (count non-terminal-jobs) 1)
      (duplicate-non-terminal-error workflow-id non-terminal-jobs)

      (= 1 (count non-terminal-jobs))
      (if run
        {:status :ok :row (row-for run (first non-terminal-jobs))}
        (missing-canonical-run-error (first non-terminal-jobs)))

      run
      {:status :ok :row (row-for run (newest-terminal-job terminal-jobs))}

      :else
      {:status :ok :row nil})))

(defn project-visible-runs
  "Return {:status :ok :runs [...]} or {:status :error ...} for visible delegate runs.

   `background-jobs` must be canonical unqualified background-job maps. Callers
   normalize query-shaped `:psi.background-job/*` maps before invoking this pure
   projection."
  [{:keys [session-id runs background-jobs]}]
  (let [filtered-result (validate-and-filter-jobs session-id background-jobs)]
    (if (= :error (:status filtered-result))
      filtered-result
      (let [run-by-id (into {} (map (juxt :run-id identity)) runs)]
        (loop [[[workflow-id jobs] & more] (seq (group-by :workflow-id (:jobs filtered-result)))
               rows []]
          (if (nil? workflow-id)
            {:status :ok
             :runs (vec (sort newest-row-first rows))}
            (let [row-result (visible-group-row run-by-id workflow-id jobs)]
              (if (= :error (:status row-result))
                row-result
                (recur more (cond-> rows (:row row-result) (conj (:row row-result))))))))))))

(defn normalize-query-job
  "Convert a public EQL background-job map to the unqualified projection shape."
  [job]
  (background-jobs/eql->job job))
