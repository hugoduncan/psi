(ns psi.project-nrepl.ops-test
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [psi.project-nrepl.config]
   [psi.project-nrepl.ops :as project-nrepl-ops]
   [psi.project-nrepl.runtime :as project-nrepl-runtime]
   [psi.project-nrepl.test-support
    :refer [delete-tree! fake-connector fake-process install-instance!
            make-ctx temp-dir]]))

(defn- write-project-config!
  "Write `<worktree>/.psi/project.edn` with the given agent-session project-nrepl map."
  [worktree project-nrepl-map]
  (let [psi-dir (io/file worktree ".psi")]
    (.mkdirs psi-dir)
    (spit (io/file psi-dir "project.edn")
          (pr-str {:agent-session {:project-nrepl project-nrepl-map}}))))

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

(deftest start-config-timeout-threading-test
  (testing "ops/start threads a configured :start-readiness-timeout-ms from project config into the instance (TR3/Q1)"
    ;; Pins the central Q1 configurability path end-to-end: a project
    ;; .psi/project.edn configured :start-readiness-timeout-ms must flow through
    ;; ops/start's resolve-config → resolved-start-readiness-timeout-ms →
    ;; cond-> opts → start-instance-in! onto the instance's :readiness-timeout-ms.
    ;; A regression in the ops glue (dropped assoc, wrong key, not reading cfg)
    ;; would otherwise pass every other test. The launcher/connector seam is
    ;; pre-seeded via ensure-instance-in! (matching :started/command/endpoint)
    ;; so start-instance-in!'s ensure matches and preserves the runtime-handle.
    (let [ctx       (make-ctx)
          worktree  (temp-dir "psi-project-nrepl-ops-")
          command   ["bb" "nrepl-server"]
          launcher  (fn [_worktree _command]
                      (spit (io/file worktree ".nrepl-port") "7777\n")
                      (fake-process {:alive? true :exit-code 0 :pid 4321}))
          connector (fake-connector "nrepl-session-1")]
      (try
        (write-project-config! worktree {:start-command command
                                         :start-readiness-timeout-ms 90000})
        ;; Pre-seed the runtime-handle launcher/connector seam. The acquisition
        ;; mode/command/endpoint match start-instance-in!'s ensure request, so it
        ;; returns this slot (no conflict) and keeps the seeded runtime-handle.
        (project-nrepl-runtime/ensure-instance-in!
         ctx
         {:worktree-path worktree
          :acquisition-mode :started
          :lifecycle-state :starting
          :command-vector command
          :runtime-handle {:process-launcher launcher
                           :nrepl-connector connector}})
        (let [result (project-nrepl-ops/start ctx worktree)]
          (is (= :started (:status result)))
          (is (= 90000 (get-in result [:instance :readiness-timeout-ms]))))
        (let [status (project-nrepl-ops/status ctx worktree)]
          (is (= 90000 (get-in status [:instance :readiness-timeout-ms]))))
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
