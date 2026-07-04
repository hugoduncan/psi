(ns extensions.context-manager-friction-helper-runtime-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [extensions.context-manager :as context-manager]
   [extensions.context-manager-test-support :refer [fake-run-api]]))

(use-fixtures :each (fn [f]
                      (reset! context-manager/friction-helper-session-ids #{})
                      (reset! context-manager/entity-resolution-helper-session-ids #{})
                      (f)))

(defn- await-friction-untracked
  "Block (up to ~2s) until `id` is no longer tracked in the friction-analysis
   helper-session atom. Mirrors `context-manager-test-support/await-untracked`
   (which polls the entity-resolution atom instead)."
  [id]
  (let [deadline (+ (System/currentTimeMillis) 2000)]
    (while (and (contains? @context-manager/friction-helper-session-ids id)
                (< (System/currentTimeMillis) deadline))
      (Thread/sleep 5))))

(deftest default-friction-run-helper-timeout-branch-test
  (testing "wall-clock timeout: real deref/::timeout branch returns nil text,
            child tracked in `friction-helper-session-ids` during the run,
            closed+untracked after orphan settles.

            Mirrors `default-run-helper-timeout-branch-test`
            (`context_manager_helper_runtime_test.clj`) for the
            entity-resolution helper — this is the friction helper's own
            dedicated exercise of the real timeout/teardown branch (task 239
            implementation-review follow-up)."
    (let [release   (atom false)
          run-began (promise)
          closed    (atom nil)
          ;; The run blocks (via fake-run-api's :block-until) simulating a
          ;; live, NOT reliably interruptible model/HTTP call `future-cancel`
          ;; cannot unwind — until `release` is set — so the orphan outlives
          ;; the injected budget and the mid-run assertions are deterministic.
          api (fake-run-api
               {:closed closed
                :block-until release
                :run-began run-began
                :run-result {:psi.agent-session/agent-run-ok? true
                             :psi.agent-session/agent-run-text "ISSUE: x | X\n"}})
          result (#'context-manager/default-friction-run-helper
                  api {:parent-session-id "s1"
                       :system-prompt "sys"
                       :user-prompt "usr"
                       :wall-clock-ms 20})]
      (is (= "child-1" (:child-session-id result)))
      (is (nil? (:text result))
          "timeout branch surfaces no text (→ no-op on the friction path)")
      ;; During the orphan run, before it settles, the child is still tracked
      ;; (recursion-safe) and NOT yet closed.
      @run-began
      (is (contains? @context-manager/friction-helper-session-ids "child-1")
          "child stays tracked in friction-helper-session-ids until the
           orphan future settles")
      (is (not (contains? @context-manager/entity-resolution-helper-session-ids "child-1"))
          "the friction helper's child is not tracked in the unrelated
           entity-resolution atom")
      (is (nil? @closed) "child not closed while orphan still running")
      ;; Let the orphan settle; the detached watcher then closes + untracks.
      (reset! release true)
      (await-friction-untracked "child-1")
      (is (not (contains? @context-manager/friction-helper-session-ids "child-1"))
          "child untracked after orphan settles")
      (is (= "child-1" @closed)
          "child closed after orphan settles, not on the augmenter thread"))))
