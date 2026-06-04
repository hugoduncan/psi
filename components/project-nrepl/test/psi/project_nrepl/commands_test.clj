(ns psi.project-nrepl.commands-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.project-nrepl.commands :as project-nrepl-commands]
   [psi.project-nrepl.runtime :as project-nrepl-runtime]
   [psi.project-nrepl.test-support :refer [install-instance!
                                           session-ctx-at with-temp-dir]]))

(deftest format-project-nrepl-status-test
  (testing "status formatting shows absent instance"
    (let [[ctx session-id] (session-ctx-at (System/getProperty "user.dir"))
          message (project-nrepl-commands/format-project-nrepl-status ctx session-id)]
      (is (re-find #"Project nREPL" message))
      (is (re-find #"state    : absent" message)))))

(deftest dispatch-project-nrepl-command-test
  (testing "/project-repl returns formatted status"
    (let [[ctx session-id] (session-ctx-at (System/getProperty "user.dir"))
          result (project-nrepl-commands/dispatch-project-nrepl-command ctx session-id "/project-repl")]
      (is (= :text (:type result)))
      (is (re-find #"Project nREPL" (:message result)))))

  (testing "/project-repl start reports missing command configuration clearly"
    (with-temp-dir [worktree-path "psi-project-nrepl-commands-"]
      (let [[ctx session-id] (session-ctx-at worktree-path)
            result (project-nrepl-commands/dispatch-project-nrepl-command ctx session-id "/project-repl start")]
        (is (= :text (:type result)))
        (is (re-find #"requires a configured start-command" (:message result)))
        (is (re-find #":agent-session :project-nrepl :start-command" (:message result)))
        (is (re-find #"~/.psi/agent/config.edn" (:message result)))
        (is (re-find #"/.psi/project.edn" (:message result)))
        (is (re-find #"/.psi/project.local.edn" (:message result))))))

  (testing "/project-repl eval routes through real commands → ops/eval-op → eval/eval-instance-in!"
    (let [worktree         (System/getProperty "user.dir")
          [ctx session-id] (session-ctx-at worktree)
          client-session   (fn [msg]
                             [{:id (:id msg)
                               :session "nrepl-session-1"
                               :value "3"
                               :status #{"done"}}])]
      ;; Instance is installed at the same worktree the dispatch session-id
      ;; resolves to (via ss/session-worktree-path-in), so the lookup hits.
      (install-instance! ctx worktree client-session)
      (let [result (project-nrepl-commands/dispatch-project-nrepl-command ctx session-id "/project-repl eval (+ 1 2)")]
        (is (= :text (:type result)))
        (is (re-find #"Project nREPL eval ok" (:message result)))
        (is (re-find #"3" (:message result))))))

  (testing "/project-repl interrupt routes through real commands → ops/interrupt → eval/interrupt-instance-in!"
    (let [worktree         (System/getProperty "user.dir")
          [ctx session-id] (session-ctx-at worktree)
          client-session   (fn [msg]
                             [{:id (:id msg)
                               :session "nrepl-session-1"
                               :status #{"done" "interrupted"}}])]
      (install-instance! ctx worktree client-session)
      ;; PRECONDITION: interrupt-instance-in! short-circuits to :no-active-eval
      ;; without an :active-op, so seed one before dispatching interrupt.
      (project-nrepl-runtime/update-instance-in!
       ctx worktree
       #(assoc-in % [:runtime-handle :active-op]
                  {:op-id "eval-123" :started-at (java.time.Instant/now)}))
      (let [result (project-nrepl-commands/dispatch-project-nrepl-command ctx session-id "/project-repl interrupt")]
        (is (= :text (:type result)))
        (is (re-find #"Project nREPL interrupt: ok" (:message result)))
        (is (re-find #"interrupted" (:message result))))))

  (testing "/project-repl interrupt with no active eval reports unavailable clearly"
    (let [worktree         (System/getProperty "user.dir")
          [ctx session-id] (session-ctx-at worktree)]
      (install-instance! ctx worktree (fn [_] []))
      (let [result (project-nrepl-commands/dispatch-project-nrepl-command ctx session-id "/project-repl interrupt")]
        (is (= :text (:type result)))
        (is (re-find #"unavailable" (:message result)))
        (is (re-find #"no-active-eval" (:message result)))))))
