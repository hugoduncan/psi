(ns psi.app-runtime.context-test
  (:require
   [clojure.test :refer [deftest is]]
   [psi.agent-session.core :as session]
   [psi.agent-session.dispatch :as dispatch]
   [psi.agent-session.persistence :as persist]
   [psi.agent-session.session-state :as ss]
   [psi.agent-session.test-support :as test-support]
   [psi.app-runtime.context :as app-context]))

(defn- create-session-context
  ([]
   (create-session-context {}))
  ([opts]
   (let [ctx (session/create-context (test-support/safe-context-opts opts))
         sd  (session/new-session-in! ctx nil {})]
     [ctx (:session-id sd)])))

(deftest context-snapshot-uses-canonical-context-session-display-names-test
  (let [[ctx sid] (create-session-context {:persist? false})
        user-ts   (java.time.Instant/parse "2026-03-16T10:47:00Z")
        _         (ss/journal-append-in! ctx sid
                                         (persist/message-entry {:role "user"
                                                                 :content [{:type :text :text "hi"}]
                                                                 :timestamp user-ts}))
        snapshot  (app-context/context-snapshot ctx sid sid)
        session   (some #(when (= sid (:id %)) %) (:sessions snapshot))]
    (is (= sid (:active-session-id snapshot)))
    (is (= "hi" (:display-name session)))
    (is (= "waiting" (:runtime-state session)))
    (is (= (str user-ts) (:updated-at session)))))

(deftest context-snapshot-marks-active-and-streaming-session-test
  (let [[ctx sid1] (create-session-context {:persist? false})
        sd2        (session/new-session-in! ctx sid1 {})
        sid2       (:session-id sd2)
        _          (dispatch/dispatch! ctx :session/prompt {:session-id sid2} {:origin :test})
        snapshot   (app-context/context-snapshot ctx sid2 sid2)
        session1   (some #(when (= sid1 (:id %)) %) (:sessions snapshot))
        session2   (some #(when (= sid2 (:id %)) %) (:sessions snapshot))]
    (is (false? (:is-active session1)))
    (is (true? (:is-active session2)))
    (is (= "waiting" (:runtime-state session1)))
    (is (= "running" (:runtime-state session2)))
    (is (true? (:is-streaming session2)))))

(deftest context-snapshot-falls-back-to-current-session-when-index-empty-test
  (let [[ctx sid] (create-session-context {:persist? false})
        _         (ss/update-state-value-in! ctx (ss/state-path :sessions) (constantly {}))
        snapshot  (app-context/context-snapshot ctx sid sid)]
    (is (= sid (:active-session-id snapshot)))
    (is (= [sid] (mapv :id (:sessions snapshot))))))

(deftest phase->runtime-state-covers-canonical-phases-test
  (is (= "waiting" (app-context/phase->runtime-state :idle)))
  (is (= "running" (app-context/phase->runtime-state :streaming)))
  (is (= "retrying" (app-context/phase->runtime-state :retrying)))
  (is (= "compacting" (app-context/phase->runtime-state :compacting)))
  (is (nil? (app-context/phase->runtime-state nil))))
