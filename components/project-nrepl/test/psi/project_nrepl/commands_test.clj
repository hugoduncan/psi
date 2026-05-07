(ns psi.project-nrepl.commands-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.project-nrepl.commands :as project-nrepl-commands]
   [psi.project-nrepl.config]
   [psi.project-nrepl.ops]
   [psi.agent-session.test-support :as test-support]))

(deftest format-project-nrepl-status-test
  (testing "status formatting shows absent instance"
    (let [[ctx session-id] (test-support/create-test-session {:persist? false
                                                              :session-defaults {:worktree-path (System/getProperty "user.dir")}})
          message (project-nrepl-commands/format-project-nrepl-status ctx session-id)]
      (is (re-find #"Project nREPL" message))
      (is (re-find #"state    : absent" message)))))

(deftest dispatch-project-nrepl-command-test
  (testing "/project-repl returns formatted status"
    (let [[ctx session-id] (test-support/create-test-session {:persist? false
                                                              :session-defaults {:worktree-path (System/getProperty "user.dir")}})
          result (project-nrepl-commands/dispatch-project-nrepl-command ctx session-id "/project-repl")]
      (is (= :text (:type result)))
      (is (re-find #"Project nREPL" (:message result)))))

  (testing "/project-repl start reports missing command configuration clearly"
    (let [worktree-path      (System/getProperty "user.dir")
          [ctx session-id] (test-support/create-test-session {:persist? false
                                                              :session-defaults {:worktree-path worktree-path}})]
      (with-redefs [psi.project-nrepl.config/resolve-config (fn [_]
                                                              {:project-nrepl {}})]
        (let [result (project-nrepl-commands/dispatch-project-nrepl-command ctx session-id "/project-repl start")]
          (is (= :text (:type result)))
          (is (re-find #"requires a configured start-command" (:message result)))
          (is (re-find #":agent-session :project-nrepl :start-command" (:message result)))
          (is (re-find #"~/.psi/agent/config.edn" (:message result)))
          (is (re-find #"/.psi/project.edn" (:message result)))
          (is (re-find #"/.psi/project.local.edn" (:message result)))))))

  (testing "/project-repl eval routes through shared project nREPL ops helper"
    (let [[ctx session-id] (test-support/create-test-session {:persist? false
                                                              :session-defaults {:worktree-path (System/getProperty "user.dir")}})]
      (with-redefs [psi.project-nrepl.ops/eval-op (fn [_ctx _worktree-path _code]
                                                    {:status :ok
                                                     :value "3"
                                                     :out ""
                                                     :err ""})]
        (let [result (project-nrepl-commands/dispatch-project-nrepl-command ctx session-id "/project-repl eval (+ 1 2)")]
          (is (= :text (:type result)))
          (is (re-find #"Project nREPL eval ok" (:message result)))
          (is (re-find #"3" (:message result)))))))

  (testing "/project-repl interrupt reports unavailable clearly"
    (let [[ctx session-id] (test-support/create-test-session {:persist? false
                                                              :session-defaults {:worktree-path (System/getProperty "user.dir")}})]
      (with-redefs [psi.project-nrepl.ops/interrupt (fn [_ctx _worktree-path]
                                                      {:status :unavailable
                                                       :reason :no-active-eval})]
        (let [result (project-nrepl-commands/dispatch-project-nrepl-command ctx session-id "/project-repl interrupt")]
          (is (= :text (:type result)))
          (is (re-find #"unavailable" (:message result)))
          (is (re-find #"no-active-eval" (:message result))))))))
