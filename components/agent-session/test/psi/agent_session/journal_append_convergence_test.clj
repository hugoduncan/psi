(ns psi.agent-session.journal-append-convergence-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.dispatch :as dispatch]
   [psi.agent-session.dispatch-effects :as dispatch-effects]
   [psi.agent-session.runtime :as runtime]
   [psi.agent-session.test-support :as test-support]
   [psi.session-persistence.core :as persist]
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

(deftest session-append-handler-declares-canonical-io-effect-test
  (let [[ctx sid] (create-ctx {:persist? false})
        file      (java.io.File/createTempFile "psi-journal-handler" ".ndedn")
        _         (ss/assoc-state-value-in! ctx (ss/state-path :flush-state sid) {:flushed? false :session-file file})
        before    (vec (ss/get-state-value-in ctx (ss/state-path :journal sid)))
        entry     (persist/message-entry {:role "assistant" :content [{:type :text :text "done"}]})
        result    (dispatch/dispatch! ctx :session/append-journal-entry {:session-id sid :entry entry} {:origin :core})
        journal   (ss/get-state-value-in ctx (ss/state-path :journal sid))]
    (is (= entry result))
    (is (= (conj before entry) journal))
    (is (.exists file))
    (is (true? (:flushed? (ss/get-state-value-in ctx (ss/state-path :flush-state sid)))))))

(deftest canonical-session-journal-io-executor-does-not-append-memory-test
  (let [[ctx sid] (create-ctx {:persist? false})
        before    (vec (ss/get-state-value-in ctx (ss/state-path :journal sid)))
        flush     (assoc (ss/get-state-value-in ctx (ss/state-path :flush-state sid))
                         :session-file (java.io.File/createTempFile "psi-journal-append" ".ndedn")
                         :flushed? true)
        entry     (persist/message-entry {:role "assistant" :content [{:type :text :text "done"}]})]
    (ss/assoc-state-value-in! ctx (ss/state-path :flush-state sid) flush)
    (dispatch-effects/execute-effect! ctx {:effect/type :persist/session-journal-io
                                           :session-id sid
                                           :request {:op :append-entry
                                                     :session-id sid
                                                     :session-file (:session-file flush)
                                                     :worktree-path (:worktree-path (ss/get-session-data-in ctx sid))
                                                     :entry entry}})
    (is (= before (ss/get-state-value-in ctx (ss/state-path :journal sid))))))

(deftest flush-success-alone-marks-session-flushed-test
  (let [[ctx sid] (create-ctx {:persist? false})
        file      (java.io.File/createTempFile "psi-journal-flush" ".ndedn")]
    (ss/assoc-state-value-in! ctx (ss/state-path :flush-state sid) {:flushed? false :session-file file})
    (dispatch-effects/execute-effect! ctx {:effect/type :persist/session-journal-io
                                           :session-id sid
                                           :request {:op :flush-journal
                                                     :session-id sid
                                                     :session-file file
                                                     :worktree-path (:worktree-path (ss/get-session-data-in ctx sid))
                                                     :entries []}})
    (is (true? (:flushed? (ss/get-state-value-in ctx (ss/state-path :flush-state sid)))))))

(deftest failed-flush-does-not-mark-session-flushed-test
  (let [[ctx sid] (create-ctx {:persist? false})
        file      (java.io.File/createTempFile "psi-journal-fail" ".ndedn")]
    (ss/assoc-state-value-in! ctx (ss/state-path :flush-state sid) {:flushed? false :session-file file})
    (with-redefs [persist/flush-journal! (fn [& _] (throw (ex-info "boom" {})))]
      (try
        (dispatch-effects/execute-effect! ctx {:effect/type :persist/session-journal-io
                                               :session-id sid
                                               :request {:op :flush-journal
                                                         :session-id sid
                                                         :session-file file
                                                         :worktree-path (:worktree-path (ss/get-session-data-in ctx sid))
                                                         :entries []}})
        (catch Exception _ nil)))
    (is (false? (:flushed? (ss/get-state-value-in ctx (ss/state-path :flush-state sid)))))))

(deftest runtime-user-journal-path-is-dispatch-owned-test
  (let [[ctx sid] (create-ctx {:persist? false})
        before     (vec (ss/get-state-value-in ctx (ss/state-path :journal sid)))
        user-msg   (runtime/journal-user-message-in! ctx sid "hello" nil)
        last-entry (last (kernel/event-log-entries))
        appended   (:entry (:event-data last-entry))
        journal    (ss/get-state-value-in ctx (ss/state-path :journal sid))]
    (is (= :session/append-journal-entry (:event-type last-entry)))
    (testing "user-only append remains a no-op at the file-io boundary"
      (is (= [] (:declared-effects last-entry))))
    (is (= "user" (:role user-msg)))
    (is (= [{:type :text :text "hello"}] (:content user-msg)))
    (is (= :message (:kind appended)))
    (is (= user-msg (get-in appended [:data :message])))
    (is (= (conj before appended) journal))))
