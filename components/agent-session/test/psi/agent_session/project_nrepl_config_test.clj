(ns psi.agent-session.project-nrepl-config-test
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.project-nrepl-config :as project-nrepl-config]
   [psi.agent-session.project-preferences :as project-prefs]
   [psi.agent-session.user-config :as user-config]))

(deftest resolve-config-test
  (testing "merges project nREPL config from user and project scopes"
    (with-redefs [user-config/read-config (fn [] {:agent-session {:project-nrepl {:start-command ["bb" "nrepl-server"]
                                                                                  :attach {:host "localhost" :port 7888}}}})
                  project-prefs/read-preferences (fn [cwd]
                                                   (is (= "/tmp/project" cwd))
                                                   {:agent-session {:project-nrepl {:attach {:port 9999}}}})]
      (is (= {:project-nrepl {:start-command ["bb" "nrepl-server"]
                              :attach {:host "localhost" :port 9999}}}
             (project-nrepl-config/resolve-config "/tmp/project")))))

  (testing "returns empty project-nrepl config when user and project config are empty"
    (with-redefs [user-config/read-config (fn [] {})
                  project-prefs/read-preferences (fn [_] {})]
      (is (= {:project-nrepl {}}
             (project-nrepl-config/resolve-config "/tmp/project"))))))

(deftest resolve-target-worktree-test
  (testing "explicit target wins over session worktree"
    (is (= "/repo/explicit"
           (project-nrepl-config/resolve-target-worktree
            {:target-worktree-path "/repo/explicit"
             :session-worktree-path "/repo/session"}))))

  (testing "session worktree is used when explicit target absent"
    (is (= "/repo/session"
           (project-nrepl-config/resolve-target-worktree
            {:session-worktree-path "/repo/session"}))))

  (testing "missing target fails explicitly"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"require explicit target-worktree-path or invoking session worktree-path"
         (project-nrepl-config/resolve-target-worktree {})))))

(deftest absolute-directory-path-test
  (testing "accepts existing absolute directory"
    (let [dir (System/getProperty "user.dir")]
      (is (= dir (project-nrepl-config/absolute-directory-path! dir)))))

  (testing "rejects relative path"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"must be absolute"
         (project-nrepl-config/absolute-directory-path! "relative/path"))))

  (testing "rejects missing directory"
    (is (thrown?
         clojure.lang.ExceptionInfo
         (project-nrepl-config/absolute-directory-path! "/definitely/not/a/dir")))))

(deftest resolved-start-command-test
  (testing "returns valid start-command vector"
    (is (= ["bb" "nrepl-server"]
           (project-nrepl-config/resolved-start-command
            {:project-nrepl {:start-command ["bb" "nrepl-server"]}}))))

  (testing "returns nil when start-command config absent"
    (is (nil? (project-nrepl-config/resolved-start-command {:project-nrepl {}}))))

  (testing "rejects invalid start-command shapes"
    (is (thrown? clojure.lang.ExceptionInfo
                 (project-nrepl-config/resolved-start-command
                  {:project-nrepl {:start-command '("bb" "nrepl-server")}})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (project-nrepl-config/resolved-start-command
                  {:project-nrepl {:start-command []}})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (project-nrepl-config/resolved-start-command
                  {:project-nrepl {:start-command ["" "nrepl-server"]}})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (project-nrepl-config/resolved-start-command
                  {:project-nrepl {:start-command ["bb" :nrepl-server]}})))))

(deftest resolved-attach-endpoint-test
  (testing "returns validated attach endpoint"
    (is (= {:host "localhost" :port 7888}
           (project-nrepl-config/resolved-attach-endpoint
            {:project-nrepl {:attach {:host "localhost" :port 7888}}})))
    (is (= {:port 7888}
           (project-nrepl-config/resolved-attach-endpoint
            {:project-nrepl {:attach {:port 7888}}}))))

  (testing "returns nil when attach config absent or fully empty"
    (is (nil? (project-nrepl-config/resolved-attach-endpoint {:project-nrepl {}})))
    (is (nil? (project-nrepl-config/resolved-attach-endpoint {:project-nrepl {:attach {}}}))))

  (testing "rejects invalid attach config"
    (is (thrown? clojure.lang.ExceptionInfo
                 (project-nrepl-config/resolved-attach-endpoint {:project-nrepl {:attach []}})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (project-nrepl-config/resolved-attach-endpoint {:project-nrepl {:attach {:host ""}}})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (project-nrepl-config/resolved-attach-endpoint {:project-nrepl {:attach {:port 0}}})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (project-nrepl-config/resolved-attach-endpoint {:project-nrepl {:attach {:port "7888"}}})))))

(deftest read-dot-nrepl-port-test
  (testing "reads integer port from target worktree .nrepl-port"
    (let [dir (io/file (System/getProperty "java.io.tmpdir") (str "psi-project-nrepl-" (java.util.UUID/randomUUID)))]
      (.mkdirs dir)
      (spit (io/file dir ".nrepl-port") "7888\n")
      (try
        (is (= {:port 7888 :port-source :dot-nrepl-port}
               (project-nrepl-config/read-dot-nrepl-port (.getAbsolutePath dir))))
        (finally
          (doseq [f (reverse (file-seq dir))]
            (.delete f))))))

  (testing "fails when .nrepl-port is missing or invalid"
    (let [dir (io/file (System/getProperty "java.io.tmpdir") (str "psi-project-nrepl-" (java.util.UUID/randomUUID)))]
      (.mkdirs dir)
      (try
        (is (thrown? clojure.lang.ExceptionInfo
                     (project-nrepl-config/read-dot-nrepl-port (.getAbsolutePath dir))))
        (spit (io/file dir ".nrepl-port") "not-a-port")
        (is (thrown? clojure.lang.ExceptionInfo
                     (project-nrepl-config/read-dot-nrepl-port (.getAbsolutePath dir))))
        (finally
          (doseq [f (reverse (file-seq dir))]
            (.delete f)))))))
