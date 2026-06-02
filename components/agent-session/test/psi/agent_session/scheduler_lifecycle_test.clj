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
  [ctx event-type data]
  (let [handler-fn (get-in (kernel/handler-entry event-type) [:fn])]
    (handler-fn ctx data)))

(defn- apply-root-state-update!
  [ctx result]
  (when-let [f (:root-state-update result)]
    (swap! (:state* ctx) f))
  result)

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
                                                          :timestamp (java.time.Instant/now)}
                     :execution-result/turn-outcome :turn.outcome/stop
                     :execution-result/tool-calls []
                     :execution-result/stop-reason :stop})]
      (session/dispatch-in! ctx :scheduler/create
                            {:session-id session-id
                             :schedule-id "sch-e2e-1"
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
        (is (= :delivered (get-in (ss/get-session-data-in ctx session-id) [:scheduler :schedules "sch-e2e-1" :status])))
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
  ;; Verifies queued scheduled deliveries drain FIFO and use the runtime scheduler
  ;; time source for scheduled user-message timestamps when :delivered-at is omitted.
  (let [delivered-at-1 (java.time.Instant/parse "2099-04-21T18:06:00Z")
        delivered-at-2 (java.time.Instant/parse "2099-04-21T18:07:00Z")
        scheduler-clock (test-support/atom-scheduler-time-source delivered-at-1)
        ;; AI-execution boundary driven through the injectable ctx seam
        ;; (:execute-prepared-request-fn) rather than a with-redefs stub of
        ;; turn/execute-prepared-request! — the effect reads this seam from ctx
        ;; (dispatch_effects.clj:154), so overriding it here gives the same
        ;; shaped execution-result without redefining the boundary var (mirrors
        ;; the e2e session-kind test's seam usage).
        execute-prepared-request-fn
        (fn [_ai-ctx _ctx sid prepared _pq]
          {:execution-result/turn-id (:prepared-request/id prepared)
           :execution-result/session-id sid
           :execution-result/assistant-message {:role "assistant"
                                                :content [{:type :text :text "scheduled ack"}]
                                                :stop-reason :stop
                                                :timestamp (java.time.Instant/now)}
           :execution-result/turn-outcome :turn.outcome/stop
           :execution-result/tool-calls []
           :execution-result/stop-reason :stop})
        [ctx session-id] (test-support/create-test-session {:persist? false
                                                            :scheduler-time-source (:time-source scheduler-clock)})
        ctx (assoc ctx :execute-prepared-request-fn execute-prepared-request-fn)]
    (swap! (:state* ctx) (ss/session-update session-id (fn [session] (assoc session :is-streaming true))))
    (doseq [[schedule-id label message created fire]
            [["sch-q-1" "first" "first wake" "2099-04-21T18:00:00Z" "2099-04-21T18:05:00Z"]
             ["sch-q-2" "second" "second wake" "2099-04-21T18:00:01Z" "2099-04-21T18:05:01Z"]]]
      (session/dispatch-in! ctx :scheduler/create
                            {:session-id session-id
                             :schedule-id schedule-id
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
           (get-in (ss/get-session-data-in ctx session-id) [:scheduler :queue])))

    (swap! (:state* ctx) (ss/session-update session-id (fn [session] (assoc session :is-streaming false))))
    (testing "drain handler shapes scheduled user message timestamp from scheduler time source"
      (let [drain-1 (invoke-scheduler-handler ctx :scheduler/drain-queue {:session-id session-id})]
        (apply-root-state-update! ctx drain-1)
        (is (= ["sch-q-2"] (get-in (ss/get-session-data-in ctx session-id) [:scheduler :queue])))
        (is (= "sch-q-1" (get-in drain-1 [:return :schedule-id])))
        (is (= :delivered (get-in (ss/get-session-data-in ctx session-id) [:scheduler :schedules "sch-q-1" :status])))
        (is (= :queued (get-in (ss/get-session-data-in ctx session-id) [:scheduler :schedules "sch-q-2" :status])))
        (is (= delivered-at-1 (-> drain-1 :effects first :event-data :user-msg :timestamp)))))

    (test-support/set-scheduler-instant! (:instant* scheduler-clock) delivered-at-2)
    (let [drain-2 (invoke-scheduler-handler ctx :scheduler/drain-queue {:session-id session-id})]
      (apply-root-state-update! ctx drain-2)
      (is (= [] (get-in (ss/get-session-data-in ctx session-id) [:scheduler :queue])))
      (is (= "sch-q-2" (get-in drain-2 [:return :schedule-id])))
      (is (= :delivered (get-in (ss/get-session-data-in ctx session-id) [:scheduler :schedules "sch-q-2" :status])))
      (is (= delivered-at-2 (-> drain-2 :effects first :event-data :user-msg :timestamp))))))

(deftest cancel-pending-and-queued-schedules-test
  (let [[ctx session-id] (test-support/create-test-session {:persist? false})]
    (session/dispatch-in! ctx :scheduler/create
                          {:session-id session-id
                           :schedule-id "sch-cancel-pending"
                           :message "pending"
                           :created-at (java.time.Instant/parse "2099-04-21T18:00:00Z")
                           :fire-at (java.time.Instant/parse "2099-04-21T18:05:00Z")
                           :delay-ms 1000}
                          {:origin :core})
    (session/dispatch-in! ctx :scheduler/cancel
                          {:session-id session-id
                           :schedule-id "sch-cancel-pending"}
                          {:origin :core})
    (is (= :cancelled (get-in (ss/get-session-data-in ctx session-id) [:scheduler :schedules "sch-cancel-pending" :status])))

    (swap! (:state* ctx) (ss/session-update session-id (fn [session] (assoc session :is-streaming true))))
    (session/dispatch-in! ctx :scheduler/create
                          {:session-id session-id
                           :schedule-id "sch-cancel-queued"
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
    (is (= :cancelled (get-in (ss/get-session-data-in ctx session-id) [:scheduler :schedules "sch-cancel-queued" :status])))
    (is (= [] (get-in (ss/get-session-data-in ctx session-id) [:scheduler :queue])))))
