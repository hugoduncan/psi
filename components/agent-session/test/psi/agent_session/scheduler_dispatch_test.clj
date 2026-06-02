(ns psi.agent-session.scheduler-dispatch-test
  (:require
   [clojure.test :refer [deftest is]]
   [psi.agent-session.core :as session]
   [psi.agent-session.test-support :as test-support]))

(defn- schedule
  [_ctx session-id schedule-id status]
  {:schedule-id schedule-id
   :kind :message
   :label nil
   :message (str "message-" schedule-id)
   :source :scheduled
   :created-at (test-support/instant "2026-04-21T12:00:00Z")
   :fire-at (test-support/instant "2026-04-21T12:01:00Z")
   :status status
   :session-id session-id})

(deftest scheduler-create-stores-schedule-and-starts-timer-test
  (let [created-at       (test-support/instant "2026-04-21T12:00:00Z")
        [ctx session-id] (test-support/make-session-ctx {})
        fire-at          (.plusMillis created-at 1000)
        result           (session/dispatch-in! ctx :scheduler/create
                                               {:session-id session-id
                                                :schedule-id "sch-1"
                                                :kind :message
                                                :label "check-build"
                                                :message "Check build"
                                                :created-at created-at
                                                :fire-at fire-at}
                                               {:origin :core})
        stored           (test-support/schedule-by-id ctx session-id "sch-1")]
    (is (= "sch-1" (:schedule-id (:return result result))))
    (is (= "check-build" (:label stored)))
    (is (= :pending (:status stored)))
    (is (contains? @(:scheduler-timers* ctx) "sch-1"))))

(deftest scheduler-cancel-marks-pending-or-queued-schedule-cancelled-test
  (let [initial-schedule (schedule nil "sid-1" "sch-1" :queued)
        [ctx session-id] (test-support/make-session-ctx {:session-data {:session-id "sid-1"
                                                                        :scheduler {:schedules {"sch-1" initial-schedule}
                                                                                    :queue ["sch-1"]}}})
        _                (swap! (:scheduler-timers* ctx) assoc "sch-1" (Thread/currentThread))
        result           (session/dispatch-in! ctx :scheduler/cancel
                                               {:session-id session-id
                                                :schedule-id "sch-1"}
                                               {:origin :core})
        stored           (test-support/schedule-by-id ctx session-id "sch-1")]
    (is (= :cancelled (:status (or (:return result) result))))
    (is (= :cancelled (:status stored)))
    (is (= [] (test-support/schedule-queue ctx session-id)))
    (is (not (contains? @(:scheduler-timers* ctx) "sch-1")))))

(deftest scheduler-fired-queues-while-session-busy-test
  (let [initial-schedule (schedule nil "sid-1" "sch-1" :pending)
        [ctx session-id] (test-support/make-session-ctx {:session-data {:session-id "sid-1"
                                                                        :is-streaming true
                                                                        :scheduler {:schedules {"sch-1" initial-schedule}
                                                                                    :queue []}}})]
    (session/dispatch-in! ctx :scheduler/fired
                          {:session-id session-id
                           :schedule-id "sch-1"}
                          {:origin :core})
    (let [stored (test-support/schedule-by-id ctx session-id "sch-1")]
      (is (= :queued (:status stored)))
      (is (= ["sch-1"] (test-support/schedule-queue ctx session-id))))))

(deftest scheduler-deliver-submits-canonical-prompt-lifecycle-test
  (let [initial-schedule (schedule nil "sid-1" "sch-1" :queued)
        [ctx session-id] (test-support/make-session-ctx {:session-data {:session-id "sid-1"
                                                                        :scheduler {:schedules {"sch-1" initial-schedule}
                                                                                    :queue ["sch-1"]}}})
        result           (session/dispatch-in! ctx :scheduler/deliver
                                               {:session-id session-id
                                                :schedule-id "sch-1"}
                                               {:origin :core})
        stored           (test-support/schedule-by-id ctx session-id "sch-1")
        scheduled-msg    (test-support/scheduled-message-by-id ctx session-id "sch-1")]
    (is (= "sch-1" (:schedule-id (or (:return result) result))))
    (is (= :delivered (:status stored)))
    (is (= [] (test-support/schedule-queue ctx session-id)))
    (is (some? scheduled-msg))))

(deftest scheduler-drain-queue-delivers-oldest-queued-schedule-test
  (let [early              (test-support/instant "2026-04-21T12:00:00Z")
        later              (test-support/instant "2026-04-21T12:05:00Z")
        initial-schedule-1 (assoc (schedule nil "sid-1" "sch-1" :queued) :fire-at later :created-at later)
        initial-schedule-2 (assoc (schedule nil "sid-1" "sch-2" :queued) :fire-at early :created-at early)
        [ctx session-id]
        (test-support/make-session-ctx
         {:session-data {:session-id "sid-1"
                         :scheduler {:schedules {"sch-1" initial-schedule-1
                                                 "sch-2" initial-schedule-2}
                                     :queue ["sch-1" "sch-2" "missing"]}}})
        result (session/dispatch-in! ctx :scheduler/drain-queue
                                     {:session-id session-id}
                                     {:origin :core})]
    (is (true? (:drained? (or (:return result) result))))
    (is (= "sch-2" (:schedule-id (or (:return result) result))))
    (is (= :delivered (test-support/schedule-status ctx session-id "sch-2")))
    (is (= ["sch-1" "missing"] (test-support/schedule-queue ctx session-id)))
    (is (nil? (:effects result)))))
