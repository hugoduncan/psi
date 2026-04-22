(ns psi.app-runtime.background-job-view
  "Canonical background-job view model shared across app-runtime projections."
  (:require
   [clojure.string :as str]))

(def default-list-statuses
  [:running :pending-cancel])

(defn status-order
  [status]
  (case status
    :running 0
    :pending-cancel 1
    :completed 2
    :failed 3
    :cancelled 4
    :timed-out 5
    99))

(defn sort-jobs
  [jobs]
  (->> (or jobs [])
       (sort-by (juxt #(status-order (:status %))
                      #(or (:started-at %) "")
                      #(or (:job-id %) "")))
       vec))

(defn- render-status-label
  [status]
  (some-> status name))

(defn job-summary
  [job]
  (let [suffix (case (:job-kind job)
                 :scheduled-prompt (str " @ " (or (:fire-at job) "unknown-fire-time"))
                 nil)]
    {:job/id                  (:job-id job)
     :job/tool-name           (:tool-name job)
     :job/status              (:status job)
     :job/status-label        (render-status-label (:status job))
     :job/thread-id           (:thread-id job)
     :job/job-kind            (:job-kind job)
     :job/workflow-ext-path   (:workflow-ext-path job)
     :job/workflow-id         (:workflow-id job)
     :job/started-at          (:started-at job)
     :job/completed-at        (:completed-at job)
     :job/cancel-requested-at (:cancel-requested-at job)
     :job/is-terminal         (contains? #{:completed :failed :cancelled :timed-out} (:status job))
     :job/is-running          (= :running (:status job))
     :job/is-cancelling       (= :pending-cancel (:status job))
     :job/list-line           (str (or (:job-id job) "(unknown-job)")
                                   "  [" (or (render-status-label (:status job)) "unknown") "]"
                                   "  " (or (:tool-name job) "(unknown-tool)")
                                   (or suffix ""))}))

(defn jobs-summary
  ([jobs]
   (jobs-summary jobs {}))
  ([jobs {:keys [statuses]
          :or   {statuses default-list-statuses}}]
   (let [status-set (set statuses)
         jobs*      (->> (or jobs [])
                         (filter #(contains? status-set (:status %)))
                         sort-jobs
                         vec)
         items      (mapv job-summary jobs*)]
     {:jobs/statuses statuses
      :jobs/items    items
      :jobs/empty?   (empty? items)
      :jobs/text     (str "── Background jobs ─────────────────────\n"
                          (if (empty? items)
                            "  (none)"
                            (str/join "\n" (map (comp #(str "  " %) :job/list-line) items)))
                          "\n───────────────────────────────────────")})))

(defn job-detail
  [job]
  (let [summary (job-summary job)]
    (assoc summary
           :job/text
           (str "── Background job ──────────────────────\n"
                (pr-str job)
                "\n───────────────────────────────────────"))))

(defn cancel-job-summary
  [job-id job]
  {:job/id      job-id
   :job/status  (:status job)
   :job/message (str "Cancellation requested for " job-id
                     " (status=" (name (:status job)) ")")})
