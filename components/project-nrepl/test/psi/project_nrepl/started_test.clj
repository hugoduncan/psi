(ns psi.project-nrepl.started-test
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [psi.project-nrepl.ops :as project-nrepl-ops]
   [psi.project-nrepl.runtime :as project-nrepl-runtime]
   [psi.project-nrepl.started :as project-nrepl-started]
   [psi.project-nrepl.test-support
    :refer [age-file-back! fake-connector fake-process make-ctx
            spit-stale-port! started-launcher! touch-fresh! with-temp-dir]]))

(deftest wait-for-started-endpoint-test
  (testing "reads discovered endpoint once .nrepl-port appears"
    (with-temp-dir [dir "psi-project-nrepl-started-"]
      (let [process (fake-process {:alive? true :exit-code 0 :pid 1234})]
        ;; file-backed readiness: write the .nrepl-port synchronously before
        ;; the wait; wait-for-started-endpoint! finds it on the first poll.
        (spit (io/file dir ".nrepl-port") "7888\n")
        (is (= {:host "127.0.0.1" :port 7888 :port-source :dot-nrepl-port}
               (project-nrepl-started/wait-for-started-endpoint! dir process))))))

  (testing "fails when process exits before port discovery"
    (with-temp-dir [dir "psi-project-nrepl-started-"]
      (let [process (fake-process {:alive? false :exit-code 23 :pid 1234})]
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"exited before \.nrepl-port became ready"
             (project-nrepl-started/wait-for-started-endpoint! dir process {:timeout-ms 100 :poll-interval-ms 10}))))))

  (testing "plain deadline timeout (alive process, no .nrepl-port) reports :started-readiness (TR5)"
    ;; The original reproduction's failure mode: an alive process that never
    ;; writes a .nrepl-port, so the deadline fires on the plain
    ;; :started-readiness else-branch (distinct from the exit branch and from
    ;; the :started-stale-port deadline branch). Pins the deadline diagnostic
    ;; shape (:phase :started-readiness, :timeout-ms, :path) and that
    ;; :command-exited? is absent (the process is still alive).
    (with-temp-dir [dir "psi-project-nrepl-started-"]
      (let [process (fake-process {:alive? true :exit-code 0 :pid 1234})
            ex (try
                 (project-nrepl-started/wait-for-started-endpoint!
                  dir process {:timeout-ms 100 :poll-interval-ms 10})
                 (catch clojure.lang.ExceptionInfo e e))
            data (ex-data ex)]
        (is (= :started-readiness (:phase data)))
        (is (= 100 (:timeout-ms data)))
        (is (= (.getAbsolutePath (io/file dir ".nrepl-port")) (:path data)))
        (is (not (:command-exited? data)))
        (is (re-find #"Timed out waiting for started project nREPL" (.getMessage ex)))))))

(deftest wait-for-started-endpoint-stale-port-gate-test
  ;; The launch-instant mtime acceptance gate (A1): with an injected
  ;; :launched-at, a pre-existing .nrepl-port older than the launch instant is
  ;; rejected, while a port whose mtime is >= the launch floor is accepted.
  (testing "rejects a stale (too-old) .nrepl-port as :started-stale-port on deadline"
    (with-temp-dir [dir "psi-project-nrepl-started-"]
      (let [process (fake-process {:alive? true :exit-code 0 :pid 1234})]
        ;; pre-existing port aged well before the launch instant
        (spit-stale-port! dir 7888)
        (let [launched-at (java.time.Instant/now)
              ex (try
                   (project-nrepl-started/wait-for-started-endpoint!
                    dir process
                    {:timeout-ms 100 :poll-interval-ms 10 :launched-at launched-at})
                   (catch clojure.lang.ExceptionInfo e e))]
          (is (= :started-stale-port (:phase (ex-data ex))))
          (is (re-find #"only a stale port was present" (.getMessage ex)))
          ;; A2: the rejected/launch instants are carried on the diagnostic.
          (let [data (ex-data ex)]
            (is (some? (:port-mtime-ms data)))
            (is (some? (:min-mtime-ms data)))
            (is (= launched-at (:launched-at data)))
            (is (>= (:min-mtime-ms data) (:port-mtime-ms data))))))))

  (testing "exit leaving only a stale port reports :started-stale-port (IR1)"
    ;; When the launched process writes only a too-old .nrepl-port and then
    ;; exits, the exit branch must preserve the A2 stale-port distinction
    ;; rather than degrade to a plain :started-readiness diagnostic.
    (with-temp-dir [dir "psi-project-nrepl-started-"]
      (let [process (fake-process {:alive? false :exit-code 42 :pid 1234})]
        (spit-stale-port! dir 7888)
        (let [launched-at (java.time.Instant/now)
              ex (try
                   (project-nrepl-started/wait-for-started-endpoint!
                    dir process
                    {:timeout-ms 100 :poll-interval-ms 10 :launched-at launched-at})
                   (catch clojure.lang.ExceptionInfo e e))]
          (is (= :started-stale-port (:phase (ex-data ex))))
          (is (true? (:command-exited? (ex-data ex))))
          (is (re-find #"exited leaving only a stale" (.getMessage ex)))
          ;; A2: the exit-branch diagnostic also carries the instants.
          (let [data (ex-data ex)]
            (is (some? (:port-mtime-ms data)))
            (is (some? (:min-mtime-ms data)))
            (is (= launched-at (:launched-at data)))
            (is (>= (:min-mtime-ms data) (:port-mtime-ms data))))))))

  (testing "accepts a fresh .nrepl-port (mtime >= launch floor) with :launched-at gate"
    (with-temp-dir [dir "psi-project-nrepl-started-"]
      ;; capture launch instant first, then write the port and force its mtime
      ;; unambiguously *after* the launch floor by construction (TS3) — the
      ;; accept relation no longer depends on the same-second wall-clock
      ;; landing, mirroring the explicit setLastModified in the reject cases.
      (let [process     (fake-process {:alive? true :exit-code 0 :pid 1234})
            port-file   (io/file dir ".nrepl-port")
            launched-at (java.time.Instant/now)]
        (spit port-file "7888\n")
        (touch-fresh! port-file)
        (is (= {:host "127.0.0.1" :port 7888 :port-source :dot-nrepl-port}
               (project-nrepl-started/wait-for-started-endpoint!
                dir process
                {:timeout-ms 1000 :poll-interval-ms 10 :launched-at launched-at})))))))

(deftest start-instance-in-test
  (testing "started-mode acquisition launches command, discovers endpoint, and marks ready"
    (with-temp-dir [worktree "psi-project-nrepl-started-"]
      (let [ctx          (make-ctx)
            ;; the shared happy launcher writes a fresh .nrepl-port synchronously
            ;; before returning the process, so the first poll finds it.
            launcher     (started-launcher!)
            connector    (fake-connector "nrepl-session-1")
            instance     (project-nrepl-started/start-instance-in!
                          ctx worktree ["bb" "nrepl-server"]
                          {:runtime-handle {:process-launcher launcher
                                            :nrepl-connector connector}})]
        (is (= :ready (:lifecycle-state instance)))
        (is (= true (:readiness instance)))
        (is (= {:host "127.0.0.1" :port 7777 :port-source :dot-nrepl-port}
               (:endpoint instance)))
        (is (= 4321 (get-in instance [:runtime-handle :pid])))
        (is (= "nrepl-session-1" (:active-session-id instance)))
        (is (= "nrepl-session-1" (get-in instance [:runtime-handle :session-id]))))))

  (testing "startup failure is projected as failed state"
    (with-temp-dir [worktree "psi-project-nrepl-started-"]
      (let [ctx      (make-ctx)
            launcher (fn [_ _] (throw (ex-info "boom" {:phase :spawn})))]
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"boom"
             (project-nrepl-started/start-instance-in!
              ctx worktree ["bb" "nrepl-server"]
              {:runtime-handle {:process-launcher launcher}})))
        (let [instance (project-nrepl-runtime/instance-in ctx worktree)]
          (is (= :failed (:lifecycle-state instance)))
          (is (= false (:readiness instance)))
          (is (= "boom" (get-in instance [:last-error :message])))))))

  (testing "deletes any pre-existing .nrepl-port before launching, accepts the fresh one"
    (with-temp-dir [worktree "psi-project-nrepl-started-"]
      (let [ctx       (make-ctx)
            port-file (io/file worktree ".nrepl-port")
            fake-proc (fake-process {:alive? true :exit-code 0 :pid 4321})
            launcher  (fn [_worktree _command]
                        ;; the launcher writes the fresh port AFTER pre-launch
                        ;; removal has run; the gate (launched-at) accepts it.
                        (spit port-file "7777\n")
                        fake-proc)
            connector (fake-connector "nrepl-session-1")]
        ;; seed a stale pre-existing port; pre-launch removal must delete it,
        ;; so the launcher-written 7777 (not the stale 9999) is discovered.
        (spit port-file "9999\n")
        (age-file-back! port-file)
        (let [instance (project-nrepl-started/start-instance-in!
                        ctx worktree ["bb" "nrepl-server"]
                        {:runtime-handle {:process-launcher launcher
                                          :nrepl-connector connector}})]
          (is (= :ready (:lifecycle-state instance)))
          (is (= 7777 (get-in instance [:endpoint :port])))))))

  (testing "no :timeout-ms opts records the raised 120000 ms default (TR1)"
    ;; Pins the raised default-readiness-timeout-ms so a regression back to the
    ;; prior 5000 ms is caught: with no :timeout-ms opt the effective timeout
    ;; recorded on the instance must be the 120000 ms default.
    (with-temp-dir [worktree "psi-project-nrepl-started-"]
      (let [ctx       (make-ctx)
            launcher  (started-launcher!)
            connector (fake-connector "nrepl-session-1")
            instance  (project-nrepl-started/start-instance-in!
                       ctx worktree ["bb" "nrepl-server"]
                       {:runtime-handle {:process-launcher launcher
                                         :nrepl-connector connector}})]
        (is (= 120000 (:readiness-timeout-ms instance))))))

  (testing "records the effective :readiness-timeout-ms"
    (with-temp-dir [worktree "psi-project-nrepl-started-"]
      (let [ctx       (make-ctx)
            launcher  (started-launcher!)
            connector (fake-connector "nrepl-session-1")
            instance  (project-nrepl-started/start-instance-in!
                       ctx worktree ["bb" "nrepl-server"]
                       {:timeout-ms 90000
                        :runtime-handle {:process-launcher launcher
                                         :nrepl-connector connector}})]
        (is (= 90000 (:readiness-timeout-ms instance))))))

  (testing "records :started-at = launch instant, not connect instant (TS4/PA2)"
    ;; Provenance, not a bare type check: capture the instant the launcher seam
    ;; is invoked (the true launch site, before wait/connect run) and assert the
    ;; recorded :started-at is <= that instant. A PA2 regression re-adding the
    ;; removed post-wait connect-time `:started-at (now)` write would record an
    ;; instant strictly after the launcher fired (the gate poll + connect happen
    ;; after launch), so :started-at would exceed `launcher-at` and fail green.
    (with-temp-dir [worktree "psi-project-nrepl-started-"]
      (let [ctx          (make-ctx)
            launcher-at  (atom nil)
            ;; the shared launcher composes with an :on-launch pre-write hook (the
            ;; true launch site) so this case can capture the launch instant
            ;; without re-open-coding the happy launcher.
            launcher     (started-launcher!
                          {:on-launch #(reset! launcher-at (java.time.Instant/now))})
            connector    (fake-connector "nrepl-session-1")
            before       (java.time.Instant/now)
            instance     (project-nrepl-started/start-instance-in!
                          ctx worktree ["bb" "nrepl-server"]
                          {:timeout-ms 90000
                           :runtime-handle {:process-launcher launcher
                                            :nrepl-connector connector}})
            started-at   (get-in instance [:runtime-handle :started-at])]
        (is (instance? java.time.Instant started-at))
        ;; launch instant precedes/equals the launcher invocation it triggers,
        ;; and is not earlier than the test's pre-call wall clock.
        (is (not (.isAfter ^java.time.Instant started-at ^java.time.Instant @launcher-at))
            "started-at must not be after the launcher-observed launch instant")
        (is (not (.isBefore ^java.time.Instant started-at ^java.time.Instant before))
            "started-at must not precede the pre-call wall clock"))))

  (testing "failure-path instance carries :readiness-timeout-ms observable via status read (PA4)"
    (with-temp-dir [worktree "psi-project-nrepl-started-"]
      (let [ctx       (make-ctx)
            fake-proc (fake-process {:alive? true :exit-code 0 :pid 4321})
            ;; launcher writes a stale (too-old) port: the gate rejects it and the
            ;; short timeout fires with :phase :started-stale-port.
            launcher  (fn [_worktree _command]
                        (spit-stale-port! worktree 9999)
                        fake-proc)]
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"only a stale port was present"
             (project-nrepl-started/start-instance-in!
              ctx worktree ["bb" "nrepl-server"]
              {:timeout-ms 1000 :poll-interval-ms 10
               :runtime-handle {:process-launcher launcher}})))
        (let [status (project-nrepl-ops/status ctx worktree)
              data   (get-in status [:instance :last-error :data])]
          (is (= 1000 (get-in status [:instance :readiness-timeout-ms])))
          (is (= :started-stale-port (:phase data)))
          ;; A2 observability: the rejected/launch instants are carried on the
          ;; diagnostic ex-data, observable from the instance via
          ;; :last-error → :data — a regression dropping them passes :phase-only.
          (is (some? (:port-mtime-ms data))
              "stale-port diagnostic must carry the rejected port mtime")
          (is (some? (:min-mtime-ms data))
              "stale-port diagnostic must carry the launch-floor min mtime")
          (is (instance? java.time.Instant (:launched-at data))
              "stale-port diagnostic must carry the launch instant")
          (is (>= (:min-mtime-ms data) (:port-mtime-ms data))
              "rejected port mtime is below the launch floor")))))

  (testing "reaps the alive launched process on the readiness-failure path (IR2)"
    ;; A hung/slow-boot child that stays alive but never writes a usable
    ;; .nrepl-port (the headline scenario the 120000 ms default enlarges) must
    ;; be destroyed when the readiness wait times out, not orphaned.
    (with-temp-dir [worktree "psi-project-nrepl-started-"]
      (let [ctx        (make-ctx)
            destroyed* (atom false)
            ;; alive process, no .nrepl-port ever written → the deadline branch
            ;; fires :phase :started-readiness while the process is still alive.
            fake-proc  (fake-process {:alive? true :exit-code 0 :pid 4321
                                      :destroyed* destroyed*})
            launcher   (fn [_worktree _command] fake-proc)]
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"Timed out waiting for started project nREPL"
             (project-nrepl-started/start-instance-in!
              ctx worktree ["bb" "nrepl-server"]
              {:timeout-ms 100 :poll-interval-ms 10
               :runtime-handle {:process-launcher launcher}})))
        (is (true? @destroyed*)
            "the alive launched process must be destroyed on the readiness-failure path")))))
