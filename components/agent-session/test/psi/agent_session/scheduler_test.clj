(ns psi.agent-session.scheduler-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.scheduler :as scheduler]
   [psi.agent-session.test-support :refer [instant]]))

(deftest empty-state-test
  (is (= {:schedules {} :queue []}
         (scheduler/empty-state))))

(deftest create-and-list-schedule-test
  (let [{state :state schedule :schedule}
        (scheduler/create-schedule
         (scheduler/empty-state)
         {:schedule-id "sch-1"
          :kind :message
          :label "check-build"
          :message "check build status"
          :created-at (instant "2026-04-21T18:00:00Z")
          :fire-at (instant "2026-04-21T18:05:00Z")
          :session-id "sid-1"})]
    (is (= "sch-1" (:schedule-id schedule)))
    (is (= :pending (:status schedule)))
    (is (= 1 (scheduler/schedule-count state)))
    (is (= 1 (scheduler/pending-count state)))
    (is (= ["sch-1"] (mapv :schedule-id (scheduler/list-schedules state [:pending]))))))

(deftest create-schedule-requires-explicit-kind-test
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"kind is invalid"
                        (scheduler/create-schedule
                         (scheduler/empty-state)
                         {:schedule-id "sch-missing-kind"
                          :message "wake"
                          :created-at (instant "2026-04-21T18:00:00Z")
                          :fire-at (instant "2026-04-21T18:05:00Z")
                          :session-id "sid-1"}))))

(deftest validate-delay-ms-test
  (testing "accepts inclusive bounds"
    (is (= scheduler/min-delay-ms (scheduler/validate-delay-ms! scheduler/min-delay-ms)))
    (is (= scheduler/max-delay-ms (scheduler/validate-delay-ms! scheduler/max-delay-ms))))

  (testing "rejects too small"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"minimum"
                          (scheduler/validate-delay-ms! 999))))

  (testing "rejects too large"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"maximum"
                          (scheduler/validate-delay-ms! (inc scheduler/max-delay-ms))))))

(deftest fire-schedule-test
  (let [{state :state}
        (scheduler/create-schedule
         nil
         {:schedule-id "sch-1"
          :kind :message
          :message "wake up"
          :created-at (instant "2026-04-21T18:00:00Z")
          :fire-at (instant "2026-04-21T18:05:00Z")
          :session-id "sid-1"})]
    (testing "idle session: returns the :deliver action and leaves the schedule :pending"
      ;; Pure `fire-schedule` returns the :deliver action without mutating
      ;; status; the schedule stays :pending until the delivery handler runs.
      (let [{state' :state action :action schedule :schedule}
            (scheduler/fire-schedule state {:is-streaming false :is-compacting false} "sch-1")]
        (is (= :deliver action))
        (is (= :pending (:status schedule)))
        (is (= [] (:queue state')))))

    (testing "busy session queues schedule"
      (let [{state' :state action :action schedule :schedule}
            (scheduler/fire-schedule state {:is-streaming true :is-compacting false} "sch-1")]
        (is (= :queue action))
        (is (= :queued (:status schedule)))
        (is (= ["sch-1"] (:queue state')))
        (is (= :queued (:status (scheduler/get-schedule state' "sch-1"))))))))

(deftest deliver-and-cancel-test
  (let [{state0 :state}
        (scheduler/create-schedule
         nil
         {:schedule-id "sch-1"
          :kind :message
          :message "wake up"
          :created-at (instant "2026-04-21T18:00:00Z")
          :fire-at (instant "2026-04-21T18:05:00Z")
          :session-id "sid-1"})
        state1 (:state (scheduler/fire-schedule state0 {:is-streaming true :is-compacting false} "sch-1"))]
    (testing "deliver moves queued schedule to delivered and removes it from queue"
      (let [{state2 :state schedule :schedule} (scheduler/deliver-schedule state1 "sch-1")]
        (is (= :delivered (:status schedule)))
        (is (= [] (:queue state2)))
        (is (= :delivered (:status (scheduler/get-schedule state2 "sch-1"))))))

    (testing "cancel marks queued schedule cancelled and removes it from queue"
      (let [{state2 :state schedule :schedule} (scheduler/cancel-schedule state1 "sch-1")]
        (is (= :cancelled (:status schedule)))
        (is (= [] (:queue state2)))
        (is (= :cancelled (:status (scheduler/get-schedule state2 "sch-1"))))))))

(deftest drain-one-test
  (let [{state0 :state} (scheduler/create-schedule nil {:schedule-id "sch-a"
                                                        :kind :message
                                                        :message "a"
                                                        :created-at (instant "2026-04-21T18:00:00Z")
                                                        :fire-at (instant "2026-04-21T18:05:00Z")
                                                        :session-id "sid-1"})
        {state1 :state} (scheduler/create-schedule state0 {:schedule-id "sch-b"
                                                           :kind :message
                                                           :message "b"
                                                           :created-at (instant "2026-04-21T18:00:01Z")
                                                           :fire-at (instant "2026-04-21T18:05:01Z")
                                                           :session-id "sid-1"})
        state2 (:state (scheduler/fire-schedule state1 {:is-streaming true :is-compacting false} "sch-a"))
        state3 (:state (scheduler/fire-schedule state2 {:is-streaming true :is-compacting false} "sch-b"))]
    (testing "drain-one is FIFO by queue order when session is idle"
      (let [{state4 :state drained? :drained? schedule :schedule} (scheduler/drain-one state3 {:is-streaming false :is-compacting false})]
        (is (true? drained?))
        (is (= "sch-a" (:schedule-id schedule)))
        (is (= ["sch-b"] (:queue state4)))
        (is (= :delivered (:status (scheduler/get-schedule state4 "sch-a"))))))

    (testing "drain-one is a no-op when session is busy"
      (let [{state4 :state drained? :drained? reason :reason} (scheduler/drain-one state3 {:is-streaming true :is-compacting false})]
        (is (false? drained?))
        (is (= :session-busy reason))
        (is (= (:queue state3) (:queue state4)))))))

;; --- 201 verification: pure-model guards + ordering (verified-correct) ---

(deftest create-schedule-rejects-duplicate-id-test
  (let [{state :state}
        (scheduler/create-schedule
         (scheduler/empty-state)
         {:schedule-id "sch-dup"
          :kind :message
          :message "first"
          :created-at (instant "2026-04-21T18:00:00Z")
          :fire-at (instant "2026-04-21T18:05:00Z")
          :session-id "sid-1"})]
    (testing "a second create with the same id throws and leaves state unchanged"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"schedule-id already exists"
           (scheduler/create-schedule
            state
            {:schedule-id "sch-dup"
             :kind :message
             :message "second"
             :created-at (instant "2026-04-21T18:01:00Z")
             :fire-at (instant "2026-04-21T18:06:00Z")
             :session-id "sid-1"})))
      (is (= 1 (scheduler/schedule-count state))))))

(deftest fire-schedule-rejects-non-pending-status-test
  (let [{state0 :state}
        (scheduler/create-schedule
         (scheduler/empty-state)
         {:schedule-id "sch-1"
          :kind :message
          :message "wake"
          :created-at (instant "2026-04-21T18:00:00Z")
          :fire-at (instant "2026-04-21T18:05:00Z")
          :session-id "sid-1"})
        idle  {:is-streaming false :is-compacting false}
        busy  {:is-streaming true :is-compacting false}]
    (testing "firing a delivered schedule throws (terminal guard)"
      (let [delivered (:state (scheduler/deliver-schedule state0 "sch-1"))]
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"only pending schedules can fire"
             (scheduler/fire-schedule delivered idle "sch-1")))))
    (testing "firing an already-queued schedule throws (non-pending guard)"
      (let [queued (:state (scheduler/fire-schedule state0 busy "sch-1"))]
        (is (= :queued (:status (scheduler/get-schedule queued "sch-1"))))
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"only pending schedules can fire"
             (scheduler/fire-schedule queued busy "sch-1")))))))

(deftest cancel-schedule-rejects-terminal-status-test
  (let [{state0 :state}
        (scheduler/create-schedule
         (scheduler/empty-state)
         {:schedule-id "sch-1"
          :kind :message
          :message "wake"
          :created-at (instant "2026-04-21T18:00:00Z")
          :fire-at (instant "2026-04-21T18:05:00Z")
          :session-id "sid-1"})
        delivered (:state (scheduler/deliver-schedule state0 "sch-1"))]
    (testing "cancelling a delivered (terminal) schedule throws"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"schedule is not cancellable"
           (scheduler/cancel-schedule delivered "sch-1"))))
    (testing "cancelling an already-cancelled schedule throws"
      (let [cancelled (:state (scheduler/cancel-schedule
                               (:state (scheduler/create-schedule
                                        (scheduler/empty-state)
                                        {:schedule-id "sch-2"
                                         :kind :message
                                         :message "wake"
                                         :created-at (instant "2026-04-21T18:00:00Z")
                                         :fire-at (instant "2026-04-21T18:05:00Z")
                                         :session-id "sid-1"}))
                               "sch-2"))]
        (is (= :cancelled (:status (scheduler/get-schedule cancelled "sch-2"))))
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"schedule is not cancellable"
             (scheduler/cancel-schedule cancelled "sch-2")))))))

(deftest fail-schedule-records-failure-detail-and-dequeues-test
  (testing "fail-schedule from :queued records detail and removes id from the queue"
    (let [{q0 :state} (scheduler/create-schedule
                       (scheduler/empty-state)
                       {:schedule-id "sch-q"
                        :kind :message
                        :message "wake"
                        :created-at (instant "2026-04-21T18:00:00Z")
                        :fire-at (instant "2026-04-21T18:05:00Z")
                        :session-id "sid-1"})
          q1 (:state (scheduler/fire-schedule q0 {:is-streaming true :is-compacting false} "sch-q"))
          {q2 :state failed :schedule}
          (scheduler/fail-schedule q1 "sch-q"
                                   {:delivery-phase :prompt-submit
                                    :error-summary {:message "boom"}
                                    :created-session-id "created-sid"})]
      (is (= :failed (:status failed)))
      (is (= :prompt-submit (:delivery-phase failed)))
      (is (= {:message "boom"} (:error-summary failed)))
      (is (= "created-sid" (:created-session-id failed)))
      (is (= [] (:queue q2)))
      (is (= :failed (:status (scheduler/get-schedule q2 "sch-q"))))))
  (testing "fail-schedule guards terminal statuses (cannot fail a cancelled schedule)"
    (let [{s0 :state} (scheduler/create-schedule
                       (scheduler/empty-state)
                       {:schedule-id "sch-fail"
                        :kind :session
                        :message "run later"
                        :created-at (instant "2026-04-21T18:00:00Z")
                        :fire-at (instant "2026-04-21T18:05:00Z")
                        :session-id "sid-1"})
          ;; session-kind fire returns the :deliver action and leaves status
          ;; :pending (pure fire-schedule does not mutate session-kind status),
          ;; so the schedule is still cancellable here.
          s1 (:state (scheduler/fire-schedule s0 {:is-streaming false :is-compacting false}
                                              "sch-fail"))
          cancelled (:state (scheduler/cancel-schedule s1 "sch-fail"))]
      (is (= :cancelled (:status (scheduler/get-schedule cancelled "sch-fail"))))
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"schedule is not fail-able"
           (scheduler/fail-schedule cancelled "sch-fail" {:delivery-phase :prompt-submit}))))))

(deftest drain-one-orders-by-fire-at-not-queue-insertion-order-test
  ;; Queue the later-firing schedule FIRST so queue-insertion order and
  ;; fire-at order disagree; drain-one must still pick the earliest fire-at,
  ;; demonstrating it sorts by [fire-at created-at schedule-id], not FIFO.
  (let [busy {:is-streaming true :is-compacting false}
        idle {:is-streaming false :is-compacting false}
        {s0 :state} (scheduler/create-schedule
                     (scheduler/empty-state)
                     {:schedule-id "sch-late"
                      :kind :message
                      :message "late"
                      :created-at (instant "2026-04-21T18:00:00Z")
                      :fire-at (instant "2026-04-21T18:10:00Z")
                      :session-id "sid-1"})
        {s1 :state} (scheduler/create-schedule
                     s0
                     {:schedule-id "sch-early"
                      :kind :message
                      :message "early"
                      :created-at (instant "2026-04-21T18:00:01Z")
                      :fire-at (instant "2026-04-21T18:05:00Z")
                      :session-id "sid-1"})
        ;; fire the late one first, then the early one → queue = [late early]
        s2 (:state (scheduler/fire-schedule s1 busy "sch-late"))
        s3 (:state (scheduler/fire-schedule s2 busy "sch-early"))]
    (is (= ["sch-late" "sch-early"] (:queue s3))
        "queue insertion order is late-then-early")
    (testing "drain-one delivers the earliest fire-at (sch-early) despite later insertion"
      (let [{drained? :drained? schedule :schedule state4 :state}
            (scheduler/drain-one s3 idle)]
        (is (true? drained?))
        (is (= "sch-early" (:schedule-id schedule)))
        (is (= ["sch-late"] (:queue state4)))
        (is (= :delivered (:status (scheduler/get-schedule state4 "sch-early"))))))))
