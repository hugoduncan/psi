(ns psi.agent-session.task-artifact-content-resolver-test
  "Behaviour tests for the generic task-artifact-content resolver.

   The resolver reads a worktree-relative task artifact file from the working
   tree, composing its path from the session worktree-path plus the generic
   :psi.munera/task-path and :psi.munera/artifact-name inputs. It knows nothing
   about design-steps.md or any marker (DI-2)."
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.resolvers :as resolvers]
   [psi.agent-session.resolvers.session :as session-resolvers]
   [psi.agent-session.test-support :as test-support]))

(defn- temp-dir!
  []
  (let [dir (io/file (System/getProperty "java.io.tmpdir")
                     (str "psi-task-artifact-" (System/nanoTime)))]
    (.mkdirs dir)
    dir))

(defn- write-artifact!
  [worktree task-path artifact-name content]
  (let [dir (io/file worktree task-path)]
    (.mkdirs dir)
    (spit (io/file dir artifact-name) content)))

(deftest task-artifact-content-resolver-test
  (testing "present file resolves to its working-tree content"
    (let [worktree (temp-dir!)
          [ctx sid] (test-support/create-test-session
                     {:persist? false
                      :session-defaults {:worktree-path (str worktree)}})]
      (write-artifact! worktree "munera/open/230-x" "design-steps.md"
                       "- [ ] SCOPE_QUESTION: open one\n")
      (let [result (resolvers/query-in
                    ctx [:psi.munera/task-artifact-content]
                    {:psi.agent-session/session-id sid
                     :psi.munera/task-path "munera/open/230-x"
                     :psi.munera/artifact-name "design-steps.md"})]
        (is (= "- [ ] SCOPE_QUESTION: open one\n"
               (:psi.munera/task-artifact-content result))
            (pr-str result)))))

  (testing "absent file resolves to nil content"
    (let [worktree (temp-dir!)
          [ctx sid] (test-support/create-test-session
                     {:persist? false
                      :session-defaults {:worktree-path (str worktree)}})
          result (resolvers/query-in
                  ctx [:psi.munera/task-artifact-content]
                  {:psi.agent-session/session-id sid
                   :psi.munera/task-path "munera/open/230-missing"
                   :psi.munera/artifact-name "design-steps.md"})]
      (is (nil? (:psi.munera/task-artifact-content result))
          (pr-str result))))

  (testing "path is composed from worktree-path + task-path + artifact-name"
    (let [worktree (temp-dir!)
          [ctx sid] (test-support/create-test-session
                     {:persist? false
                      :session-defaults {:worktree-path (str worktree)}})]
      (write-artifact! worktree "munera/open/230-y" "design.md" "design body")
      (let [result (resolvers/query-in
                    ctx [:psi.munera/task-artifact-content]
                    {:psi.agent-session/session-id sid
                     :psi.munera/task-path "munera/open/230-y"
                     :psi.munera/artifact-name "design.md"})]
        (is (= "design body"
               (:psi.munera/task-artifact-content result))
            (pr-str result))))))

(deftest task-artifact-content-resolver-fail-open-guard-test
  ;; The resolver's (and (string? worktree-path) (string? task-path)
  ;; (string? artifact-name)) guard is the safety hinge the gate relies on
  ;; (DI-3): a nil/unresolvable input must yield nil content, not an NPE on
  ;; io/file, so the gate fails *open* (proceed) rather than crashing. Invoke
  ;; the resolver directly so the guard branch itself is exercised. Through
  ;; query-in this branch is unreachable as a present-but-non-string input:
  ;; worktree-path is either resolved to a valid string by agent-session-cwd or
  ;; that resolver throws (session missing :worktree-path), and the operation
  ;; handler supplies task-path/artifact-name as literal strings — so the guard
  ;; is defensive (belt-and-suspenders) and only reachable by a direct call.
  (testing "nil worktree-path → nil content (no NPE)"
    (is (nil? (:psi.munera/task-artifact-content
               (session-resolvers/agent-session-task-artifact-content
                {:psi.agent-session/worktree-path nil
                 :psi.munera/task-path "munera/open/230-x"
                 :psi.munera/artifact-name "design-steps.md"})))))
  (testing "nil task-path → nil content (no NPE)"
    (is (nil? (:psi.munera/task-artifact-content
               (session-resolvers/agent-session-task-artifact-content
                {:psi.agent-session/worktree-path (System/getProperty "java.io.tmpdir")
                 :psi.munera/task-path nil
                 :psi.munera/artifact-name "design-steps.md"})))))
  (testing "nil artifact-name → nil content (no NPE)"
    (is (nil? (:psi.munera/task-artifact-content
               (session-resolvers/agent-session-task-artifact-content
                {:psi.agent-session/worktree-path (System/getProperty "java.io.tmpdir")
                 :psi.munera/task-path "munera/open/230-x"
                 :psi.munera/artifact-name nil}))))))
