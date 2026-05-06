(ns psi.agent-session.extension-schedule-event-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.core :as session]
   [psi.agent-session.extensions :as ext]
   [psi.agent-session.test-support :as test-support]))

(defn- create-session-context
  ([] (create-session-context {}))
  ([opts]
   (let [ctx (session/create-context (test-support/safe-context-opts (assoc opts :persist? false)))
         sd  (session/new-session-in! ctx nil {})]
     [ctx (:session-id sd)])))

(deftest schedule-extension-event-dispatch-test
  (testing "session schedule-extension-event dispatches delayed extension event"
    (let [[ctx session-id] (create-session-context)
          fired           (atom [])
          reg             (:extension-registry ctx)]
      (ext/register-extension-in! reg "/ext/auto-session-name")
      (ext/register-handler-in! reg "/ext/auto-session-name" "rename-checkpoint"
                                (fn [event]
                                  (swap! fired conj event)
                                  nil))
      (let [result (session/dispatch-in! ctx
                                         :session/schedule-extension-event
                                         {:session-id session-id
                                          :delay-ms 20
                                          :event-name "rename-checkpoint"
                                          :payload {:session-id session-id
                                                    :turn-count 2}}
                                         {:origin :core})]
        (is (= {:scheduled? true
                :delay-ms 20
                :event-name "rename-checkpoint"}
               result))
        (loop [i 0]
          (when (and (< i 20) (not= 1 (count @fired)))
            (Thread/sleep 20)
            (recur (inc i))))
        (is (= 1 (count @fired)) (pr-str @fired))
        (is (= {:session-id session-id
                :turn-count 2}
               (first @fired))
            (pr-str @fired))))))
