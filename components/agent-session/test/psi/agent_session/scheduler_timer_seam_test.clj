(ns psi.agent-session.scheduler-timer-seam-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.core :as session]
   [psi.agent-session.dispatch :as dispatch]
   [psi.agent-session.test-support :as test-support]))

(defn- create-session-context
  ([]
   (create-session-context {}))
  ([opts]
   (let [ctx (session/create-context (test-support/safe-context-opts opts))
         sd  (session/new-session-in! ctx nil {})]
     [ctx (:session-id sd)])))

(deftest scheduler-start-timer-uses-injected-time-source-and-delay-runner-test
  (testing "scheduler timer computes delay from injected now-fn and dispatches via injected runner"
    (let [[ctx session-id] (create-session-context {:persist? false})
          now              (java.time.Instant/parse "2026-04-21T17:00:00Z")
          fire-at          (.plusSeconds now 5)
          observed-delay*  (atom nil)
          callback*        (atom nil)
          ctx*             (assoc ctx
                                  :now-fn (fn [] now)
                                  :scheduler-run-after-delay-fn (fn [_ctx delay-ms f]
                                                                  (reset! observed-delay* delay-ms)
                                                                  (reset! callback* f)
                                                                  {:handle :fake}))]
      (dispatch/dispatch! ctx* :scheduler/create
                          {:session-id session-id
                           :schedule-id "sch-1"
                           :label "later"
                           :message "later"
                           :fire-at fire-at}
                          {:origin :core})
      (is (= 5000 @observed-delay*))
      (is (= {:handle :fake} (get @(:scheduler-timers* ctx*) "sch-1")))
      (@callback*)
      (is (= :delivered (get-in @(:state* ctx*) [:agent-session :sessions session-id :data :scheduler :schedules "sch-1" :status])))))

  (testing "scheduler cancel uses injected cancel fn for non-thread handles"
    (let [[ctx session-id] (create-session-context {:persist? false})
          cancelled*       (atom nil)
          ctx*             (assoc ctx
                                  :scheduler-run-after-delay-fn (fn [_ctx _delay-ms _f]
                                                                  {:handle :fake})
                                  :scheduler-cancel-delay-fn (fn [_ctx handle]
                                                               (reset! cancelled* handle)))]
      (dispatch/dispatch! ctx* :scheduler/create
                          {:session-id session-id
                           :schedule-id "sch-1"
                           :label "later"
                           :message "later"
                           :fire-at (.plusSeconds (java.time.Instant/now) 5)}
                          {:origin :core})
      (dispatch/dispatch! ctx* :scheduler/cancel
                          {:session-id session-id
                           :schedule-id "sch-1"}
                          {:origin :core})
      (is (= {:handle :fake} @cancelled*))
      (is (= :cancelled (get-in @(:state* ctx*) [:agent-session :sessions session-id :data :scheduler :schedules "sch-1" :status]))))))
