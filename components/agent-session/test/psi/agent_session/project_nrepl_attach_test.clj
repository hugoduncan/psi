(ns psi.agent-session.project-nrepl-attach-test
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.project-nrepl-client]
   [psi.agent-session.project-nrepl-attach :as project-nrepl-attach]
   [psi.agent-session.project-nrepl-runtime :as project-nrepl-runtime]
   [psi.agent-session.test-support :as test-support]))

(defn- make-ctx []
  (let [[ctx _] (test-support/create-test-session {:persist? false})]
    ctx))

(defn- temp-dir []
  (str (java.nio.file.Files/createTempDirectory
        "psi-project-nrepl-attach-"
        (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- delete-tree! [path]
  (when path
    (let [f (io/file path)]
      (when (.exists f)
        (doseq [x (reverse (file-seq f))]
          (.delete x))))))

(deftest resolve-attach-endpoint-test
  (testing "explicit port wins and host defaults when omitted"
    (let [worktree (System/getProperty "user.dir")]
      (is (= {:host "127.0.0.1" :port 7888 :port-source :explicit}
             (project-nrepl-attach/resolve-attach-endpoint worktree {:port 7888})))
      (is (= {:host "localhost" :port 7888 :port-source :explicit}
             (project-nrepl-attach/resolve-attach-endpoint worktree {:host "localhost" :port 7888})))))

  (testing "falls back to worktree-local .nrepl-port when explicit port absent"
    (let [dir (temp-dir)]
      (try
        (spit (io/file dir ".nrepl-port") "7999\n")
        (is (= {:host "127.0.0.1" :port 7999 :port-source :dot-nrepl-port}
               (project-nrepl-attach/resolve-attach-endpoint dir {})))
        (is (= {:host "localhost" :port 7999 :port-source :dot-nrepl-port}
               (project-nrepl-attach/resolve-attach-endpoint dir {:host "localhost"})))
        (finally
          (delete-tree! dir))))))

(deftest attach-instance-in-test
  (testing "attach establishes attached instance and managed client session"
    (let [ctx      (make-ctx)
          worktree (System/getProperty "user.dir")]
      (with-redefs [psi.agent-session.project-nrepl-client/connect-instance-in! (fn [ctx worktree-path]
                                                                                  (project-nrepl-runtime/update-instance-in!
                                                                                   ctx worktree-path
                                                                                   #(assoc %
                                                                                           :lifecycle-state :ready
                                                                                           :readiness true
                                                                                           :active-session-id "nrepl-session-1"
                                                                                           :can-eval? true
                                                                                           :can-interrupt? true)))]
        (let [instance (project-nrepl-attach/attach-instance-in! ctx worktree {:port 7888})]
          (is (= :attached (:acquisition-mode instance)))
          (is (= :ready (:lifecycle-state instance)))
          (is (= {:host "127.0.0.1" :port 7888 :port-source :explicit}
                 (:endpoint instance)))
          (is (= "nrepl-session-1" (:active-session-id instance)))))))

  (testing "attach failure is projected as failed state"
    (let [ctx      (make-ctx)
          worktree (System/getProperty "user.dir")]
      (with-redefs [psi.agent-session.project-nrepl-client/connect-instance-in! (fn [_ _]
                                                                                  (throw (ex-info "attach-boom" {:phase :connect})))]
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"attach-boom"
             (project-nrepl-attach/attach-instance-in! ctx worktree {:port 7888})))
        (let [instance (project-nrepl-runtime/instance-in ctx worktree)]
          (is (= :failed (:lifecycle-state instance)))
          (is (= false (:readiness instance)))
          (is (= {:host "127.0.0.1" :port 7888 :port-source :explicit}
                 (:endpoint instance)))
          (is (= "attach-boom" (get-in instance [:last-error :message]))))))))
