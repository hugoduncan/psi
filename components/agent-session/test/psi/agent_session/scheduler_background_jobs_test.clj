(ns psi.agent-session.scheduler-background-jobs-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.background-job-runtime :as bg-rt]
   [psi.agent-session.core :as session]
   [psi.agent-session.test-support :as test-support]))

(deftest scheduler-background-job-projection-test
  (testing "pending and queued schedules project into background jobs"
    (let [[ctx session-id] (test-support/create-test-session {:persist? false})
          _                (session/dispatch-in! ctx :scheduler/create
                                                 {:session-id session-id
                                                  :schedule-id "sch-1"
                                                  :kind :message
                                                  :label "check-build"
                                                  :message "check build"
                                                  :created-at (java.time.Instant/parse "2099-04-21T17:59:00Z")
                                                  :fire-at (java.time.Instant/parse "2099-04-21T18:00:00Z")}
                                                 {:origin :core})
          _                (session/dispatch-in! ctx :scheduler/create
                                                 {:session-id session-id
                                                  :schedule-id "sch-2"
                                                  :kind :session
                                                  :label "review"
                                                  :message "review"
                                                  :session-config {:session-name "review later"}
                                                  :created-at (java.time.Instant/parse "2099-04-21T18:59:00Z")
                                                  :fire-at (java.time.Instant/parse "2099-04-21T19:00:00Z")}
                                                 {:origin :core})
          _                (swap! (:state* ctx) assoc-in [:agent-session :sessions session-id :data :scheduler :schedules "sch-2" :status] :queued)
          _                (swap! (:state* ctx) assoc-in [:agent-session :sessions session-id :data :scheduler :queue] ["sch-2"])
          jobs             (bg-rt/list-background-jobs-in! ctx session-id [:pending :queued])]
      (is (= 2 (count jobs)))
      (is (= #{{:job-id "schedule/sch-1" :status :running :job-kind :scheduled-prompt :tool-name "check-build"}
               {:job-id "schedule/sch-2" :status :running :job-kind :scheduled-session :tool-name "review"}}
             (set (map #(select-keys % [:job-id :status :job-kind :tool-name]) jobs))))))

  (testing "scheduler-projected background job cancel routes to scheduler cancel"
    (let [[ctx session-id] (test-support/create-test-session {:persist? false})
          _                (session/dispatch-in! ctx :scheduler/create
                                                 {:session-id session-id
                                                  :schedule-id "sch-1"
                                                  :kind :message
                                                  :label "check-build"
                                                  :message "check build"
                                                  :created-at (java.time.Instant/parse "2099-04-21T17:59:00Z")
                                                  :fire-at (java.time.Instant/parse "2099-04-21T18:00:00Z")}
                                                 {:origin :core})
          cancelled        (bg-rt/cancel-background-job-in! ctx session-id "schedule/sch-1" :user)]
      (is (= :cancelled (:status cancelled)))
      (is (= :cancelled (get-in @(:state* ctx) [:agent-session :sessions session-id :data :scheduler :schedules "sch-1" :status]))))))
