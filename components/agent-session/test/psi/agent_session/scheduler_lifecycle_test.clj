(ns psi.agent-session.scheduler-lifecycle-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.core :as session]
   [psi.state-kernel.dispatch :as kernel]
   [psi.session-persistence.core :as persist]
   [psi.turn-runtime.core]
   [psi.session-state.state :as ss]
   [psi.agent-session.test-support :as test-support]))

(defn- journal-messages
  [ctx session-id]
  (->> (persist/all-entries-in ctx session-id)
       (filter #(= :message (:kind %)))
       (map #(get-in % [:data :message]))
       vec))

(defn- scheduled-user-messages
  [ctx session-id]
  (->> (journal-messages ctx session-id)
       (filter #(and (= "user" (:role %))
                     (some? (:schedule-id %))))
       vec))

(defn- invoke-scheduler-handler
  "Invoke a scheduler dispatch handler's pure `:fn` directly (handler unit),
  returning its `{:return … :effects …}` result without running the dispatch
  pipeline. Used only for the time-source-stamp-on-effect handler-unit
  assertion, not for the cited live covering tests (which drive real dispatch)."
  [ctx event-type data]
  (let [handler-fn (get-in (kernel/handler-entry event-type) [:fn])]
    (handler-fn ctx data)))

(deftest scheduled-deliver-runs-canonical-prompt-lifecycle-test
  ;; Verifies scheduled delivery runs through the canonical prompt lifecycle and
  ;; stamps the scheduled user message from the runtime scheduler time source.
  (let [delivered-at (java.time.Instant/parse "2099-04-21T18:06:00Z")
        [ctx session-id] (test-support/create-test-session {:persist? false
                                                            :scheduler-time-source (test-support/fixed-scheduler-time-source delivered-at)})]
    (kernel/clear-event-log!)
    (kernel/clear-dispatch-trace!)
    (with-redefs [psi.turn-runtime.core/execute-prepared-request!
                  (fn [_ai-ctx _ctx sid prepared _pq]
                    {:execution-result/turn-id (:prepared-request/id prepared)
                     :execution-result/session-id sid
                     :execution-result/assistant-message {:role "assistant"
                                                          :content [{:type :text :text "scheduled ack"}]
                                                          :stop-reason :stop
                                                          ;; fixed instant (test's delivery time) — no wall-clock,
                                                          ;; matching the surrounding time-control discipline
                                                          :timestamp delivered-at}
                     :execution-result/turn-outcome :turn.outcome/stop
                     :execution-result/tool-calls []
                     :execution-result/stop-reason :stop})]
      (session/dispatch-in! ctx :scheduler/create
                            {:session-id session-id
                             :schedule-id "sch-e2e-1"
                             :kind :message
                             :label "wake-check"
                             :message "check status"
                             :created-at (java.time.Instant/parse "2099-04-21T18:00:00Z")
                             :fire-at (java.time.Instant/parse "2099-04-21T18:05:00Z")
                             :delay-ms 1000}
                            {:origin :core})
      (session/dispatch-in! ctx :scheduler/fired
                            {:session-id session-id
                             :schedule-id "sch-e2e-1"}
                            {:origin :core})
      (let [entries (kernel/event-log-entries)
            user-msg (first (scheduled-user-messages ctx session-id))
            assistant-msg (some #(when (= "assistant" (:role %)) %)
                                (journal-messages ctx session-id))]
        (is (= :delivered (test-support/schedule-status ctx session-id "sch-e2e-1")))
        (is (= :idle (ss/sc-phase-in ctx session-id)))
        (is (= "user" (:role user-msg)))
        (is (= "check status" (get-in user-msg [:content 0 :text])))
        (is (= delivered-at (:timestamp user-msg)))
        (is (= "sch-e2e-1" (:schedule-id user-msg)))
        (is (= "wake-check" (:label user-msg)))
        (is (= "assistant" (:role assistant-msg)))
        (is (some #(= :scheduler/fired (:event-type %)) entries))
        (is (some #(= :scheduler/deliver (:event-type %)) entries))
        (is (some #(= :session/prompt-submit (:event-type %)) entries))
        (is (some #(= :session/prompt-record-response (:event-type %)) entries))
        (is (some #(= :session/prompt-finish (:event-type %)) entries))))))

(deftest busy-session-fire-queues-then-idle-drains-fifo-test
  ;; Cited busy queue + drain-on-idle covering test. Drives the WHOLE sequence
  ;; (fire-while-busy -> queue -> idle -> drain) through the REAL dispatch
  ;; pipeline (design "Drain-on-idle trigger": dispatch :scheduler/drain-queue
  ;; directly), and asserts OBSERVABLE delivered state — the per-schedule
  ;; :delivered status, FIFO drain order (oldest by [fire-at created-at
  ;; schedule-id] first), and the post-drain queue contents — not the shape of
  ;; the handler-returned effect data. The scheduler-time-source stamping of the
  ;; scheduled user message is asserted separately as a handler unit
  ;; (`drain-one-stamps-scheduled-user-message-from-scheduler-time-source-test`).
  (let [scheduler-clock (test-support/atom-scheduler-time-source
                         (java.time.Instant/parse "2099-04-21T18:06:00Z"))
        [ctx session-id] (test-support/create-test-session {:persist? false
                                                            :scheduler-time-source (:time-source scheduler-clock)})]
    (swap! (:state* ctx) (ss/session-update session-id (fn [session] (assoc session :is-streaming true))))
    (doseq [[schedule-id label message created fire]
            [["sch-q-1" "first" "first wake" "2099-04-21T18:00:00Z" "2099-04-21T18:05:00Z"]
             ["sch-q-2" "second" "second wake" "2099-04-21T18:00:01Z" "2099-04-21T18:05:01Z"]]]
      (session/dispatch-in! ctx :scheduler/create
                            {:session-id session-id
                             :schedule-id schedule-id
                             :kind :message
                             :label label
                             :message message
                             :created-at (java.time.Instant/parse created)
                             :fire-at (java.time.Instant/parse fire)
                             :delay-ms 1000}
                            {:origin :core})
      (session/dispatch-in! ctx :scheduler/fired
                            {:session-id session-id
                             :schedule-id schedule-id}
                            {:origin :core}))
    (is (= ["sch-q-1" "sch-q-2"]
           (test-support/schedule-queue ctx session-id))
        "fire-while-busy queues both schedules in FIFO order")

    (swap! (:state* ctx) (ss/session-update session-id (fn [session] (assoc session :is-streaming false))))
    (testing "first idle drain delivers the oldest queued schedule via real dispatch"
      (let [drain-1 (session/dispatch-in! ctx :scheduler/drain-queue
                                          {:session-id session-id}
                                          {:origin :core})]
        (is (= ["sch-q-2"] (test-support/schedule-queue ctx session-id)))
        (is (= "sch-q-1" (:schedule-id (or (:return drain-1) drain-1))))
        (is (= :delivered (test-support/schedule-status ctx session-id "sch-q-1")))
        (is (= :queued (test-support/schedule-status ctx session-id "sch-q-2")))))

    (testing "second idle drain delivers the remaining queued schedule via real dispatch"
      (let [drain-2 (session/dispatch-in! ctx :scheduler/drain-queue
                                          {:session-id session-id}
                                          {:origin :core})]
        (is (= [] (test-support/schedule-queue ctx session-id)))
        (is (= "sch-q-2" (:schedule-id (or (:return drain-2) drain-2))))
        (is (= :delivered (test-support/schedule-status ctx session-id "sch-q-2")))))))

(deftest drain-one-stamps-scheduled-user-message-from-scheduler-time-source-test
  ;; Handler-unit assertion (split out of busy-session-...-drains-fifo per
  ;; sufficient-coverage clause 3: keep the time-source-stamp-on-effect check as
  ;; a clearly-named handler unit, not as part of the cited live covering test).
  ;; Asserts the :scheduler/drain-queue handler stamps the scheduled user message
  ;; it emits from the runtime scheduler time source when :delivered-at is omitted.
  (let [delivered-at (java.time.Instant/parse "2099-04-21T18:06:00Z")
        scheduler-clock (test-support/atom-scheduler-time-source delivered-at)
        [ctx session-id] (test-support/create-test-session {:persist? false
                                                            :scheduler-time-source (:time-source scheduler-clock)})]
    (swap! (:state* ctx) (ss/session-update session-id (fn [session] (assoc session :is-streaming true))))
    (session/dispatch-in! ctx :scheduler/create
                          {:session-id session-id
                           :schedule-id "sch-q-1"
                           :kind :message
                           :label "first"
                           :message "first wake"
                           :created-at (java.time.Instant/parse "2099-04-21T18:00:00Z")
                           :fire-at (java.time.Instant/parse "2099-04-21T18:05:00Z")
                           :delay-ms 1000}
                          {:origin :core})
    (session/dispatch-in! ctx :scheduler/fired
                          {:session-id session-id :schedule-id "sch-q-1"}
                          {:origin :core})
    (swap! (:state* ctx) (ss/session-update session-id (fn [session] (assoc session :is-streaming false))))
    (let [drain (invoke-scheduler-handler ctx :scheduler/drain-queue {:session-id session-id})]
      (is (= "sch-q-1" (get-in drain [:return :schedule-id])))
      (is (= delivered-at (-> drain :effects first :event-data :user-msg :timestamp))
          "scheduled user message stamped from the scheduler time source"))))

(deftest cancel-pending-and-queued-schedules-test
  (let [[ctx session-id] (test-support/create-test-session {:persist? false})]
    (session/dispatch-in! ctx :scheduler/create
                          {:session-id session-id
                           :schedule-id "sch-cancel-pending"
                           :kind :message
                           :message "pending"
                           :created-at (java.time.Instant/parse "2099-04-21T18:00:00Z")
                           :fire-at (java.time.Instant/parse "2099-04-21T18:05:00Z")
                           :delay-ms 1000}
                          {:origin :core})
    (session/dispatch-in! ctx :scheduler/cancel
                          {:session-id session-id
                           :schedule-id "sch-cancel-pending"}
                          {:origin :core})
    (is (= :cancelled (test-support/schedule-status ctx session-id "sch-cancel-pending")))

    (swap! (:state* ctx) (ss/session-update session-id (fn [session] (assoc session :is-streaming true))))
    (session/dispatch-in! ctx :scheduler/create
                          {:session-id session-id
                           :schedule-id "sch-cancel-queued"
                           :kind :message
                           :message "queued"
                           :created-at (java.time.Instant/parse "2099-04-21T18:00:01Z")
                           :fire-at (java.time.Instant/parse "2099-04-21T18:05:01Z")
                           :delay-ms 1000}
                          {:origin :core})
    (session/dispatch-in! ctx :scheduler/fired
                          {:session-id session-id
                           :schedule-id "sch-cancel-queued"}
                          {:origin :core})
    (session/dispatch-in! ctx :scheduler/cancel
                          {:session-id session-id
                           :schedule-id "sch-cancel-queued"}
                          {:origin :core})
    (is (= :cancelled (test-support/schedule-status ctx session-id "sch-cancel-queued")))
    (is (= [] (test-support/schedule-queue ctx session-id)))))
