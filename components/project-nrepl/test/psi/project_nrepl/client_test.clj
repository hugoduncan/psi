(ns psi.project-nrepl.client-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [nrepl.core]
   [psi.project-nrepl.client :as project-nrepl-client]
   [psi.project-nrepl.runtime :as project-nrepl-runtime]
   [psi.agent-session.test-support :as test-support]))

(defn- make-ctx []
  (let [[ctx _] (test-support/create-test-session {:persist? false})]
    ctx))

(deftest connect-instance-in-test
  (testing "connect establishes single managed client session and capability flags"
    (let [ctx      (make-ctx)
          worktree (System/getProperty "user.dir")
          transport {:transport :fake}
          client-fn (fn ([] nil) ([_] nil))
          session-fn (with-meta (fn [_] nil) {(keyword "nrepl.core" "taking-until") {:session "nrepl-session-1"}})]
      (project-nrepl-runtime/ensure-instance-in!
       ctx
       {:worktree-path worktree
        :acquisition-mode :attached
        :endpoint {:host "127.0.0.1" :port 7888 :port-source :explicit}})
      (with-redefs [nrepl.core/connect (fn [& _] transport)
                    nrepl.core/client (fn [_transport _timeout] client-fn)
                    nrepl.core/client-session (fn [_client] session-fn)]
        (let [instance (project-nrepl-client/connect-instance-in! ctx worktree)]
          (is (= :ready (:lifecycle-state instance)))
          (is (= true (:readiness instance)))
          (is (= "nrepl-session-1" (:active-session-id instance)))
          (is (= true (:can-eval? instance)))
          (is (= true (:can-interrupt? instance)))
          (is (= transport (get-in instance [:runtime-handle :transport]))))))))

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
