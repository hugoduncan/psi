(ns psi.project-nrepl.started-test
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [psi.project-nrepl.runtime :as project-nrepl-runtime]
   [psi.project-nrepl.started :as project-nrepl-started]
   [psi.project-nrepl.test-support
    :refer [delete-tree! fake-connector make-ctx temp-dir]]))

(defn- fake-process
  [{:keys [alive? exit-code pid destroyed*]}]
  (proxy [Process] []
    (isAlive [] alive?)
    (waitFor
      ([] exit-code)
      ([_timeout _unit] true))
    (exitValue [] exit-code)
    (destroy [] (when destroyed* (reset! destroyed* true)) nil)
    (destroyForcibly [] (when destroyed* (reset! destroyed* true)) this)
    (pid [] pid)
    (toHandle [] nil)
    (info [] nil)
    (children [] nil)
    (descendants [] nil)
    (getInputStream [] nil)
    (getErrorStream [] nil)
    (getOutputStream [] nil)))

(deftest wait-for-started-endpoint-test
  (testing "reads discovered endpoint once .nrepl-port appears"
    (let [dir     (temp-dir "psi-project-nrepl-started-")
          process (fake-process {:alive? true :exit-code 0 :pid 1234})]
      (try
        ;; file-backed readiness: write the .nrepl-port synchronously before
        ;; the wait; wait-for-started-endpoint! finds it on the first poll.
        (spit (io/file dir ".nrepl-port") "7888\n")
        (is (= {:host "127.0.0.1" :port 7888 :port-source :dot-nrepl-port}
               (project-nrepl-started/wait-for-started-endpoint! dir process {:timeout-ms 1000 :poll-interval-ms 20})))
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
                        {:timeout-ms 1000
                         :poll-interval-ms 10
                         :runtime-handle {:process-launcher launcher
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
          (delete-tree! worktree))))))
