(ns psi.app-runtime.background-job-ui-test
  (:require
   [clojure.test :refer [deftest is]]
   [psi.agent-session.background-jobs :as bg-jobs]
   [psi.agent-session.core :as session]
   [psi.agent-session.dispatch :as dispatch]
   [psi.agent-session.session-state :as ss]
   [psi.agent-session.test-support :as test-support]
   [psi.app-runtime.background-job-ui :as bg-ui]))

(defn- make-ctx []
  (let [ctx (session/create-context (test-support/safe-context-opts {}))
        sd  (session/new-session-in! ctx nil {})]
    [ctx (:session-id sd)]))

(deftest refresh-background-jobs-ui-projects-widget-and-statuses-test
  (let [[ctx session-id] (make-ctx)
        _ (dispatch/dispatch! ctx :session/update-background-jobs-state
                              {:update-fn (fn [store]
                                            (:state (bg-jobs/start-background-job
                                                     store
                                                     {:tool-call-id "tc-1"
                                                      :thread-id session-id
                                                      :tool-name "delegate"
                                                      :job-id "job-1"})))}
                              {:origin :test})
        _ (dispatch/dispatch! ctx :session/update-background-jobs-state
                              {:update-fn (fn [store]
                                            (:state (bg-jobs/start-background-job
                                                     store
                                                     {:tool-call-id "tc-2"
                                                      :thread-id session-id
                                                      :tool-name "delegate"
                                                      :job-id "job-2"})))}
                              {:origin :test})
        _ (dispatch/dispatch! ctx :session/update-background-jobs-state
                              {:update-fn (fn [store]
                                            (bg-jobs/request-cancel store {:thread-id session-id :job-id "job-2"}))}
                              {:origin :test})
        _ (bg-ui/refresh-background-jobs-ui! ctx session-id)
        ui-state (ss/get-state-value-in ctx (ss/state-path :ui-state))
        widget (get-in ui-state [:widgets ["psi-background-jobs" "background-jobs"]])]
    (is (= ["job-1  [running]  delegate"
            "job-2  [pending-cancel]  delegate"]
           (:content widget)))
    (is (= "job-1 running"
           (get-in ui-state [:statuses "psi-background-jobs/job-1" :text])))
    (is (= "job-2 pending-cancel"
           (get-in ui-state [:statuses "psi-background-jobs/job-2" :text])))))

(deftest refresh-background-jobs-ui-clears-stale-widget-and-statuses-test
  (let [[ctx session-id] (make-ctx)
        _ (dispatch/dispatch! ctx :session/ui-set-widget {:extension-id "psi-background-jobs"
                                                          :widget-id "background-jobs"
                                                          :placement :below-editor
                                                          :content ["stale"]}
                              {:origin :test})
        _ (dispatch/dispatch! ctx :session/ui-set-status {:extension-id "psi-background-jobs/job-old"
                                                          :text "job-old running"}
                              {:origin :test})
        _ (bg-ui/refresh-background-jobs-ui! ctx session-id)
        ui-state (ss/get-state-value-in ctx (ss/state-path :ui-state))]
    (is (nil? (get-in ui-state [:widgets ["psi-background-jobs" "background-jobs"]])))
    (is (nil? (get-in ui-state [:statuses "psi-background-jobs/job-old"])))))

(deftest refresh-background-jobs-ui-projects-scheduled-prompts-test
  (let [[ctx session-id] (make-ctx)
        _ (dispatch/dispatch! ctx :scheduler/create
                              {:session-id session-id
                               :schedule-id "sch-ui-1"
                               :label "check-build"
                               :message "check build later"
                               :created-at (java.time.Instant/parse "2099-04-21T18:00:00Z")
                               :fire-at (java.time.Instant/parse "2099-04-21T18:05:00Z")
                               :delay-ms 1000}
                              {:origin :test})
        _ (bg-ui/refresh-background-jobs-ui! ctx session-id)
        ui-state (ss/get-state-value-in ctx (ss/state-path :ui-state))
        widget (get-in ui-state [:widgets ["psi-background-jobs" "background-jobs"]])]
    (is (= ["schedule/sch-ui-1  [running]  check-build @ 2099-04-21T18:05:00Z"]
           (:content widget)))
    (is (= "schedule/sch-ui-1 running"
           (get-in ui-state [:statuses "psi-background-jobs/schedule/sch-ui-1" :text])))))
