(ns psi.agent-session.scheduler-lifecycle-test
  (:require
   [clojure.test :refer [deftest is]]
   [psi.agent-session.core :as session]
   [psi.agent-session.dispatch :as dispatch]
   [psi.agent-session.persistence :as persist]
   [psi.agent-session.prompt-runtime]
   [psi.agent-session.session-state :as ss]
   [psi.agent-session.test-support :as test-support]))

(defn- create-session-context
  ([] (create-session-context {}))
  ([opts]
   (let [ctx (session/create-context (test-support/safe-context-opts (assoc opts :persist? false)))
         sd  (session/new-session-in! ctx nil {})]
     [ctx (:session-id sd)])))

(defn- journal-messages
  [ctx session-id]
  (->> (persist/all-entries-in ctx session-id)
       (filter #(= :message (:kind %)))
       (map #(get-in % [:data :message]))
       vec))

(deftest scheduled-deliver-runs-canonical-prompt-lifecycle-test
  (let [[ctx session-id] (create-session-context {:persist? false})]
    (dispatch/clear-event-log!)
    (with-redefs [psi.agent-session.prompt-runtime/execute-prepared-request!
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
      (dispatch/dispatch! ctx :scheduler/create
                          {:session-id session-id
                           :schedule-id "sch-e2e-1"
                           :label "wake-check"
                           :message "check status"
                           :created-at (java.time.Instant/parse "2099-04-21T18:00:00Z")
                           :fire-at (java.time.Instant/parse "2099-04-21T18:05:00Z")
                           :delay-ms 1000}
                          {:origin :core})
      (dispatch/dispatch! ctx :scheduler/fired
                          {:session-id session-id
                           :schedule-id "sch-e2e-1"}
                          {:origin :core})
      (let [entries (dispatch/event-log-entries)
            messages (journal-messages ctx session-id)
            user-msg (first messages)
            assistant-msg (second messages)]
        (is (= :delivered (get-in (ss/get-session-data-in ctx session-id) [:scheduler :schedules "sch-e2e-1" :status])))
        (is (= :idle (ss/sc-phase-in ctx session-id)))
        (is (= "user" (:role user-msg)))
        (is (= "check status" (get-in user-msg [:content 0 :text])))
        (is (= :scheduled (:source user-msg)))
        (is (= "sch-e2e-1" (:schedule-id user-msg)))
        (is (= "wake-check" (:label user-msg)))
        (is (= "assistant" (:role assistant-msg)))
        (is (some #(= :scheduler/fired (:event-type %)) entries))
        (is (some #(= :scheduler/deliver (:event-type %)) entries))
        (is (some #(= :session/prompt-submit (:event-type %)) entries))
        (is (some #(= :session/prompt-record-response (:event-type %)) entries))
        (is (some #(= :session/prompt-finish (:event-type %)) entries))))))

(deftest busy-session-fire-queues-then-idle-drains-fifo-test
  (let [[ctx session-id] (create-session-context {:persist? false})]
    (with-redefs [psi.agent-session.prompt-runtime/execute-prepared-request!
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
      (swap! (:state* ctx) (ss/session-update session-id (fn [session] (assoc session :is-streaming true))))
      (doseq [[schedule-id label message created fire]
              [["sch-q-1" "first" "first wake" "2099-04-21T18:00:00Z" "2099-04-21T18:05:00Z"]
               ["sch-q-2" "second" "second wake" "2099-04-21T18:00:01Z" "2099-04-21T18:05:01Z"]]]
        (dispatch/dispatch! ctx :scheduler/create
                            {:session-id session-id
                             :schedule-id schedule-id
                             :label label
                             :message message
                             :created-at (java.time.Instant/parse created)
                             :fire-at (java.time.Instant/parse fire)
                             :delay-ms 1000}
                            {:origin :core})
        (dispatch/dispatch! ctx :scheduler/fired
                            {:session-id session-id
                             :schedule-id schedule-id}
                            {:origin :core}))
      (is (= ["sch-q-1" "sch-q-2"]
             (get-in (ss/get-session-data-in ctx session-id) [:scheduler :queue])))

      (swap! (:state* ctx) (ss/session-update session-id (fn [session] (assoc session :is-streaming false))))
      (let [drain-1 (dispatch/dispatch! ctx :scheduler/drain-queue {:session-id session-id} {:origin :core})]
        (is (= ["sch-q-2"] (get-in (ss/get-session-data-in ctx session-id) [:scheduler :queue])))
        (is (= "sch-q-1" (:schedule-id drain-1)))
        (is (= :delivered (get-in (ss/get-session-data-in ctx session-id) [:scheduler :schedules "sch-q-1" :status])))
        (is (= :queued (get-in (ss/get-session-data-in ctx session-id) [:scheduler :schedules "sch-q-2" :status]))))

      (let [drain-2 (dispatch/dispatch! ctx :scheduler/drain-queue {:session-id session-id} {:origin :core})]
        (is (= [] (get-in (ss/get-session-data-in ctx session-id) [:scheduler :queue])))
        (is (= "sch-q-2" (:schedule-id drain-2)))
        (is (= :delivered (get-in (ss/get-session-data-in ctx session-id) [:scheduler :schedules "sch-q-2" :status])))))))

(deftest cancel-pending-and-queued-schedules-test
  (let [[ctx session-id] (create-session-context {:persist? false})]
    (dispatch/dispatch! ctx :scheduler/create
                        {:session-id session-id
                         :schedule-id "sch-cancel-pending"
                         :message "pending"
                         :created-at (java.time.Instant/parse "2099-04-21T18:00:00Z")
                         :fire-at (java.time.Instant/parse "2099-04-21T18:05:00Z")
                         :delay-ms 1000}
                        {:origin :core})
    (dispatch/dispatch! ctx :scheduler/cancel
                        {:session-id session-id
                         :schedule-id "sch-cancel-pending"}
                        {:origin :core})
    (is (= :cancelled (get-in (ss/get-session-data-in ctx session-id) [:scheduler :schedules "sch-cancel-pending" :status])))

    (swap! (:state* ctx) (ss/session-update session-id (fn [session] (assoc session :is-streaming true))))
    (dispatch/dispatch! ctx :scheduler/create
                        {:session-id session-id
                         :schedule-id "sch-cancel-queued"
                         :message "queued"
                         :created-at (java.time.Instant/parse "2099-04-21T18:00:01Z")
                         :fire-at (java.time.Instant/parse "2099-04-21T18:05:01Z")
                         :delay-ms 1000}
                        {:origin :core})
    (dispatch/dispatch! ctx :scheduler/fired
                        {:session-id session-id
                         :schedule-id "sch-cancel-queued"}
                        {:origin :core})
    (dispatch/dispatch! ctx :scheduler/cancel
                        {:session-id session-id
                         :schedule-id "sch-cancel-queued"}
                        {:origin :core})
    (is (= :cancelled (get-in (ss/get-session-data-in ctx session-id) [:scheduler :schedules "sch-cancel-queued" :status])))
    (is (= [] (get-in (ss/get-session-data-in ctx session-id) [:scheduler :queue])))))
