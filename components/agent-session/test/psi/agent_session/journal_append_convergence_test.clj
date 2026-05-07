(ns psi.agent-session.journal-append-convergence-test
  (:require
   [clojure.test :refer [deftest is]]
   [psi.agent-session.dispatch-effects :as dispatch-effects]
   [psi.agent-session.persistence :as persist]
   [psi.agent-session.runtime :as runtime]
   [psi.agent-session.test-support :as test-support]
   [psi.session-state.state :as ss]
   [psi.state-kernel.dispatch :as kernel]))

(defn- create-ctx
  ([] (create-ctx {}))
  ([opts]
   (test-support/create-test-session (test-support/safe-context-opts opts))))

(deftest generic-journal-append-effect-updates-memory-test
  (let [[ctx sid] (create-ctx {:persist? false})
        before    (vec (ss/get-state-value-in ctx (ss/state-path :journal sid)))
        entry     (persist/message-entry {:role "user" :content [{:type :text :text "hi"}]})]
    (dispatch-effects/execute-effect! ctx {:effect/type :persist/journal-append-entry
                                           :session-id sid
                                           :entry entry})
    (is (= (conj before entry) (ss/get-state-value-in ctx (ss/state-path :journal sid))))))

(deftest generic-journal-append-effect-reaches-persistence-boundary-test
  (let [[ctx sid] (create-ctx {:persist? false})
        entry     (persist/message-entry {:role "assistant" :content [{:type :text :text "done"}]})
        seen      (atom [])]
    (with-redefs [persist/persist-entry-in! (fn [ctx* session-id cwd parent-session-id parent-session-path]
                                              (swap! seen conj {:ctx ctx*
                                                                :session-id session-id
                                                                :cwd cwd
                                                                :parent-session-id parent-session-id
                                                                :parent-session-path parent-session-path}))]
      (dispatch-effects/execute-effect! ctx {:effect/type :persist/journal-append-entry
                                             :session-id sid
                                             :entry entry}))
    (is (= 1 (count @seen)))
    (is (= sid (:session-id (first @seen))))))

(deftest runtime-user-journal-path-is-dispatch-owned-test
  (let [[ctx sid] (create-ctx {:persist? false})
        before     (vec (ss/get-state-value-in ctx (ss/state-path :journal sid)))
        user-msg   (runtime/journal-user-message-in! ctx sid "hello" nil)
        last-entry (last (kernel/event-log-entries))
        appended   (:entry (:event-data last-entry))
        journal    (ss/get-state-value-in ctx (ss/state-path :journal sid))]
    (is (= :session/append-journal-entry (:event-type last-entry)))
    (is (= [{:effect/type :persist/journal-append-entry
             :entry appended}]
           (:declared-effects last-entry)))
    (is (= "user" (:role user-msg)))
    (is (= [{:type :text :text "hello"}] (:content user-msg)))
    (is (= :message (:kind appended)))
    (is (= user-msg (get-in appended [:data :message])))
    (is (= (conj before appended) journal))))
