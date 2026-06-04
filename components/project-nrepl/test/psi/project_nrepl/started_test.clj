(ns psi.project-nrepl.started-test
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [psi.project-nrepl.ops :as project-nrepl-ops]
   [psi.project-nrepl.runtime :as project-nrepl-runtime]
   [psi.project-nrepl.started :as project-nrepl-started]
   [psi.project-nrepl.test-support
    :refer [age-file-back! delete-tree! fake-connector fake-process make-ctx
            spit-stale-port! temp-dir touch-fresh!]]))

(deftest wait-for-started-endpoint-test
  (testing "reads discovered endpoint once .nrepl-port appears"
    (let [dir     (temp-dir "psi-project-nrepl-started-")
          process (fake-process {:alive? true :exit-code 0 :pid 1234})]
      (try
        ;; file-backed readiness: write the .nrepl-port synchronously before
        ;; the wait; wait-for-started-endpoint! finds it on the first poll.
        (spit (io/file dir ".nrepl-port") "7888\n")
        (is (= {:host "127.0.0.1" :port 7888 :port-source :dot-nrepl-port}
               (project-nrepl-started/wait-for-started-endpoint! dir process)))
        (finally
          (delete-tree! dir)))))

  (testing "fails when process exits before port discovery"
    (let [dir     (temp-dir "psi-project-nrepl-started-")
          process (fake-process {:alive? false :exit-code 23 :pid 1234})]
      (try
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"exited before \.nrepl-port became ready"
             (project-nrepl-started/wait-for-started-endpoint! dir process {:timeout-ms 100 :poll-interval-ms 10})))
        (finally
          (delete-tree! dir)))))

  (testing "plain deadline timeout (alive process, no .nrepl-port) reports :started-readiness (TR5)"
    ;; The original reproduction's failure mode: an alive process that never
    ;; writes a .nrepl-port, so the deadline fires on the plain
    ;; :started-readiness else-branch (distinct from the exit branch and from
    ;; the :started-stale-port deadline branch). Pins the deadline diagnostic
    ;; shape (:phase :started-readiness, :timeout-ms, :path) and that
    ;; :command-exited? is absent (the process is still alive).
    (let [dir     (temp-dir "psi-project-nrepl-started-")
          process (fake-process {:alive? true :exit-code 0 :pid 1234})]
      (try
        (let [ex (try
                   (project-nrepl-started/wait-for-started-endpoint!
                    dir process {:timeout-ms 100 :poll-interval-ms 10})
                   (catch clojure.lang.ExceptionInfo e e))
              data (ex-data ex)]
          (is (= :started-readiness (:phase data)))
          (is (= 100 (:timeout-ms data)))
          (is (= (.getAbsolutePath (io/file dir ".nrepl-port")) (:path data)))
          (is (not (:command-exited? data)))
          (is (re-find #"Timed out waiting for started project nREPL" (.getMessage ex))))
        (finally
          (delete-tree! dir))))))

(deftest wait-for-started-endpoint-stale-port-gate-test
  ;; The launch-instant mtime acceptance gate (A1): with an injected
  ;; :launched-at, a pre-existing .nrepl-port older than the launch instant is
  ;; rejected, while a port whose mtime is >= the launch floor is accepted.
  (testing "rejects a stale (too-old) .nrepl-port as :started-stale-port on deadline"
    (let [dir     (temp-dir "psi-project-nrepl-started-")
          process (fake-process {:alive? true :exit-code 0 :pid 1234})]
      (try
        ;; pre-existing port aged well before the launch instant
        (spit-stale-port! dir 7888)
        (let [launched-at (java.time.Instant/now)
              ex (try
                   (project-nrepl-started/wait-for-started-endpoint!
                    dir process
                    {:timeout-ms 100 :poll-interval-ms 10 :launched-at launched-at})
                   (catch clojure.lang.ExceptionInfo e e))]
          (is (= :started-stale-port (:phase (ex-data ex))))
          (is (re-find #"only a stale port was present" (.getMessage ex))))
        (finally
          (delete-tree! dir)))))

  (testing "exit leaving only a stale port reports :started-stale-port (IR1)"
    ;; When the launched process writes only a too-old .nrepl-port and then
    ;; exits, the exit branch must preserve the A2 stale-port distinction
    ;; rather than degrade to a plain :started-readiness diagnostic.
    (let [dir     (temp-dir "psi-project-nrepl-started-")
          process (fake-process {:alive? false :exit-code 42 :pid 1234})]
      (try
        (spit-stale-port! dir 7888)
        (let [launched-at (java.time.Instant/now)
              ex (try
                   (project-nrepl-started/wait-for-started-endpoint!
                    dir process
                    {:timeout-ms 100 :poll-interval-ms 10 :launched-at launched-at})
                   (catch clojure.lang.ExceptionInfo e e))]
          (is (= :started-stale-port (:phase (ex-data ex))))
          (is (true? (:command-exited? (ex-data ex))))
          (is (re-find #"exited leaving only a stale" (.getMessage ex))))
        (finally
          (delete-tree! dir)))))

  (testing "accepts a fresh .nrepl-port (mtime >= launch floor) with :launched-at gate"
    (let [dir       (temp-dir "psi-project-nrepl-started-")
          process   (fake-process {:alive? true :exit-code 0 :pid 1234})
          port-file (io/file dir ".nrepl-port")]
      (try
        ;; capture launch instant first, then write the port and force its mtime
        ;; unambiguously *after* the launch floor by construction (TS3) — the
        ;; accept relation no longer depends on the same-second wall-clock
        ;; landing, mirroring the explicit setLastModified in the reject cases.
        (let [launched-at (java.time.Instant/now)]
          (spit port-file "7888\n")
          (touch-fresh! port-file)
          (is (= {:host "127.0.0.1" :port 7888 :port-source :dot-nrepl-port}
                 (project-nrepl-started/wait-for-started-endpoint!
                  dir process
                  {:timeout-ms 1000 :poll-interval-ms 10 :launched-at launched-at}))))
        (finally
          (delete-tree! dir))))))

(deftest start-instance-in-test
  (testing "started-mode acquisition launches command, discovers endpoint, and marks ready"
    (let [ctx          (make-ctx)
          worktree     (temp-dir "psi-project-nrepl-started-")
          fake-proc    (fake-process {:alive? true :exit-code 0 :pid 4321})
          launcher     (fn [_worktree _command]
                         ;; file-backed readiness: write a real .nrepl-port in the
                         ;; temp worktree synchronously before returning the
                         ;; process; the launcher runs before
                         ;; wait-for-started-endpoint!, so the first poll finds it.
                         (spit (io/file worktree ".nrepl-port") "7777\n")
                         fake-proc)
          connector    (fake-connector "nrepl-session-1")]
      (try
        (let [instance (project-nrepl-started/start-instance-in!
                        ctx worktree ["bb" "nrepl-server"]
                        {:runtime-handle {:process-launcher launcher
                                          :nrepl-connector connector}})]
          (is (= :ready (:lifecycle-state instance)))
          (is (= true (:readiness instance)))
          (is (= {:host "127.0.0.1" :port 7777 :port-source :dot-nrepl-port}
                 (:endpoint instance)))
          (is (= 4321 (get-in instance [:runtime-handle :pid])))
          (is (= "nrepl-session-1" (:active-session-id instance)))
          (is (= "nrepl-session-1" (get-in instance [:runtime-handle :session-id]))))
        (finally
          (delete-tree! worktree)))))

  (testing "startup failure is projected as failed state"
    (let [ctx      (make-ctx)
          worktree (temp-dir "psi-project-nrepl-started-")
          launcher (fn [_ _] (throw (ex-info "boom" {:phase :spawn})))]
      (try
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"boom"
             (project-nrepl-started/start-instance-in!
              ctx worktree ["bb" "nrepl-server"]
              {:runtime-handle {:process-launcher launcher}})))
        (let [instance (project-nrepl-runtime/instance-in ctx worktree)]
          (is (= :failed (:lifecycle-state instance)))
          (is (= false (:readiness instance)))
          (is (= "boom" (get-in instance [:last-error :message]))))
        (finally
          (delete-tree! worktree)))))

  (testing "deletes any pre-existing .nrepl-port before launching, accepts the fresh one"
    (let [ctx       (make-ctx)
          worktree  (temp-dir "psi-project-nrepl-started-")
          port-file (io/file worktree ".nrepl-port")
          fake-proc (fake-process {:alive? true :exit-code 0 :pid 4321})
          launcher  (fn [_worktree _command]
                      ;; the launcher writes the fresh port AFTER pre-launch
                      ;; removal has run; the gate (launched-at) accepts it.
                      (spit port-file "7777\n")
                      fake-proc)
          connector (fake-connector "nrepl-session-1")]
      (try
        ;; seed a stale pre-existing port; pre-launch removal must delete it,
        ;; so the launcher-written 7777 (not the stale 9999) is discovered.
        (spit port-file "9999\n")
        (age-file-back! port-file)
        (let [instance (project-nrepl-started/start-instance-in!
                        ctx worktree ["bb" "nrepl-server"]
                        {:runtime-handle {:process-launcher launcher
                                          :nrepl-connector connector}})]
          (is (= :ready (:lifecycle-state instance)))
          (is (= 7777 (get-in instance [:endpoint :port]))))
        (finally
          (delete-tree! worktree)))))

  (testing "no :timeout-ms opts records the raised 120000 ms default (TR1)"
    ;; Pins the raised default-readiness-timeout-ms so a regression back to the
    ;; prior 5000 ms is caught: with no :timeout-ms opt the effective timeout
    ;; recorded on the instance must be the 120000 ms default.
    (let [ctx       (make-ctx)
          worktree  (temp-dir "psi-project-nrepl-started-")
          fake-proc (fake-process {:alive? true :exit-code 0 :pid 4321})
          launcher  (fn [_worktree _command]
                      (spit (io/file worktree ".nrepl-port") "7777\n")
                      fake-proc)
          connector (fake-connector "nrepl-session-1")]
      (try
        (let [instance (project-nrepl-started/start-instance-in!
                        ctx worktree ["bb" "nrepl-server"]
                        {:runtime-handle {:process-launcher launcher
                                          :nrepl-connector connector}})]
          (is (= 120000 (:readiness-timeout-ms instance))))
        (finally
          (delete-tree! worktree)))))

  (testing "records the effective :readiness-timeout-ms"
    (let [ctx       (make-ctx)
          worktree  (temp-dir "psi-project-nrepl-started-")
          fake-proc (fake-process {:alive? true :exit-code 0 :pid 4321})
          launcher  (fn [_worktree _command]
                      (spit (io/file worktree ".nrepl-port") "7777\n")
                      fake-proc)
          connector (fake-connector "nrepl-session-1")]
      (try
        (let [instance (project-nrepl-started/start-instance-in!
                        ctx worktree ["bb" "nrepl-server"]
                        {:timeout-ms 90000
                         :runtime-handle {:process-launcher launcher
                                          :nrepl-connector connector}})]
          (is (= 90000 (:readiness-timeout-ms instance))))
        (finally
          (delete-tree! worktree)))))

  (testing "records :started-at = launch instant, not connect instant (TS4/PA2)"
    ;; Provenance, not a bare type check: capture the instant the launcher seam
    ;; is invoked (the true launch site, before wait/connect run) and assert the
    ;; recorded :started-at is <= that instant. A PA2 regression re-adding the
    ;; removed post-wait connect-time `:started-at (now)` write would record an
    ;; instant strictly after the launcher fired (the gate poll + connect happen
    ;; after launch), so :started-at would exceed `launcher-at` and fail green.
    (let [ctx          (make-ctx)
          worktree     (temp-dir "psi-project-nrepl-started-")
          fake-proc    (fake-process {:alive? true :exit-code 0 :pid 4321})
          launcher-at  (atom nil)
          launcher     (fn [_worktree _command]
                         (reset! launcher-at (java.time.Instant/now))
                         (spit (io/file worktree ".nrepl-port") "7777\n")
                         fake-proc)
          connector    (fake-connector "nrepl-session-1")
          before       (java.time.Instant/now)]
      (try
        (let [instance   (project-nrepl-started/start-instance-in!
                          ctx worktree ["bb" "nrepl-server"]
                          {:timeout-ms 90000
                           :runtime-handle {:process-launcher launcher
                                            :nrepl-connector connector}})
              started-at (get-in instance [:runtime-handle :started-at])]
          (is (instance? java.time.Instant started-at))
          ;; launch instant precedes/equals the launcher invocation it triggers,
          ;; and is not earlier than the test's pre-call wall clock.
          (is (not (.isAfter ^java.time.Instant started-at ^java.time.Instant @launcher-at))
              "started-at must not be after the launcher-observed launch instant")
          (is (not (.isBefore ^java.time.Instant started-at ^java.time.Instant before))
              "started-at must not precede the pre-call wall clock"))
        (finally
          (delete-tree! worktree)))))

  (testing "failure-path instance carries :readiness-timeout-ms observable via status read (PA4)"
    (let [ctx       (make-ctx)
          worktree  (temp-dir "psi-project-nrepl-started-")
          fake-proc (fake-process {:alive? true :exit-code 0 :pid 4321})
          ;; launcher writes a stale (too-old) port: the gate rejects it and the
          ;; short timeout fires with :phase :started-stale-port.
          launcher  (fn [_worktree _command]
                      (spit-stale-port! worktree 9999)
                      fake-proc)]
      (try
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"only a stale port was present"
             (project-nrepl-started/start-instance-in!
              ctx worktree ["bb" "nrepl-server"]
              {:timeout-ms 1000 :poll-interval-ms 10
               :runtime-handle {:process-launcher launcher}})))
        (let [status (project-nrepl-ops/status ctx worktree)]
          (is (= 1000 (get-in status [:instance :readiness-timeout-ms])))
          (is (= :started-stale-port
                 (get-in status [:instance :last-error :data :phase]))))
        (finally
          (delete-tree! worktree))))))
