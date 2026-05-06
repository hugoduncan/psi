(ns psi.agent-session.scheduler-context-shutdown-test
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

(deftest shutdown-context-clears-scheduler-timers-test
  (testing "context shutdown interrupts and clears scheduler timer handles"
    (let [[ctx session-id] (create-session-context {:persist? false})
          _                (session/dispatch-in! ctx :scheduler/create
                                                 {:session-id session-id
                                                  :schedule-id "sch-1"
                                                  :label "later"
                                                  :message "later"
                                                  :fire-at (.plusSeconds (java.time.Instant/now) 60)}
                                                 {:origin :core})]
      (is (contains? @(:scheduler-timers* ctx) "sch-1"))
      (session/shutdown-context! ctx)
      (is (= {} @(:scheduler-timers* ctx))))))
