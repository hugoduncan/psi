(ns psi.agent-session.scheduler-effects-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.core :as session]
   [psi.agent-session.dispatch :as dispatch]
   [psi.agent-session.dispatch-effects :as dispatch-effects]
   [psi.session-state.state :as ss]
   [psi.agent-session.test-support :as test-support]))

(deftest scheduler-start-and-cancel-timer-effects-test
  (dispatch-effects/cancel-all-scheduler-timers!)
  (testing "start-timer dispatches scheduler/fired after delay and removes handle"
    (let [now (java.time.Instant/parse "2026-04-21T18:00:00Z")
          [ctx session-id] (test-support/create-test-session {:scheduler-time-source (test-support/fixed-scheduler-time-source now)})
          fired (promise)]
      (with-redefs [dispatch/dispatch!
                    (fn [_ctx event-type event-data _opts]
                      (when (= :scheduler/fired event-type)
                        (deliver fired event-data))
                      nil)]
        (is (= 0 (dispatch-effects/scheduler-timer-handle-count)))
        (dispatch-effects/execute-effect! ctx {:effect/type :scheduler/start-timer
                                               :session-id session-id
                                               :schedule-id "sch-1"
                                               :fire-at (.plusMillis now 20)})
        (is (= {:session-id session-id :schedule-id "sch-1"}
               (deref fired 1000 ::timeout)))
        (loop [i 0]
          (when (and (< i 20) (not= 0 (dispatch-effects/scheduler-timer-handle-count)))
            (Thread/sleep 10)
            (recur (inc i))))
        (is (= 0 (dispatch-effects/scheduler-timer-handle-count))))))

  (testing "start-timer requires scheduler time source"
    (let [[ctx session-id] (test-support/create-test-session)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"scheduler time-source"
                            (dispatch-effects/execute-effect! (dissoc ctx :scheduler-time-source)
                                                              {:effect/type :scheduler/start-timer
                                                               :session-id session-id
                                                               :schedule-id "sch-missing-time"
                                                               :fire-at (java.time.Instant/parse "2026-04-21T18:01:00Z")})))))

  (testing "start-timer rejects invalid scheduler time-source return"
    (let [[ctx session-id] (test-support/create-test-session {:scheduler-time-source (fn [] "not-an-instant")})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"java.time.Instant"
                            (dispatch-effects/execute-effect! ctx
                                                              {:effect/type :scheduler/start-timer
                                                               :session-id session-id
                                                               :schedule-id "sch-invalid-time"
                                                               :fire-at (java.time.Instant/parse "2026-04-21T18:01:00Z")})))))

  (testing "cancel-timer interrupts and removes handle"
    (let [now (java.time.Instant/parse "2026-04-21T18:01:00Z")
          [ctx session-id] (test-support/create-test-session {:scheduler-time-source (test-support/fixed-scheduler-time-source now)})]
      (with-redefs [dispatch/dispatch!
                    (fn [_ctx _event-type _event-data _opts]
                      (throw (ex-info "should not fire" {})))]
        (dispatch-effects/execute-effect! ctx {:effect/type :scheduler/start-timer
                                               :session-id session-id
                                               :schedule-id "sch-2"
                                               :fire-at (.plusMillis now 200)})
        (is (= 1 (dispatch-effects/scheduler-timer-handle-count)))
        (is (= {:schedule-id "sch-2" :cancelled? true}
               (dispatch-effects/execute-effect! ctx {:effect/type :scheduler/cancel-timer
                                                      :schedule-id "sch-2"})))
        (Thread/sleep 30)
        (is (= 0 (dispatch-effects/scheduler-timer-handle-count)))))))

(deftest shutdown-context-cancels-scheduler-timers-test
  (dispatch-effects/cancel-all-scheduler-timers!)
  (let [[ctx session-id] (test-support/create-test-session)]
    (session/dispatch-in! ctx :scheduler/create
                          {:session-id session-id
                           :schedule-id "sch-3"
                           :kind :message
                           :message "shutdown cleanup"
                           :created-at (java.time.Instant/parse "2099-04-21T18:00:00Z")
                           :fire-at (java.time.Instant/parse "2099-04-21T18:05:00Z")
                           :delay-ms 500}
                          {:origin :core})
    (session/dispatch-in! ctx :scheduler/create
                          {:session-id session-id
                           :schedule-id "sch-4"
                           :kind :message
                           :message "shutdown cleanup 2"
                           :created-at (java.time.Instant/parse "2099-04-21T18:00:01Z")
                           :fire-at (java.time.Instant/parse "2099-04-21T18:05:01Z")
                           :delay-ms 500}
                          {:origin :core})
    (is (= 2 (dispatch-effects/scheduler-timer-handle-count)))
    (session/shutdown-context! ctx)
    (is (= 0 (dispatch-effects/scheduler-timer-handle-count)))
    (is (= :cancelled
           (get-in (ss/get-session-data-in ctx session-id) [:scheduler :schedules "sch-3" :status])))
    (is (= :cancelled
           (get-in (ss/get-session-data-in ctx session-id) [:scheduler :schedules "sch-4" :status])))))
