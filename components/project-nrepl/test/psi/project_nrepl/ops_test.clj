(ns psi.project-nrepl.ops-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.project-nrepl.config]
   [psi.project-nrepl.ops :as project-nrepl-ops]
   [psi.project-nrepl.runtime :as project-nrepl-runtime]
   [psi.project-nrepl.test-support
    :refer [delete-tree! install-instance! make-ctx temp-dir]]))

(deftest start-test
  (testing "start returns structured missing-start-command result with actionable guidance"
    (let [ctx      (make-ctx)
          worktree (temp-dir "psi-project-nrepl-ops-")]
      (try
        (let [result (project-nrepl-ops/start ctx worktree)]
          (is (= :missing-start-command (:status result)))
          (is (= worktree (:worktree-path result)))
          (is (= :config (:phase result)))
          (is (= ["~/.psi/agent/config.edn"
                  (str worktree "/.psi/project.edn")
                  (str worktree "/.psi/project.local.edn")]
                 (:config-paths result)))
          (is (re-find #"requires a configured start-command" (:message result)))
          (is (re-find #":agent-session :project-nrepl :start-command" (:message result)))
          (is (= {:agent-session {:project-nrepl {:start-command ["bb" "nrepl-server"]}}}
                 (:example-config result))))
        (finally
          (delete-tree! worktree))))))

(deftest status-readiness-timeout-projection-test
  (testing "status/instance-payload projects :readiness-timeout-ms on a ready instance (TR2/AMB3)"
    ;; Pins the instance-payload key-list extension at the ops layer: a normal
    ;; present/ready instance's status must surface :readiness-timeout-ms, so a
    ;; future instance-payload edit dropping it is caught independently of the
    ;; started-mode failure-path status read.
    (let [ctx      (make-ctx)
          worktree (temp-dir "psi-project-nrepl-ops-")]
      (try
        (install-instance! ctx worktree (fn [_] nil))
        (project-nrepl-runtime/update-instance-in!
         ctx worktree
         #(assoc % :readiness-timeout-ms 120000))
        (let [result (project-nrepl-ops/status ctx worktree)]
          (is (= :present (:status result)))
          (is (= true (get-in result [:instance :readiness])))
          (is (= 120000 (get-in result [:instance :readiness-timeout-ms]))))
        (finally
          (delete-tree! worktree))))))

(deftest eval-op-test
  (testing "eval-op preserves the public success contract through real eval-instance-in!"
    (let [ctx            (make-ctx)
          worktree       (System/getProperty "user.dir")
          client-session (fn [msg]
                           [{:id (:id msg)
                             :session "nrepl-session-1"
                             :value "3"
                             :status #{"done"}}])]
      (install-instance! ctx worktree client-session)
      (let [result (project-nrepl-ops/eval-op ctx worktree "(+ 1 2)")]
        (is (= :ok (:status result)))
        (is (= "3" (:value result)))
        ;; eval-instance-in!'s result map omits :ns, so the public payload's
        ;; :ns is nil regardless of the response — the response intentionally
        ;; does NOT seed :ns, proving the drop-:ns contract by observation.
        (is (nil? (:ns result)))
        (is (contains? (:timing result) :started-at))
        (is (contains? (:timing result) :finished-at)))))

  (testing "eval-op preserves the public interrupted contract through real eval-instance-in!"
    (let [ctx            (make-ctx)
          worktree       (System/getProperty "user.dir")
          ;; :interrupted is derived from the response statuses (status
          ;; "interrupted") through real eval-instance-in! → summarize-response,
          ;; not from a canned op result.
          client-session (fn [msg]
                           [{:id (:id msg)
                             :session "nrepl-session-1"
                             :err "Interrupted"
                             :status #{"interrupted"}}])]
      (install-instance! ctx worktree client-session)
      (let [result (project-nrepl-ops/eval-op ctx worktree "(+ 1 2)")]
        (is (= :interrupted (:status result)))
        (is (nil? (:ns result)))
        (is (= "Interrupted" (:err result)))
        (is (contains? (:timing result) :started-at))
        (is (contains? (:timing result) :finished-at))))))
