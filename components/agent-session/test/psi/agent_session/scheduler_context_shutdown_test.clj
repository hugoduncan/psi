(ns psi.agent-session.scheduler-context-shutdown-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.core :as session]
   [psi.agent-session.test-support :as test-support]))

(deftest shutdown-context-clears-scheduler-timers-test
  (testing "context shutdown interrupts and clears scheduler timer handles"
    (let [[ctx session-id] (test-support/create-test-session {:persist? false})
          _                (session/dispatch-in! ctx :scheduler/create
                                                 {:session-id session-id
                                                  :schedule-id "sch-1"
                                                  :kind :message
                                                  :label "later"
                                                  :message "later"
                                                  :created-at (test-support/instant "2099-04-21T17:59:00Z")
                                                  :fire-at (test-support/instant "2099-04-21T18:00:00Z")}
                                                 {:origin :core})]
      (is (contains? @(:scheduler-timers* ctx) "sch-1"))
      (session/shutdown-context! ctx)
      (is (= {} @(:scheduler-timers* ctx))))))

;; --- 201 verification: context shutdown clears timers and prevents a captured
;; callback from firing :scheduler/fired afterwards (no fire-after-shutdown).

(deftest shutdown-context-prevents-captured-timer-callback-from-firing-test
  (testing "after shutdown the schedule is cancelled and invoking a captured stale callback does not fire/deliver"
    (let [now              (test-support/instant "2026-04-21T17:40:00Z")
          [ctx session-id] (test-support/create-test-session
                            {:persist? false
                             :scheduler-time-source (test-support/fixed-scheduler-time-source now)})
          [capture* callback*] (test-support/capturing-delay-fn)
          ctx*             (assoc ctx
                                  :scheduler-run-after-delay-fn capture*
                                  :scheduler-cancel-delay-fn (fn [_ctx _handle] nil))]
      (session/dispatch-in! ctx* :scheduler/create
                            {:session-id session-id
                             :schedule-id "sch-shutdown"
                             :kind :message
                             :label "later"
                             :message "later"
                             :created-at now
                             :fire-at (.plusMillis now 5000)}
                            {:origin :core})
      (is (some? @callback*) "timer callback captured")
      (is (contains? @(:scheduler-timers* ctx*) "sch-shutdown"))
      (session/shutdown-context! ctx*)
      (is (nil? (get @(:scheduler-timers* ctx*) "sch-shutdown"))
          "shutdown removed the timer handle")
      (is (= :cancelled (test-support/schedule-status ctx* session-id "sch-shutdown"))
          "shutdown cancelled the outstanding schedule")
      ;; invoking the stale captured callback post-shutdown must not deliver
      ((:f @callback*))
      (is (= :cancelled (test-support/schedule-status ctx* session-id "sch-shutdown"))
          "no fire-after-shutdown: schedule stays :cancelled, not :delivered"))))
