(ns psi.agent-session.scheduler-cancel-job-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.core :as session]
   [psi.agent-session.test-support :as test-support]))

(deftest session-cancel-job-routes-scheduler-projection-to-scheduler-cancel-test
  (testing "session/cancel-job cancels scheduler-projected jobs by schedule id"
    (let [[ctx session-id] (test-support/create-test-session {:persist? false})
          _                (session/dispatch-in! ctx :scheduler/create
                                                 {:session-id session-id
                                                  :schedule-id "sch-1"
                                                  :label "check-build"
                                                  :message "check build"
                                                  :created-at (java.time.Instant/parse "2099-04-21T17:59:00Z")
                                                  :fire-at (java.time.Instant/parse "2099-04-21T18:00:00Z")}
                                                 {:origin :core})
          result           (session/cancel-job-in! ctx session-id "sch-1" :user)]
      (is (= :cancelled (:status result)))
      (is (= :cancelled (get-in @(:state* ctx) [:agent-session :sessions session-id :data :scheduler :schedules "sch-1" :status]))))))
