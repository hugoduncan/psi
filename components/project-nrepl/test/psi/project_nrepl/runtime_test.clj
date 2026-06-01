(ns psi.project-nrepl.runtime-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.project-nrepl.runtime :as project-nrepl-runtime]
   [psi.project-nrepl.test-support :refer [make-ctx]]))

(deftest ensure-instance-creates-starting-instance-test
  (testing "ensure-instance-in! creates one managed instance per worktree"
    (let [ctx      (make-ctx)
          worktree (System/getProperty "user.dir")
          instance (project-nrepl-runtime/ensure-instance-in!
                    ctx
                    {:worktree-path worktree
                     :acquisition-mode :started
                     :command-vector ["bb" "nrepl-server"]})]
      (is (= worktree (:worktree-path instance)))
      (is (= :started (:acquisition-mode instance)))
      (is (= :starting (:lifecycle-state instance)))
      (is (= :nrepl (:transport-kind instance)))
      (is (= :single (:session-mode instance)))
      (is (= ["bb" "nrepl-server"] (:command-vector instance)))
      (is (= 1 (project-nrepl-runtime/instance-count-in ctx)))
      (is (= [worktree] (vec (project-nrepl-runtime/instance-worktree-paths-in ctx)))))))

(deftest ensure-instance-reuses-matching-active-instance-test
  (testing "matching ensure requests reuse the existing active instance"
    (let [ctx      (make-ctx)
          worktree (System/getProperty "user.dir")
          i1       (project-nrepl-runtime/ensure-instance-in!
                    ctx
                    {:worktree-path worktree
                     :acquisition-mode :attached
                     :endpoint {:host "localhost" :port 7888 :port-source :explicit}})
          i2       (project-nrepl-runtime/ensure-instance-in!
                    ctx
                    {:worktree-path worktree
                     :acquisition-mode :attached
                     :endpoint {:host "localhost" :port 7888 :port-source :explicit}})]
      (is (= (:id i1) (:id i2)))
      (is (= 1 (project-nrepl-runtime/instance-count-in ctx))))))

(deftest ensure-instance-rejects-conflicting-active-acquisition-test
  (testing "conflicting acquisition attempts require explicit replace"
    (let [ctx      (make-ctx)
          worktree (System/getProperty "user.dir")]
      (project-nrepl-runtime/ensure-instance-in!
       ctx
       {:worktree-path worktree
        :acquisition-mode :started
        :command-vector ["bb" "nrepl-server"]})
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"requires explicit replace"
           (project-nrepl-runtime/ensure-instance-in!
            ctx
            {:worktree-path worktree
             :acquisition-mode :attached
             :endpoint {:host "localhost" :port 7888 :port-source :explicit}}))))))

(deftest replace-instance-replaces-existing-slot-test
  (testing "replace-instance-in! intentionally replaces the existing instance"
    (let [ctx      (make-ctx)
          worktree (System/getProperty "user.dir")
          i1       (project-nrepl-runtime/ensure-instance-in!
                    ctx
                    {:worktree-path worktree
                     :acquisition-mode :started
                     :command-vector ["bb" "nrepl-server"]})
          i2       (project-nrepl-runtime/replace-instance-in!
                    ctx
                    {:worktree-path worktree
                     :acquisition-mode :attached
                     :endpoint {:host "localhost" :port 7888 :port-source :explicit}})]
      (is (not= (:id i1) (:id i2)))
      (is (= (:id i1) (:replaced-instance-id i2)))
      (is (= :attached (:acquisition-mode i2)))
      (is (= {:host "localhost" :port 7888 :port-source :explicit} (:endpoint i2))))))

(deftest update-instance-updates-and-stamps-test
  (testing "update-instance-in! updates projected state and refreshed updated-at"
    (let [ctx      (make-ctx)
          worktree (System/getProperty "user.dir")
          i1       (project-nrepl-runtime/ensure-instance-in!
                    ctx
                    {:worktree-path worktree
                     :acquisition-mode :attached
                     :endpoint {:host "localhost" :port 7888 :port-source :explicit}})
          i2       (project-nrepl-runtime/update-instance-in!
                    ctx worktree
                    #(assoc %
                            :lifecycle-state :ready
                            :readiness true
                            :active-session-id "nrepl-session-1"))]
      (is (= :ready (:lifecycle-state i2)))
      (is (= true (:readiness i2)))
      (is (= "nrepl-session-1" (:active-session-id i2)))
      (is (not= (:updated-at i1) (:updated-at i2))))))

(deftest remove-instance-removes-slot-test
  (testing "remove-instance-in! removes the managed slot"
    (let [ctx      (make-ctx)
          worktree (System/getProperty "user.dir")]
      (project-nrepl-runtime/ensure-instance-in!
       ctx
       {:worktree-path worktree
        :acquisition-mode :started
        :command-vector ["bb" "nrepl-server"]})
      (is (= 1 (project-nrepl-runtime/instance-count-in ctx)))
      (is (some? (project-nrepl-runtime/remove-instance-in! ctx worktree)))
      (is (zero? (project-nrepl-runtime/instance-count-in ctx))))))
