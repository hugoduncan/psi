(ns psi.app-runtime.background-job-ui
  "Project background jobs into canonical extension UI widget/status state."
  (:require
   [clojure.string :as str]
   [psi.agent-session.background-job-runtime :as bg-rt]
   [psi.agent-session.core :as session]
   [psi.agent-session.session-state :as ss]
   [psi.app-runtime.background-job-widgets :as bg-widgets]))

(def ^:private widget-extension-id "psi-background-jobs")
(def ^:private widget-id "background-jobs")
(def ^:private status-extension-prefix "psi-background-jobs/")

(defn- clear-stale-statuses!
  [ctx ui-state active-status-ids]
  (doseq [status-id (->> (:statuses ui-state)
                         keys
                         (filter #(and (string? %)
                                       (str/starts-with? % status-extension-prefix)
                                       (not (contains? active-status-ids %)))))]
    (session/dispatch-in! ctx :session/ui-clear-status {:extension-id status-id}
                          {:origin :core})))

(defn refresh-background-jobs-ui!
  [ctx session-id]
  (let [jobs        (bg-rt/list-background-jobs-in! ctx session-id [:running :pending-cancel])
        widget      (bg-widgets/background-jobs-widget jobs {:extension-id widget-extension-id
                                                             :widget-id widget-id
                                                             :placement :below-editor})
        statuses    (bg-widgets/background-jobs-statuses jobs {:extension-id widget-extension-id})
        ui-state    (ss/get-state-value-in ctx (ss/state-path :ui-state))
        active-ids  (into #{} (map #(str status-extension-prefix (:status/job-id %))) statuses)]
    (if (:widget/empty? widget)
      (session/dispatch-in! ctx :session/ui-clear-widget {:extension-id widget-extension-id
                                                          :widget-id widget-id}
                            {:origin :core})
      (session/dispatch-in! ctx :session/ui-set-widget {:extension-id widget-extension-id
                                                        :widget-id widget-id
                                                        :placement (:widget/placement widget)
                                                        :content (:widget/content-lines widget)}
                            {:origin :core}))
    (clear-stale-statuses! ctx ui-state active-ids)
    (doseq [status statuses]
      (session/dispatch-in! ctx :session/ui-set-status {:extension-id (str status-extension-prefix (:status/job-id status))
                                                        :text (:status/text status)}
                            {:origin :core}))
    {:jobs jobs
     :widget widget
     :statuses statuses}))

(defn maybe-refresh-from-command-result!
  [ctx session-id cmd-result]
  (when (contains? #{:text} (:type cmd-result))
    (refresh-background-jobs-ui! ctx session-id)))

(defn install-background-job-ui-refresh!
  [ctx]
  (reset! (:background-job-ui-refresh-fn ctx) refresh-background-jobs-ui!))
