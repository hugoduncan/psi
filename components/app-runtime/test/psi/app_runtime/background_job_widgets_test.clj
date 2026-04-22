(ns psi.app-runtime.background-job-widgets-test
  (:require
   [clojure.test :refer [deftest is]]
   [psi.app-runtime.background-job-widgets :as widgets]))

(def sample-jobs
  [{:job-id "job-b"
    :tool-name "tool-b"
    :status :pending-cancel
    :started-at "2026-04-07T10:05:00Z"}
   {:job-id "job-a"
    :tool-name "tool-a"
    :status :running
    :started-at "2026-04-07T10:00:00Z"}
   {:job-id "job-c"
    :tool-name "tool-c"
    :status :completed
    :started-at "2026-04-07T10:10:00Z"}])

(deftest background-jobs-widget-builds-command-lines-in-canonical-order-test
  (let [widget (widgets/background-jobs-widget sample-jobs)]
    (is (= ["job-a  [running]  tool-a"
            "job-b  [pending-cancel]  tool-b"]
           (mapv :text (:widget/content-lines widget))))
    (is (= ["/job job-a" "/job job-b"]
           (mapv #(get-in % [:action :command]) (:widget/content-lines widget))))
    (is (false? (:widget/empty? widget)))))

(deftest background-jobs-statuses-build-status-lines-test
  (let [statuses (widgets/background-jobs-statuses sample-jobs)]
    (is (= ["job-a running" "job-b pending-cancel"]
           (mapv :status/text statuses)))
    (is (= [:info :warning]
           (mapv :status/level statuses)))))
