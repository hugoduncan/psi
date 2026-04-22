(ns psi.agent-session.scheduler-dispatch-test
  (:require
   [clojure.test :refer [deftest is]]
   [psi.agent-session.dispatch :as dispatch]
   [psi.agent-session.session-state :as ss]
   [psi.agent-session.test-support :as test-support]))

(defn- schedule
  [_ctx session-id schedule-id status]
  {:schedule-id schedule-id
   :label nil
   :message (str "message-" schedule-id)
   :source :scheduled
   :created-at (java.time.Instant/now)
   :fire-at (java.time.Instant/now)
   :status status
   :session-id session-id})

(deftest scheduler-create-stores-schedule-and-starts-timer-test
  (let [[ctx session-id] (test-support/make-session-ctx {})
        fire-at          (java.time.Instant/now)
        result           (dispatch/dispatch! ctx :scheduler/create
                                             {:session-id session-id
                                              :schedule-id "sch-1"
                                              :label "check-build"
                                              :message "Check build"
                                              :fire-at fire-at}
                                             {:origin :core})
        stored           (get-in (ss/get-session-data-in ctx session-id)
                                 [:scheduler :schedules "sch-1"])]
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
        result           (dispatch/dispatch! ctx :scheduler/cancel
                                             {:session-id session-id
                                              :schedule-id "sch-1"}
                                             {:origin :core})
        stored           (get-in (ss/get-session-data-in ctx session-id)
                                 [:scheduler :schedules "sch-1"])]
    (is (= :cancelled (:status (or (:return result) result))))
    (is (= :cancelled (:status stored)))
    (is (= [] (get-in (ss/get-session-data-in ctx session-id) [:scheduler :queue])))
    (is (not (contains? @(:scheduler-timers* ctx) "sch-1")))))

(deftest scheduler-fired-queues-while-session-busy-test
  (let [initial-schedule (schedule nil "sid-1" "sch-1" :pending)
        [ctx session-id] (test-support/make-session-ctx {:session-data {:session-id "sid-1"
                                                                        :is-streaming true
                                                                        :scheduler {:schedules {"sch-1" initial-schedule}
                                                                                    :queue []}}})]
    (dispatch/dispatch! ctx :scheduler/fired
                        {:session-id session-id
                         :schedule-id "sch-1"}
                        {:origin :core})
    (let [stored (get-in (ss/get-session-data-in ctx session-id)
                         [:scheduler :schedules "sch-1"])]
      (is (= :queued (:status stored)))
      (is (= :queued (:status stored)))
      (is (= ["sch-1"] (get-in (ss/get-session-data-in ctx session-id) [:scheduler :queue]))))))

(deftest scheduler-deliver-submits-canonical-prompt-lifecycle-test
  (let [initial-schedule (schedule nil "sid-1" "sch-1" :queued)
        [ctx session-id] (test-support/make-session-ctx {:session-data {:session-id "sid-1"
                                                                        :scheduler {:schedules {"sch-1" initial-schedule}
                                                                                    :queue ["sch-1"]}}})
        result           (dispatch/dispatch! ctx :scheduler/deliver
                                             {:session-id session-id
                                              :schedule-id "sch-1"}
                                             {:origin :core})
        stored           (get-in (ss/get-session-data-in ctx session-id)
                                 [:scheduler :schedules "sch-1"])
        journal          (ss/get-state-value-in ctx (ss/state-path :journal session-id))
        scheduled-msg    (some->> journal
                                  (keep #(get-in % [:data :message]))
                                  (some (fn [message]
                                          (when (and (= "user" (:role message))
                                                     (= :scheduled (:source message))
                                                     (= "sch-1" (:schedule-id message)))
                                            message))))]
    (is (= "sch-1" (:schedule-id (or (:return result) result))))
    (is (= :delivered (:status stored)))
    (is (= [] (get-in (ss/get-session-data-in ctx session-id) [:scheduler :queue])))
    (is (some? scheduled-msg))))

(deftest scheduler-drain-queue-delivers-oldest-queued-schedule-test
  (let [early              (java.time.Instant/parse "2026-04-21T12:00:00Z")
        later              (java.time.Instant/parse "2026-04-21T12:05:00Z")
        initial-schedule-1 (assoc (schedule nil "sid-1" "sch-1" :queued) :fire-at later :created-at later)
        initial-schedule-2 (assoc (schedule nil "sid-1" "sch-2" :queued) :fire-at early :created-at early)
        [ctx session-id]
        (test-support/make-session-ctx
         {:session-data {:session-id "sid-1"
                         :scheduler {:schedules {"sch-1" initial-schedule-1
                                                 "sch-2" initial-schedule-2}
                                     :queue ["sch-1" "sch-2" "missing"]}}})
        result (dispatch/dispatch! ctx :scheduler/drain-queue
                                   {:session-id session-id}
                                   {:origin :core})]
    (is (true? (:drained? (or (:return result) result))))
    (is (= "sch-2" (:schedule-id (or (:return result) result))))
    (is (= :delivered (get-in (ss/get-session-data-in ctx session-id)
                              [:scheduler :schedules "sch-2" :status])))
    (is (= ["missing"] (get-in (ss/get-session-data-in ctx session-id) [:scheduler :queue])))
    (is (nil? (:effects result)))))
