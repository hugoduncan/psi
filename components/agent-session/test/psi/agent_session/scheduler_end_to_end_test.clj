(ns psi.agent-session.scheduler-end-to-end-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.core :as session]
   [psi.session-state.state :as ss]
   [psi.agent-session.test-support :as test-support]))

(defn- create-session-context
  ([]
   (create-session-context {}))
  ([opts]
   (let [ctx (session/create-context (test-support/safe-context-opts opts))
         sd  (session/new-session-in! ctx nil {})]
     [ctx (:session-id sd)])))

(deftest scheduler-fired-end-to-end-delivers-when-idle-test
  (testing "create -> fired -> deliver appends scheduled user message and returns to idle"
    (let [[ctx session-id] (create-session-context {:persist? false})
          _                (session/dispatch-in! ctx :scheduler/create
                                                 {:session-id session-id
                                                  :schedule-id "sch-1"
                                                  :kind :message
                                                  :label "check-build"
                                                  :message "check build"
                                                  :created-at (java.time.Instant/parse "2099-04-21T18:00:00Z")
                                                  :fire-at (java.time.Instant/parse "2099-04-21T18:05:00Z")}
                                                 {:origin :core})
          _                (session/dispatch-in! ctx :scheduler/fired
                                                 {:session-id session-id
                                                  :schedule-id "sch-1"}
                                                 {:origin :core})
          journal          (ss/get-state-value-in ctx (ss/state-path :journal session-id))
          scheduled-msg    (some->> journal
                                    (keep #(get-in % [:data :message]))
                                    (some (fn [message]
                                            (when (and (= "user" (:role message))
                                                       (= :scheduled (:source message))
                                                       (= "sch-1" (:schedule-id message)))
                                              message))))]
      (is (some? scheduled-msg))
      (is (= :delivered (get-in @(:state* ctx) [:agent-session :sessions session-id :data :scheduler :schedules "sch-1" :status])))
      (is (= [] (get-in @(:state* ctx) [:agent-session :sessions session-id :data :scheduler :queue])))
      (is (= :idle (ss/sc-phase-in ctx session-id))))))

;; --- 201 verification: message-kind live round trip via the timer seam ---
;; Unlike the test above (which dispatches :scheduler/fired directly), this one
;; crosses the real timer boundary: it captures the timer callback the
;; :scheduler/start-timer effect would schedule, invokes it (no wall-clock
;; sleep), and asserts the delivered prompt with scheduled provenance lands in
;; the ORIGIN session.

(deftest scheduler-message-kind-fires-via-timer-seam-and-delivers-to-origin-test
  (testing "create message-kind -> captured timer callback fires -> delivered prompt with scheduled provenance in origin session"
    (let [now              (java.time.Instant/parse "2026-04-21T18:00:00Z")
          [ctx session-id] (create-session-context
                            {:persist? false
                             :scheduler-time-source (test-support/fixed-scheduler-time-source now)})
          callback*        (atom nil)
          ctx*             (assoc ctx
                                  :scheduler-run-after-delay-fn
                                  (fn [_ctx _delay-ms f]
                                    (reset! callback* f)
                                    {:handle :captured}))]
      (session/dispatch-in! ctx* :scheduler/create
                            {:session-id session-id
                             :schedule-id "sch-msg"
                             :kind :message
                             :label "check-build"
                             :message "check build"
                             :created-at now
                             :fire-at (.plusMillis now 5000)}
                            {:origin :core})
      (testing "before the timer fires, schedule is pending and nothing delivered"
        (is (= :pending (get-in @(:state* ctx*)
                                [:agent-session :sessions session-id
                                 :data :scheduler :schedules "sch-msg" :status])))
        (is (some? @callback*) "timer callback captured via the seam"))
      ;; fire the timer by invoking the captured callback (no Thread/sleep)
      (@callback*)
      (let [journal       (ss/get-state-value-in ctx* (ss/state-path :journal session-id))
            scheduled-msg (some->> journal
                                   (keep #(get-in % [:data :message]))
                                   (some (fn [message]
                                           (when (and (= "user" (:role message))
                                                      (= :scheduled (:source message))
                                                      (= "sch-msg" (:schedule-id message)))
                                             message))))]
        (is (some? scheduled-msg)
            "scheduled user message delivered into origin session")
        (is (= :delivered (get-in @(:state* ctx*)
                                  [:agent-session :sessions session-id
                                   :data :scheduler :schedules "sch-msg" :status])))
        (is (= [] (get-in @(:state* ctx*)
                          [:agent-session :sessions session-id :data :scheduler :queue])))))))
