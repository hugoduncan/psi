(ns psi.app-runtime.background-job-widgets
  "Adapter-neutral background-job widget/status projections shared across interactive UIs."
  (:require
   [psi.app-runtime.background-job-view :as view]))

(defn- status->level
  [status]
  (case status
    :failed :error
    :timed-out :error
    :pending-cancel :warning
    :running :info
    :completed :info
    :cancelled :info
    :info))

(defn- widget-lines
  [items]
  (vec
   (map (fn [item]
          {:text (str (:job/list-line item))
           :action {:type :command
                    :command (str "/job " (:job/id item))}})
        items)))

(defn background-jobs-widget
  ([jobs]
   (background-jobs-widget jobs {}))
  ([jobs {:keys [statuses extension-id widget-id placement]
          :or   {statuses [:running :pending-cancel]
                 extension-id "psi-background-jobs"
                 widget-id "background-jobs"
                 placement :below-editor}}]
   (let [summary (:jobs/items (view/jobs-summary jobs {:statuses statuses}))]
     {:widget/extension-id extension-id
      :widget/widget-id widget-id
      :widget/placement placement
      :widget/content-lines (widget-lines summary)
      :widget/empty? (empty? summary)})))

(defn background-jobs-statuses
  ([jobs]
   (background-jobs-statuses jobs {}))
  ([jobs {:keys [extension-id statuses]
          :or   {extension-id "psi-background-jobs"
                 statuses [:running :pending-cancel]}}]
   (let [items (:jobs/items (view/jobs-summary jobs {:statuses statuses}))]
     (vec
      (for [item items]
        {:status/extension-id extension-id
         :status/text (str (:job/id item) " " (:job/status-label item))
         :status/level (status->level (:job/status item))
         :status/job-id (:job/id item)})))))
