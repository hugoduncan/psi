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
                                                  :created-at (java.time.Instant/parse "2026-04-21T18:00:00Z")
                                                  :fire-at (java.time.Instant/parse "2026-04-21T18:00:01Z")}
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
