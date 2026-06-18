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

(deftest task-artifact-content-resolver-test
  (testing "present file resolves to its working-tree content"
    (test-support/with-temp-worktree-session
      (fn [worktree ctx sid]
        (test-support/write-task-artifact! worktree "munera/open/230-x" "design-steps.md"
                                           "- [ ] SCOPE_QUESTION: open one\n")
        (let [result (resolvers/query-in
                      ctx [:psi.munera/task-artifact-content]
                      {:psi.agent-session/session-id sid
                       :psi.munera/task-path "munera/open/230-x"
                       :psi.munera/artifact-name "design-steps.md"})]
          (is (= "- [ ] SCOPE_QUESTION: open one\n"
                 (:psi.munera/task-artifact-content result))
              (pr-str result))))))

  (testing "absent file resolves to nil content"
    (test-support/with-temp-worktree-session
      (fn [_worktree ctx sid]
        (let [result (resolvers/query-in
                      ctx [:psi.munera/task-artifact-content]
                      {:psi.agent-session/session-id sid
                       :psi.munera/task-path "munera/open/230-missing"
                       :psi.munera/artifact-name "design-steps.md"})]
          (is (nil? (:psi.munera/task-artifact-content result))
              (pr-str result))))))

  (testing "path is composed from worktree-path + task-path + artifact-name"
    (test-support/with-temp-worktree-session
      (fn [worktree ctx sid]
        (test-support/write-task-artifact! worktree "munera/open/230-y" "design.md" "design body")
        (let [result (resolvers/query-in
                      ctx [:psi.munera/task-artifact-content]
                      {:psi.agent-session/session-id sid
                       :psi.munera/task-path "munera/open/230-y"
                       :psi.munera/artifact-name "design.md"})]
          (is (= "design body"
                 (:psi.munera/task-artifact-content result))
              (pr-str result)))))))

(deftest task-artifact-content-resolver-rejects-unsafe-paths-test
  ;; The generic file-read resolver is registered in the agent-session resolver
  ;; graph, so it must be robust independent of the scope-gate operation's
  ;; workflow-specific task-path normalization. Unsafe or non-file inputs return
  ;; nil content: the lifecycle gate fails open instead of escaping the worktree
  ;; or throwing from slurp.
  (testing "absolute task path is rejected"
    (test-support/with-temp-worktree-session
      (fn [worktree ctx sid]
        (let [outside (test-support/write-task-artifact! worktree "outside" "design-steps.md"
                                                         "escaped content")
              result  (resolvers/query-in
                       ctx [:psi.munera/task-artifact-content]
                       {:psi.agent-session/session-id sid
                        :psi.munera/task-path (.getParent outside)
                        :psi.munera/artifact-name (.getName outside)})]
          (is (nil? (:psi.munera/task-artifact-content result))
              (pr-str result))))))

  (testing "absolute artifact path is rejected"
    (test-support/with-temp-worktree-session
      (fn [worktree ctx sid]
        (let [outside (test-support/write-task-artifact! worktree "outside" "design-steps.md"
                                                         "escaped content")
              result  (resolvers/query-in
                       ctx [:psi.munera/task-artifact-content]
                       {:psi.agent-session/session-id sid
                        :psi.munera/task-path "munera/open/230-x"
                        :psi.munera/artifact-name (.getAbsolutePath outside)})]
          (is (nil? (:psi.munera/task-artifact-content result))
              (pr-str result))))))

  (testing "relative .. escape is rejected"
    (test-support/with-temp-worktree-session
      (fn [worktree ctx sid]
        (let [sibling-dir (io/file (.getParentFile worktree)
                                   (str (.getName worktree) "-outside"))]
          (try
            (.mkdirs sibling-dir)
            (spit (io/file sibling-dir "design-steps.md") "escaped content")
            (let [result (resolvers/query-in
                          ctx [:psi.munera/task-artifact-content]
                          {:psi.agent-session/session-id sid
                           :psi.munera/task-path (str "../" (.getName sibling-dir))
                           :psi.munera/artifact-name "design-steps.md"})]
              (is (nil? (:psi.munera/task-artifact-content result))
                  (pr-str result)))
            (finally
              (test-support/delete-recursively! sibling-dir)))))))

  (testing "artifact-name .. escape to a worktree-root file is rejected"
    (test-support/with-temp-worktree-session
      (fn [worktree ctx sid]
        (spit (io/file worktree "README.md") "root readme")
        (test-support/write-task-artifact! worktree "munera/open/230-x" "design.md"
                                           "task design")
        (let [result (resolvers/query-in
                      ctx [:psi.munera/task-artifact-content]
                      {:psi.agent-session/session-id sid
                       :psi.munera/task-path "munera/open/230-x"
                       :psi.munera/artifact-name "../../../README.md"})]
          (is (nil? (:psi.munera/task-artifact-content result))
              (pr-str result))))))

  (testing "artifact-name .. escape to a sibling task artifact is rejected"
    (test-support/with-temp-worktree-session
      (fn [worktree ctx sid]
        (test-support/write-task-artifact! worktree "munera/open/230-x" "design.md"
                                           "task design")
        (test-support/write-task-artifact! worktree "munera/open/231-y" "design.md"
                                           "sibling design")
        (let [result (resolvers/query-in
                      ctx [:psi.munera/task-artifact-content]
                      {:psi.agent-session/session-id sid
                       :psi.munera/task-path "munera/open/230-x"
                       :psi.munera/artifact-name "../231-y/design.md"})]
          (is (nil? (:psi.munera/task-artifact-content result))
              (pr-str result))))))

  (testing "directory artifact is rejected instead of slurped"
    (test-support/with-temp-worktree-session
      (fn [worktree ctx sid]
        (.mkdirs (io/file worktree "munera/open/230-x/design-steps.md"))
        (let [result (resolvers/query-in
                      ctx [:psi.munera/task-artifact-content]
                      {:psi.agent-session/session-id sid
                       :psi.munera/task-path "munera/open/230-x"
                       :psi.munera/artifact-name "design-steps.md"})]
          (is (nil? (:psi.munera/task-artifact-content result))
              (pr-str result)))))))

(deftest task-artifact-content-resolver-fail-open-guard-test
  ;; The resolver's input/type guard is the safety hinge the gate relies on
  ;; (DI-3): a nil/unresolvable input must yield nil content, not an NPE on
  ;; path composition, so the gate fails *open* (proceed) rather than crashing.
  ;; Invoke the resolver directly so the guard branch itself is exercised.
  ;; Through query-in this branch is unreachable as a present-but-non-string
  ;; input: worktree-path is either resolved to a valid string by
  ;; agent-session-cwd or that resolver throws (session missing :worktree-path),
  ;; and the operation handler supplies task-path/artifact-name as literal
  ;; strings — so the guard is defensive (belt-and-suspenders) and only
  ;; reachable by a direct call.
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
