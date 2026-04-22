(ns psi.app-runtime.background-job-view-test
  (:require
   [clojure.test :refer [deftest is]]
   [psi.app-runtime.background-job-view :as bg]))

(deftest jobs-summary-sorts-and-renders-canonical-list-text-test
  (let [summary (bg/jobs-summary
                 [{:job-id "job-c"
                   :tool-name "tool-c"
                   :status :completed
                   :started-at "2026-04-07T10:10:00Z"}
                  {:job-id "job-a"
                   :tool-name "tool-a"
                   :status :running
                   :started-at "2026-04-07T10:00:00Z"}
                  {:job-id "job-b"
                   :tool-name "tool-b"
                   :status :pending-cancel
                   :started-at "2026-04-07T10:05:00Z"}]
                 {:statuses [:running :pending-cancel :completed]})]
    (is (= ["job-a" "job-b" "job-c"]
           (mapv :job/id (:jobs/items summary))))
    (is (false? (:jobs/empty? summary)))
    (is (.contains (:jobs/text summary) "job-a  [running]  tool-a"))
    (is (.contains (:jobs/text summary) "job-b  [pending-cancel]  tool-b"))
    (is (.contains (:jobs/text summary) "job-c  [completed]  tool-c"))))

(deftest jobs-summary-renders-empty-state-test
  (let [summary (bg/jobs-summary [] {:statuses [:running :pending-cancel]})]
    (is (true? (:jobs/empty? summary)))
    (is (.contains (:jobs/text summary) "(none)"))))

(deftest job-detail-and-cancel-summary-render-canonical-text-test
  (let [job {:job-id "job-1"
             :tool-name "delegate"
             :status :pending-cancel
             :thread-id "s1"}
        detail (bg/job-detail job)
        cancel (bg/cancel-job-summary "job-1" job)]
    (is (= "job-1" (:job/id detail)))
    (is (.contains (:job/text detail) "job-1"))
    (is (= "Cancellation requested for job-1 (status=pending-cancel)"
           (:job/message cancel)))))

(deftest scheduled-prompt-job-summary-includes-fire-time-test
  (let [summary (bg/job-summary {:job-id "schedule/sch-1"
                                 :tool-name "check-build"
                                 :job-kind :scheduled-prompt
                                 :status :running
                                 :fire-at "2026-04-21T18:05:00Z"})]
    (is (.contains (:job/list-line summary) "check-build"))
    (is (.contains (:job/list-line summary) "2026-04-21T18:05:00Z"))))
