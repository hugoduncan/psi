(ns psi.agent-session.project-nrepl-started-test
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.project-nrepl-client]
   [psi.agent-session.project-nrepl-runtime :as project-nrepl-runtime]
   [psi.agent-session.project-nrepl-started :as project-nrepl-started]
   [psi.agent-session.test-support :as test-support]))

(defn- make-ctx []
  (let [[ctx _] (test-support/create-test-session {:persist? false})]
    ctx))

(defn- temp-dir []
  (str (java.nio.file.Files/createTempDirectory
        "psi-project-nrepl-started-"
        (make-array java.nio.file.attribute.FileAttribute 0))))

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

(defn- delete-tree! [path]
  (when path
    (let [f (io/file path)]
      (when (.exists f)
        (doseq [x (reverse (file-seq f))]
          (.delete x))))))

(deftest wait-for-started-endpoint-test
  (testing "reads discovered endpoint once .nrepl-port appears"
    (let [dir     (temp-dir)
          process (fake-process {:alive? true :exit-code 0 :pid 1234})]
      (try
        (future
          (Thread/sleep 100)
          (spit (io/file dir ".nrepl-port") "7888\n"))
        (is (= {:host "127.0.0.1" :port 7888 :port-source :dot-nrepl-port}
               (project-nrepl-started/wait-for-started-endpoint! dir process {:timeout-ms 1000 :poll-interval-ms 20})))
        (finally
          (delete-tree! dir)))))

  (testing "fails when process exits before port discovery"
    (let [dir     (temp-dir)
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
    (let [ctx      (make-ctx)
          worktree (temp-dir)
          fake-process (fake-process {:alive? true :exit-code 0 :pid 4321})]
      (try
        (with-redefs [project-nrepl-started/start-process! (fn [_worktree _command]
                                                             (future
                                                               (Thread/sleep 50)
                                                               (spit (io/file worktree ".nrepl-port") "7777\n"))
                                                             fake-process)
                      psi.agent-session.project-nrepl-client/connect-instance-in! (fn [ctx worktree-path]
                                                                                    (project-nrepl-runtime/update-instance-in!
                                                                                     ctx worktree-path
                                                                                     #(assoc %
                                                                                             :lifecycle-state :ready
                                                                                             :readiness true
                                                                                             :active-session-id "nrepl-session-1"
                                                                                             :can-eval? true
                                                                                             :can-interrupt? true)))]
          (let [instance (project-nrepl-started/start-instance-in!
                          ctx worktree ["bb" "nrepl-server"] {:timeout-ms 1000 :poll-interval-ms 10})]
            (is (= :ready (:lifecycle-state instance)))
            (is (= true (:readiness instance)))
            (is (= {:host "127.0.0.1" :port 7777 :port-source :dot-nrepl-port}
                   (:endpoint instance)))
            (is (= 4321 (get-in instance [:runtime-handle :pid])))))
        (finally
          (delete-tree! worktree)))))

  (testing "startup failure is projected as failed state"
    (let [ctx      (make-ctx)
          worktree (temp-dir)]
      (try
        (with-redefs [project-nrepl-started/start-process! (fn [_ _]
                                                             (throw (ex-info "boom" {:phase :spawn})))]
          (is (thrown-with-msg?
               clojure.lang.ExceptionInfo
               #"boom"
               (project-nrepl-started/start-instance-in! ctx worktree ["bb" "nrepl-server"])))
          (let [instance (project-nrepl-runtime/instance-in ctx worktree)]
            (is (= :failed (:lifecycle-state instance)))
            (is (= false (:readiness instance)))
            (is (= "boom" (get-in instance [:last-error :message])))))
        (finally
          (delete-tree! worktree))))))
