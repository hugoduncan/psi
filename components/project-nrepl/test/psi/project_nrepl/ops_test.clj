(ns psi.project-nrepl.ops-test
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [psi.project-nrepl.config]
   [psi.project-nrepl.runtime :as project-nrepl-runtime]
   [psi.project-nrepl.ops :as project-nrepl-ops]
   [psi.agent-session.test-support :as test-support]))

(defn- make-ctx []
  (let [[ctx _] (test-support/create-test-session {:persist? false})]
    ctx))

(defn- install-instance!
  "Install a real managed attached instance at `worktree-path` with an in-memory
   `[:runtime-handle :client-session]` fn (the eval_test pattern)."
  [ctx worktree-path client-session]
  (project-nrepl-runtime/ensure-instance-in!
   ctx
   {:worktree-path worktree-path
    :acquisition-mode :attached
    :endpoint {:host "127.0.0.1" :port 7888 :port-source :explicit}})
  (project-nrepl-runtime/update-instance-in!
   ctx worktree-path
   #(assoc %
           :lifecycle-state :ready
           :readiness true
           :active-session-id "nrepl-session-1"
           :runtime-handle {:client-session client-session
                            :session-id "nrepl-session-1"})))

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
  (testing "eval-op preserves the public success contract through real eval-instance-in!"
    (let [ctx            (make-ctx)
          worktree       (System/getProperty "user.dir")
          client-session (fn [msg]
                           [{:id (:id msg)
                             :session "nrepl-session-1"
                             :value "3"
                             :ns "user"
                             :status #{"done"}}])]
      (install-instance! ctx worktree client-session)
      (let [result (project-nrepl-ops/eval-op ctx worktree "(+ 1 2)")]
        (is (= :ok (:status result)))
        (is (= "3" (:value result)))
        ;; eval-instance-in!'s result map omits :ns, so the public payload's
        ;; :ns is nil — real-behavior contract (the prior mock fabricated :ns).
        (is (nil? (:ns result)))
        (is (contains? (:timing result) :started-at))
        (is (contains? (:timing result) :finished-at)))))

  (testing "eval-op preserves the public interrupted contract through real eval-instance-in!"
    (let [ctx            (make-ctx)
          worktree       (System/getProperty "user.dir")
          ;; :interrupted is derived from the response statuses (status
          ;; "interrupted") through real eval-instance-in! → summarize-response,
          ;; not from a canned op result.
          client-session (fn [msg]
                           [{:id (:id msg)
                             :session "nrepl-session-1"
                             :err "Interrupted"
                             :ns "user"
                             :status #{"interrupted"}}])]
      (install-instance! ctx worktree client-session)
      (let [result (project-nrepl-ops/eval-op ctx worktree "(+ 1 2)")]
        (is (= :interrupted (:status result)))
        (is (nil? (:ns result)))
        (is (= "Interrupted" (:err result)))
        (is (contains? (:timing result) :started-at))
        (is (contains? (:timing result) :finished-at))))))
