(ns psi.project-nrepl.ops-test
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [psi.project-nrepl.config]
   [psi.project-nrepl.eval]
   [psi.project-nrepl.ops :as project-nrepl-ops]
   [psi.agent-session.test-support :as test-support]))

(defn- make-ctx []
  (let [[ctx _] (test-support/create-test-session {:persist? false})]
    ctx))

(defn- temp-dir []
  (str (java.nio.file.Files/createTempDirectory
        "psi-project-nrepl-ops-"
        (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- delete-tree! [path]
  (when path
    (let [f (io/file path)]
      (when (.exists f)
        (doseq [x (reverse (file-seq f))]
          (.delete x))))))

(deftest start-test
  (testing "start returns structured missing-start-command result with actionable guidance"
    (let [ctx      (make-ctx)
          worktree (temp-dir)]
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

(deftest eval-op-test
  (testing "eval-op preserves the public success contract"
    (let [ctx      (make-ctx)
          worktree (System/getProperty "user.dir")
          started  (java.time.Instant/parse "2026-05-07T20:00:00Z")
          finished (java.time.Instant/parse "2026-05-07T20:00:01Z")]
      (with-redefs [psi.project-nrepl.eval/eval-instance-in! (fn [_ctx _worktree-path _code]
                                                               {:status :success
                                                                :value "3"
                                                                :out ""
                                                                :err ""
                                                                :ns "user"
                                                                :started-at started
                                                                :finished-at finished})]
        (let [result (project-nrepl-ops/eval-op ctx worktree "(+ 1 2)")]
          (is (= {:status :ok
                  :value "3"
                  :out ""
                  :err ""
                  :ns "user"
                  :timing {:started-at started
                           :finished-at finished}}
                 result))))))

  (testing "eval-op preserves the public interrupted contract"
    (let [ctx      (make-ctx)
          worktree (System/getProperty "user.dir")
          started  (java.time.Instant/parse "2026-05-07T20:00:00Z")
          finished (java.time.Instant/parse "2026-05-07T20:00:01Z")]
      (with-redefs [psi.project-nrepl.eval/eval-instance-in! (fn [_ctx _worktree-path _code]
                                                               {:status :interrupted
                                                                :value nil
                                                                :out ""
                                                                :err "Interrupted"
                                                                :ns "user"
                                                                :started-at started
                                                                :finished-at finished})]
        (let [result (project-nrepl-ops/eval-op ctx worktree "(+ 1 2)")]
          (is (= {:status :interrupted
                  :value nil
                  :out ""
                  :err "Interrupted"
                  :ns "user"
                  :timing {:started-at started
                           :finished-at finished}}
                 result)))))))
