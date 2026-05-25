(ns psi.session-state.tool-authority-test
  "Tests for tool-ids as authoritative session tool membership field.
   Verifies schema, initial-session default, and lifecycle path propagation."
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.session-state.init :as init]
   [psi.session-state.model :as model]
   [psi.session-state.state :as state]))

(deftest tool-ids-schema-test
  ;; :tool-ids is a required vector of strings in the session schema
  (testing "initial-session includes :tool-ids as empty vector"
    (let [s (model/initial-session)]
      (is (= [] (:tool-ids s)))))

  (testing "session with :tool-ids passes schema validation"
    (is (model/valid-session? (model/initial-session {:tool-ids ["bash" "read"]}))))

  (testing "session without :tool-ids fails schema validation"
    (let [s (dissoc (model/initial-session) :tool-ids)]
      (is (some? (model/explain-session s))))))

(deftest initialize-new-session-carries-tool-ids-test
  ;; new-session lifecycle path inherits :tool-ids from current session data
  (testing "new session inherits parent :tool-ids"
    (let [current-sd (assoc (model/initial-session {:worktree-path "/tmp/parent"})
                            :tool-ids ["bash" "read" "edit"]
                            :tool-defs [{:name "bash"} {:name "read"} {:name "edit"}])
          state1     (init/initialize-new-session-state
                      {} current-sd
                      {:new-session-id "new-1"
                       :worktree-path "/tmp/new"
                       :session-name "new"
                       :spawn-mode :new-root
                       :session-file nil})
          sd1        (get-in state1 (state/session-data-path "new-1"))]
      (is (= ["bash" "read" "edit"] (:tool-ids sd1)))
      (is (= (mapv :name (:tool-defs sd1)) (:tool-ids sd1))
          ":tool-defs and :tool-ids co-propagate coherently through lifecycle")))

  (testing "new session inherits empty :tool-ids when parent has none set"
    (let [current-sd (model/initial-session {:worktree-path "/tmp/parent"})
          state1     (init/initialize-new-session-state
                      {} current-sd
                      {:new-session-id "new-2"
                       :worktree-path "/tmp/new"
                       :session-name "new"
                       :spawn-mode :new-root
                       :session-file nil})
          sd1        (get-in state1 (state/session-data-path "new-2"))]
      (is (= [] (:tool-ids sd1))))))

(deftest initialize-resumed-session-carries-tool-ids-test
  ;; resumed-session lifecycle path inherits :tool-ids from current session data
  (testing "resumed session inherits :tool-ids from current session"
    (let [current-sd (assoc (model/initial-session {:worktree-path "/tmp/source"})
                            :tool-ids ["bash" "write"])
          state1     (init/initialize-resumed-session-state
                      {} current-sd
                      {:session-id "sid-r"
                       :session-path "/tmp/resume.ndedn"
                       :header {:worktree-path "/tmp/resume"
                                :parent-session-id nil
                                :parent-session nil}
                       :entries []
                       :model {:provider "p" :id "m"}
                       :thinking-level :medium})
          sd1        (get-in state1 (state/session-data-path "sid-r"))]
      (is (= ["bash" "write"] (:tool-ids sd1))))))

(deftest initialize-forked-session-carries-tool-ids-test
  ;; forked-session lifecycle path inherits :tool-ids from parent session data
  (testing "forked session inherits parent :tool-ids"
    (let [parent-sd (assoc (model/initial-session {:worktree-path "/tmp/ws"})
                           :session-id "parent"
                           :session-file "/tmp/parent.ndedn"
                           :tool-ids ["bash" "read" "psi-tool"])
          state0    {:agent-session {:sessions {"parent" {:agent-ctx ::agent :sc-session-id ::sc}}}}
          state1    (init/initialize-forked-session-state
                     state0 parent-sd
                     {:new-session-id "fork-1"
                      :branch-entries []
                      :session-file nil})
          sd1       (get-in state1 (state/session-data-path "fork-1"))]
      (is (= ["bash" "read" "psi-tool"] (:tool-ids sd1))))))
