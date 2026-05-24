(ns psi.agent-session.scheduler-timer-seam-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.core :as session]
   [psi.agent-session.test-support :as test-support]))

(defn- create-session-context
  ([]
   (create-session-context {}))
  ([opts]
   (let [ctx (session/create-context (test-support/safe-context-opts opts))
         sd  (session/new-session-in! ctx nil {})]
     [ctx (:session-id sd)])))

(deftest scheduler-start-timer-uses-injected-time-source-and-delay-runner-test
  (testing "scheduler timer computes delay from injected scheduler time source and dispatches via injected runner"
    (let [now              (java.time.Instant/parse "2026-04-21T17:00:00Z")
          [ctx session-id] (create-session-context {:persist? false
                                                    :scheduler-time-source (test-support/fixed-scheduler-time-source now)})
          fire-at          (.plusMillis now 5000)
          observed-delay*  (atom nil)
          callback*        (atom nil)
          ctx*             (assoc ctx
                                  :scheduler-run-after-delay-fn (fn [_ctx delay-ms f]
                                                                  (reset! observed-delay* delay-ms)
                                                                  (reset! callback* f)
                                                                  {:handle :fake}))]
      (session/dispatch-in! ctx* :scheduler/create
                            {:session-id session-id
                             :schedule-id "sch-1"
                             :label "later"
                             :message "later"
                             :created-at now
                             :fire-at fire-at}
                            {:origin :core})
      (is (= 5000 @observed-delay*))
      (is (= {:handle :fake} (get @(:scheduler-timers* ctx*) "sch-1")))
      (@callback*)
      (is (= :delivered (get-in @(:state* ctx*) [:agent-session :sessions session-id :data :scheduler :schedules "sch-1" :status])))))

  (testing "scheduler cancel uses injected cancel fn for non-thread handles"
    (let [now              (java.time.Instant/parse "2026-04-21T17:10:00Z")
          [ctx session-id] (create-session-context {:persist? false
                                                    :scheduler-time-source (test-support/fixed-scheduler-time-source now)})
          cancelled*       (atom nil)
          ctx*             (assoc ctx
                                  :scheduler-run-after-delay-fn (fn [_ctx _delay-ms _f]
                                                                  {:handle :fake})
                                  :scheduler-cancel-delay-fn (fn [_ctx handle]
                                                               (reset! cancelled* handle)))]
      (session/dispatch-in! ctx* :scheduler/create
                            {:session-id session-id
                             :schedule-id "sch-1"
                             :label "later"
                             :message "later"
                             :created-at now
                             :fire-at (.plusMillis now 5000)}
                            {:origin :core})
      (session/dispatch-in! ctx* :scheduler/cancel
                            {:session-id session-id
                             :schedule-id "sch-1"}
                            {:origin :core})
      (is (= {:handle :fake} @cancelled*))
      (is (= :cancelled (get-in @(:state* ctx*) [:agent-session :sessions session-id :data :scheduler :schedules "sch-1" :status]))))))

(deftest scheduler-cancelled-default-delay-thread-exits-without-uncaught-interrupted-exception-test
  (testing "cancelling the default delayed scheduler thread interrupts sleep without leaking an uncaught exception"
    (let [now              (java.time.Instant/parse "2026-04-21T17:20:00Z")
          [ctx session-id] (create-session-context {:persist? false
                                                    :scheduler-time-source (test-support/fixed-scheduler-time-source now)})
          started-thread*  (atom nil)
          uncaught*        (atom nil)
          ctx*             (assoc ctx
                                  :daemon-thread-fn
                                  (fn [f]
                                    (let [thread (Thread. ^Runnable f)]
                                      (.setDaemon thread true)
                                      (.setUncaughtExceptionHandler
                                       thread
                                       (reify Thread$UncaughtExceptionHandler
                                         (uncaughtException [_ _ ex]
                                           (reset! uncaught* ex))))
                                      (reset! started-thread* thread)
                                      (.start thread)
                                      thread)))]
      (session/dispatch-in! ctx* :scheduler/create
                            {:session-id session-id
                             :schedule-id "sch-1"
                             :label "later"
                             :message "later"
                             :created-at now
                             :fire-at (.plusSeconds now 5)}
                            {:origin :core})
      (dotimes [_ 50]
        (when-not @started-thread*
          (Thread/sleep 10)))
      (is (some? @started-thread*))
      (session/dispatch-in! ctx* :scheduler/cancel
                            {:session-id session-id
                             :schedule-id "sch-1"}
                            {:origin :core})
      (.join ^Thread @started-thread* 500)
      (is (false? (.isAlive ^Thread @started-thread*)))
      (is (nil? @uncaught*))
      (is (nil? (get @(:scheduler-timers* ctx*) "sch-1")))
      (is (= :cancelled (get-in @(:state* ctx*) [:agent-session :sessions session-id :data :scheduler :schedules "sch-1" :status]))))))
