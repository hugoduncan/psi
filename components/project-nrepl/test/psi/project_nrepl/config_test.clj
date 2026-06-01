(ns psi.project-nrepl.config-test
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [psi.project-nrepl.config :as project-nrepl-config]))

(defn- capture-stderr [f]
  (let [w (java.io.StringWriter.)]
    (binding [*err* w]
      (f))
    (str w)))

(defn- temp-dir [prefix]
  (str (java.nio.file.Files/createTempDirectory
        prefix
        (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- delete-tree! [path]
  (when path
    (let [f (io/file path)]
      (when (.exists f)
        (doseq [x (reverse (file-seq f))]
          (.delete x))))))

(defn- write-user-config! [home-dir content]
  (let [f (io/file home-dir ".psi" "agent" "config.edn")]
    (.mkdirs (.getParentFile f))
    (spit f (pr-str content))))

(defn- write-project-config! [worktree content]
  (let [f (io/file worktree ".psi" "project.edn")]
    (.mkdirs (.getParentFile f))
    (spit f (pr-str content))))

(defn- with-temp-home
  "Run `f` with `user.home` rebound to `home-dir`, restoring the original after."
  [home-dir f]
  (let [prior (System/getProperty "user.home")]
    (try
      (System/setProperty "user.home" home-dir)
      (f)
      (finally
        (System/setProperty "user.home" prior)))))

(deftest resolve-config-test
  (testing "merges project nREPL config from real user and project config files (project overrides user)"
    (let [home     (temp-dir "psi-project-nrepl-home-")
          worktree (temp-dir "psi-project-nrepl-wt-")]
      (try
        ;; on-disk content MUST be nested under [:agent-session :project-nrepl]
        ;; because resolve-config extracts via (:project-nrepl (agent-session-map ...)).
        (write-user-config! home
                            {:agent-session {:project-nrepl {:start-command ["bb" "nrepl-server"]
                                                             :attach {:host "localhost" :port 7888}}}})
        (write-project-config! worktree
                               {:agent-session {:project-nrepl {:attach {:port 9999}}}})
        (with-temp-home home
          #(is (= {:project-nrepl {:start-command ["bb" "nrepl-server"]
                                   :attach {:host "localhost" :port 9999}}}
                  (project-nrepl-config/resolve-config worktree))))
        (finally
          (delete-tree! home)
          (delete-tree! worktree)))))

  (testing "returns empty project-nrepl config when no user or project config files exist"
    (let [home     (temp-dir "psi-project-nrepl-home-")
          worktree (temp-dir "psi-project-nrepl-wt-")]
      (try
        (with-temp-home home
          #(is (= {:project-nrepl {}}
                  (project-nrepl-config/resolve-config worktree))))
        (finally
          (delete-tree! home)
          (delete-tree! worktree))))))

(deftest read-project-preferences-test
  (testing "deep-merges shared then local with local precedence"
    (let [dir      (io/file (System/getProperty "java.io.tmpdir") (str "psi-project-nrepl-pref-" (java.util.UUID/randomUUID)))
          shared-f (io/file dir ".psi" "project.edn")
          local-f  (io/file dir ".psi" "project.local.edn")]
      (.mkdirs (.getParentFile shared-f))
      (spit shared-f (pr-str {:agent-session {:project-nrepl {:start-command ["bb" "nrepl-server"]
                                                              :attach {:host "localhost" :port 7888}}}}))
      (spit local-f (pr-str {:agent-session {:project-nrepl {:attach {:port 9999}}}}))
      (try
        (is (= {:version 1
                :agent-session {:project-nrepl {:start-command ["bb" "nrepl-server"]
                                                :attach {:host "localhost" :port 9999}}}}
               (project-nrepl-config/read-project-preferences (.getAbsolutePath dir))))
        (finally
          (doseq [f (reverse (file-seq dir))]
            (.delete f))))))

  (testing "malformed local warns and falls back to shared"
    (let [dir      (io/file (System/getProperty "java.io.tmpdir") (str "psi-project-nrepl-pref-" (java.util.UUID/randomUUID)))
          shared-f (io/file dir ".psi" "project.edn")
          local-f  (io/file dir ".psi" "project.local.edn")]
      (.mkdirs (.getParentFile shared-f))
      (spit shared-f (pr-str {:agent-session {:project-nrepl {:start-command ["bb" "nrepl-server"]}}}))
      (spit local-f "not valid edn")
      (try
        (let [err (capture-stderr
                   #(is (= {:version 1
                            :agent-session {:project-nrepl {:start-command ["bb" "nrepl-server"]}}}
                           (project-nrepl-config/read-project-preferences (.getAbsolutePath dir)))))]
          (is (.contains err "WARNING: ignoring malformed project preferences file"))
          (is (.contains err "project.local.edn")))
        (finally
          (doseq [f (reverse (file-seq dir))]
            (.delete f))))))

  (testing "malformed shared warns and falls back to local"
    (let [dir      (io/file (System/getProperty "java.io.tmpdir") (str "psi-project-nrepl-pref-" (java.util.UUID/randomUUID)))
          shared-f (io/file dir ".psi" "project.edn")
          local-f  (io/file dir ".psi" "project.local.edn")]
      (.mkdirs (.getParentFile shared-f))
      (spit shared-f "not valid edn")
      (spit local-f (pr-str {:agent-session {:project-nrepl {:attach {:port 7888}}}}))
      (try
        (let [err (capture-stderr
                   #(is (= {:version 1
                            :agent-session {:project-nrepl {:attach {:port 7888}}}}
                           (project-nrepl-config/read-project-preferences (.getAbsolutePath dir)))))]
          (is (.contains err "WARNING: ignoring malformed project preferences file"))
          (is (.contains err "project.edn")))
        (finally
          (doseq [f (reverse (file-seq dir))]
            (.delete f)))))))

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
