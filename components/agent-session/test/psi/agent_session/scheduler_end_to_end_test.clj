(ns psi.agent-session.scheduler-end-to-end-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.core :as session]
   [psi.session-state.state :as ss]
   [psi.agent-session.test-support :as test-support]))

(deftest scheduler-fired-end-to-end-delivers-when-idle-test
  (testing "create -> fired -> deliver appends scheduled user message and returns to idle"
    (let [[ctx session-id] (test-support/create-test-session {:persist? false})
          _                (session/dispatch-in! ctx :scheduler/create
                                                 {:session-id session-id
                                                  :schedule-id "sch-1"
                                                  :kind :message
                                                  :label "check-build"
                                                  :message "check build"
                                                  :created-at (test-support/instant "2099-04-21T18:00:00Z")
                                                  :fire-at (test-support/instant "2099-04-21T18:05:00Z")}
                                                 {:origin :core})
          _                (session/dispatch-in! ctx :scheduler/fired
                                                 {:session-id session-id
                                                  :schedule-id "sch-1"}
                                                 {:origin :core})
          scheduled-msg    (test-support/scheduled-message-by-id ctx session-id "sch-1")]
      (is (some? scheduled-msg))
      (is (= :delivered (get-in (ss/get-session-data-in ctx session-id) [:scheduler :schedules "sch-1" :status])))
      (is (= [] (get-in (ss/get-session-data-in ctx session-id) [:scheduler :queue])))
      (is (= :idle (ss/sc-phase-in ctx session-id))))))

;; --- 201 verification: message-kind live round trip via the timer seam ---
;; Unlike the test above (which dispatches :scheduler/fired directly), this one
;; crosses the real timer boundary: it captures the timer callback the
;; :scheduler/start-timer effect would schedule, invokes it (no wall-clock
;; sleep), and asserts the delivered prompt with scheduled provenance lands in
;; the ORIGIN session.

(deftest scheduler-message-kind-fires-via-timer-seam-and-delivers-to-origin-test
  (testing "create message-kind -> captured timer callback fires -> delivered prompt with scheduled provenance in origin session"
    (let [now              (test-support/instant "2026-04-21T18:00:00Z")
          [ctx session-id] (test-support/create-test-session
                            {:persist? false
                             :scheduler-time-source (test-support/fixed-scheduler-time-source now)})
          [capture* callback*] (test-support/capturing-delay-fn)
          ctx*             (assoc ctx :scheduler-run-after-delay-fn capture*)]
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
        (is (= :pending (get-in (ss/get-session-data-in ctx* session-id)
                                [:scheduler :schedules "sch-msg" :status])))
        (is (some? @callback*) "timer callback captured via the seam"))
      ;; fire the timer by invoking the captured callback (no Thread/sleep)
      ((:f @callback*))
      (let [scheduled-msg (test-support/scheduled-message-by-id ctx* session-id "sch-msg")]
        (is (some? scheduled-msg)
            "scheduled user message delivered into origin session")
        (is (= :delivered (get-in (ss/get-session-data-in ctx* session-id)
                                  [:scheduler :schedules "sch-msg" :status])))
        (is (= [] (get-in (ss/get-session-data-in ctx* session-id)
                          [:scheduler :queue])))))))

;; --- 201 verification: session-kind live round trip via the timer seam ---
;; session-kind always delivers (origin idle state irrelevant): the captured
;; timer callback fires -> :scheduler/deliver creates a FRESH TOP-LEVEL session
;; in the origin worktree/context and submits the scheduled prompt into it.
;; Asserts the new session exists with scheduler provenance, the schedule records
;; :created-session-id + :delivery-phase :prompt-submit, and the origin session
;; is NOT switched away from.

(deftest scheduler-session-kind-fires-via-timer-seam-and-creates-top-level-session-test
  (testing "create session-kind -> captured timer callback fires -> fresh top-level session created + prompt submitted; created-session-id/delivery-phase recorded"
    (let [now              (test-support/instant "2026-04-21T18:00:00Z")
          [ctx session-id] (test-support/create-test-session
                            {:persist? false
                             :scheduler-time-source (test-support/fixed-scheduler-time-source now)})
          [capture* callback*] (test-support/capturing-delay-fn)
          ;; AI-execution boundary driven through the injectable ctx seam
          ;; (:execute-prepared-request-fn) rather than a with-redefs stub of
          ;; turn/execute-prepared-request! — the effect reads this seam from
          ;; ctx (dispatch_effects.clj:154), so overriding it here gives the same
          ;; shaped execution-result without redefining the boundary var.
          execute-prepared-request-fn
          (fn [_ai-ctx _ctx sid prepared _pq]
            ;; canonical stub shape via the shared test-support builder; the
            ;; fixed instant (test's fire time) keeps it wall-clock-free,
            ;; matching the surrounding time-control discipline.
            (test-support/stub-execution-result
             {:sid sid :prepared prepared :text "scheduled ack"
              :timestamp (.plusMillis now 5000)}))
          ctx*             (assoc ctx
                                  :scheduler-run-after-delay-fn capture*
                                  :execute-prepared-request-fn execute-prepared-request-fn)]
      ;; origin session busy on purpose: session-kind must deliver regardless.
      (swap! (:state* ctx*)
             (ss/session-update session-id (fn [sd] (assoc sd :is-streaming true))))
      (session/dispatch-in! ctx* :scheduler/create
                            {:session-id session-id
                             :schedule-id "sch-sess"
                             :kind :session
                             :label "morning-review"
                             :message "review overnight changes"
                             :session-config {:session-name "Morning review"}
                             :created-at now
                             :fire-at (.plusMillis now 5000)}
                            {:origin :core})
      (is (some? @callback*) "timer callback captured via the seam")
      (let [sessions-before (set (map :session-id (ss/list-context-sessions-in ctx*)))]
        ;; fire the timer (no Thread/sleep)
        ((:f @callback*))
        (let [schedule    (get-in (ss/get-session-data-in ctx* session-id)
                                  [:scheduler :schedules "sch-sess"])
              created-id  (:created-session-id schedule)
              created-sd  (when created-id (ss/get-session-data-in ctx* created-id))
              sessions-after (set (map :session-id (ss/list-context-sessions-in ctx*)))]
          (is (= :delivered (:status schedule)))
          (is (= :prompt-submit (:delivery-phase schedule)))
          (is (some? created-id) "schedule records the created-session-id")
          (is (not (contains? sessions-before created-id))
              "created session is fresh (not the origin)")
          (is (contains? sessions-after created-id)
              "created top-level session is present in the context")
          (is (not= session-id created-id) "created session is a new top-level session")
          ;; scheduler provenance recorded on the created session
          (is (= session-id (:scheduled-origin-session-id created-sd)))
          (is (= "sch-sess" (:scheduled-from-schedule-id created-sd)))
          (is (= "morning-review" (:scheduled-from-label created-sd)))
          ;; origin not switched away from: origin still exists, schedule done
          (is (contains? sessions-after session-id)))))))
