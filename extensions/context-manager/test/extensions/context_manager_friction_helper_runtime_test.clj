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

(deftest default-friction-run-helper-settled-run-test
  (testing "on a normal settled run the friction wrapper (a) returns the run
            text, (b) passes :session-name \"friction-analysis\" (and the
            no-tools :tool-ids []/:tool-names [] grant) to
            create-child-session, (c) threads the selected model into
            run-agent-loop-in-session, and (d) closes + untracks the child in
            `friction-helper-session-ids` on success.

            Mirrors `default-run-helper-settled-run-closes-and-untracks-test`
            + `default-run-helper-forwards-selected-model-test`
            (`context_manager_helper_runtime_test.clj`) for the
            entity-resolution wrapper — the two wrappers differ only by
            session-name / tool-grant / tracking atom, so without this a
            mis-wired friction session-name or normal-completion teardown
            would pass every existing friction helper-runtime test
            (task 239 task-test-review round-2 follow-up)."
    (let [create-calls (atom nil)
          run-calls    (atom nil)
          closed       (atom nil)
          model        {:provider :ollama :id "qwen2.5-coder"}
          api (fake-run-api
               {:create-calls create-calls
                :run-calls run-calls
                :closed closed
                :run-result {:psi.agent-session/agent-run-ok? true
                             :psi.agent-session/agent-run-text "ISSUE: x | X\n"}})
          result (#'context-manager/default-friction-run-helper
                  api {:parent-session-id "s1"
                       :system-prompt "sys"
                       :user-prompt "usr"
                       :model model})]
      (is (= "child-1" (:child-session-id result)))
      (is (= "ISSUE: x | X\n" (:text result))
          "settled successful run surfaces the run text")
      (is (= "friction-analysis" (:session-name @create-calls))
          "friction wrapper passes :session-name \"friction-analysis\"")
      (is (= [] (:tool-ids @create-calls))
          "friction helper is granted no tools (:tool-ids [])")
      (is (= [] (get-in @create-calls [:prompt-component-selection :tool-names]))
          "no tool-name prompt fragments granted (:tool-names [])")
      (is (= model (:model @run-calls))
          "selected model threaded into run params")
      ;; The future's finally closes + untracks on its own thread; await it.
      (await-friction-untracked "child-1")
      (is (= "child-1" @closed)
          "settled run closes the child session")
      (is (not (contains? @context-manager/friction-helper-session-ids
                          "child-1"))
          "settled run untracks the child from the recursion-avoidance atom"))))

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
