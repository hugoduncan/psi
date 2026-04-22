(ns psi.agent-session.project-nrepl-commands-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.commands :as commands]
   [psi.agent-session.project-nrepl-config]
   [psi.agent-session.project-nrepl-ops]
   [psi.agent-session.test-support :as test-support]))

(deftest project-nrepl-command-dispatch-test
  (testing "/project-repl returns formatted status"
    (let [[ctx session-id] (test-support/create-test-session {:persist? false
                                                              :session-defaults {:worktree-path (System/getProperty "user.dir")}})
          result (commands/dispatch-in ctx session-id "/project-repl" {})]
      (is (= :text (:type result)))
      (is (re-find #"Project nREPL" (:message result)))))

  (testing "/project-repl start reports missing command configuration clearly"
    (let [worktree-path (System/getProperty "user.dir")
          [ctx session-id] (test-support/create-test-session {:persist? false
                                                              :session-defaults {:worktree-path worktree-path}})]
      (with-redefs [psi.agent-session.project-nrepl-config/resolve-config (fn [_]
                                                                            {:project-nrepl {}})]
        (let [result (commands/dispatch-in ctx session-id "/project-repl start" {})]
          (is (= :text (:type result)))
          (is (re-find #"requires a configured start-command" (:message result)))
          (is (re-find #":agent-session :project-nrepl :start-command" (:message result)))
          (is (re-find #"~/.psi/agent/config.edn" (:message result)))
          (is (re-find #"/.psi/project.edn" (:message result)))
          (is (re-find #"/.psi/project.local.edn" (:message result)))))))

  (testing "/project-repl eval routes through shared project nREPL ops helper"
    (let [[ctx session-id] (test-support/create-test-session {:persist? false
                                                              :session-defaults {:worktree-path (System/getProperty "user.dir")}})]
      (with-redefs [psi.agent-session.project-nrepl-ops/eval-op (fn [_ctx _worktree-path _code]
                                                                  {:status :ok
                                                                   :value "3"
                                                                   :out ""
                                                                   :err ""})]
        (let [result (commands/dispatch-in ctx session-id "/project-repl eval (+ 1 2)" {})]
          (is (= :text (:type result)))
          (is (re-find #"Project nREPL eval ok" (:message result)))
          (is (re-find #"3" (:message result)))))))

  (testing "/project-repl interrupt reports unavailable clearly"
    (let [[ctx session-id] (test-support/create-test-session {:persist? false
                                                              :session-defaults {:worktree-path (System/getProperty "user.dir")}})]
      (with-redefs [psi.agent-session.project-nrepl-ops/interrupt (fn [_ctx _worktree-path]
                                                                    {:status :unavailable
                                                                     :reason :no-active-eval})]
        (let [result (commands/dispatch-in ctx session-id "/project-repl interrupt" {})]
          (is (= :text (:type result)))
          (is (re-find #"unavailable" (:message result)))
          (is (re-find #"no-active-eval" (:message result))))))))
