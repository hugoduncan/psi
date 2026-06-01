(ns psi.project-nrepl.client-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.project-nrepl.client :as project-nrepl-client]
   [psi.project-nrepl.runtime :as project-nrepl-runtime]
   [psi.project-nrepl.test-support :refer [make-ctx session-fn-with-id]]))

(deftest connect-instance-in-test
  (testing "connect establishes single managed client session and capability flags"
    (let [ctx        (make-ctx)
          worktree   (System/getProperty "user.dir")
          transport  {:transport :fake}
          client-fn  (fn ([] nil) ([_] nil))
          session-fn (session-fn-with-id "nrepl-session-1")
          connector  (fn [_endpoint]
                       {:transport transport
                        :client client-fn
                        :client-session session-fn})]
      (project-nrepl-runtime/ensure-instance-in!
       ctx
       {:worktree-path worktree
        :acquisition-mode :attached
        :endpoint {:host "127.0.0.1" :port 7888 :port-source :explicit}})
      (project-nrepl-runtime/update-instance-in!
       ctx worktree
       #(assoc-in % [:runtime-handle :nrepl-connector] connector))
      (let [instance (project-nrepl-client/connect-instance-in! ctx worktree)]
        (is (= :ready (:lifecycle-state instance)))
        (is (= true (:readiness instance)))
        (is (= "nrepl-session-1" (:active-session-id instance)))
        (is (= true (:can-eval? instance)))
        (is (= true (:can-interrupt? instance)))
        (is (= transport (get-in instance [:runtime-handle :transport])))
        (is (= client-fn (get-in instance [:runtime-handle :client])))
        (is (= session-fn (get-in instance [:runtime-handle :client-session])))
        (is (= "nrepl-session-1" (get-in instance [:runtime-handle :session-id])))))))

(deftest disconnect-instance-in-test
  (testing "disconnect clears managed client session runtime fields"
    (let [ctx      (make-ctx)
          worktree (System/getProperty "user.dir")
          closed*  (atom nil)]
      (project-nrepl-runtime/ensure-instance-in!
       ctx
       {:worktree-path worktree
        :acquisition-mode :attached
        :endpoint {:host "127.0.0.1" :port 7888 :port-source :explicit}})
      (project-nrepl-runtime/update-instance-in!
       ctx worktree
       #(assoc %
               :readiness true
               :active-session-id "nrepl-session-1"
               :runtime-handle {:transport (proxy [java.io.Closeable] []
                                             (close [] (reset! closed* :fake)))
                                :client :client
                                :client-session :session
                                :session-id "nrepl-session-1"}))
      (let [instance (project-nrepl-client/disconnect-instance-in! ctx worktree)]
        (is (= :fake @closed*))
        (is (= false (:readiness instance)))
        (is (nil? (:active-session-id instance)))
        (is (nil? (get-in instance [:runtime-handle :transport])))))))
