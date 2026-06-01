(ns psi.project-nrepl.attach-test
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [psi.project-nrepl.attach :as project-nrepl-attach]
   [psi.project-nrepl.runtime :as project-nrepl-runtime]
   [psi.project-nrepl.test-support
    :refer [delete-tree! fake-connector make-ctx temp-dir]]))

(deftest resolve-attach-endpoint-test
  (testing "explicit port wins and host defaults when omitted"
    (let [worktree (System/getProperty "user.dir")]
      (is (= {:host "127.0.0.1" :port 7888 :port-source :explicit}
             (project-nrepl-attach/resolve-attach-endpoint worktree {:port 7888})))
      (is (= {:host "localhost" :port 7888 :port-source :explicit}
             (project-nrepl-attach/resolve-attach-endpoint worktree {:host "localhost" :port 7888})))))

  (testing "falls back to worktree-local .nrepl-port when explicit port absent"
    (let [dir (temp-dir "psi-project-nrepl-attach-")]
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
    (let [ctx       (make-ctx)
          worktree  (System/getProperty "user.dir")
          connector (fake-connector "nrepl-session-1")
          instance  (project-nrepl-attach/attach-instance-in!
                     ctx worktree {:port 7888} {:runtime-handle {:nrepl-connector connector}})]
      (is (= :attached (:acquisition-mode instance)))
      (is (= :ready (:lifecycle-state instance)))
      (is (= true (:readiness instance)))
      (is (= {:host "127.0.0.1" :port 7888 :port-source :explicit}
             (:endpoint instance)))
      (is (= "nrepl-session-1" (:active-session-id instance)))
      (is (= "nrepl-session-1" (get-in instance [:runtime-handle :session-id])))
      (is (= true (:can-eval? instance)))
      (is (= true (:can-interrupt? instance)))))

  (testing "attach failure is projected as failed state"
    (let [ctx       (make-ctx)
          worktree  (System/getProperty "user.dir")
          connector (fn [_endpoint]
                      (throw (ex-info "attach-boom" {:phase :connect})))]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"attach-boom"
           (project-nrepl-attach/attach-instance-in!
            ctx worktree {:port 7888} {:runtime-handle {:nrepl-connector connector}})))
      (let [instance (project-nrepl-runtime/instance-in ctx worktree)]
        (is (= :failed (:lifecycle-state instance)))
        (is (= false (:readiness instance)))
        (is (= {:host "127.0.0.1" :port 7888 :port-source :explicit}
               (:endpoint instance)))
        (is (= "attach-boom" (get-in instance [:last-error :message])))))))
