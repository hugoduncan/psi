(ns psi.project-nrepl.config-test
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [psi.project-nrepl.config :as project-nrepl-config]
   [psi.project-nrepl.test-support :refer [age-file-back! with-temp-dir]]))

(defn- capture-stderr [f]
  (let [w (java.io.StringWriter.)]
    (binding [*err* w]
      (f))
    (str w)))

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
    (with-temp-dir [home     "psi-project-nrepl-home-"
                    worktree "psi-project-nrepl-wt-"]
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
                (project-nrepl-config/resolve-config worktree))))))

  (testing "returns empty project-nrepl config when no user or project config files exist"
    (with-temp-dir [home     "psi-project-nrepl-home-"
                    worktree "psi-project-nrepl-wt-"]
      (with-temp-home home
        #(is (= {:project-nrepl {}}
                (project-nrepl-config/resolve-config worktree)))))))

(deftest read-project-preferences-test
  (testing "deep-merges shared then local with local precedence"
    (with-temp-dir [dir "psi-project-nrepl-pref-"]
      (let [shared-f (io/file dir ".psi" "project.edn")
            local-f  (io/file dir ".psi" "project.local.edn")]
        (.mkdirs (.getParentFile shared-f))
        (spit shared-f (pr-str {:agent-session {:project-nrepl {:start-command ["bb" "nrepl-server"]
                                                                :attach {:host "localhost" :port 7888}}}}))
        (spit local-f (pr-str {:agent-session {:project-nrepl {:attach {:port 9999}}}}))
        (is (= {:version 1
                :agent-session {:project-nrepl {:start-command ["bb" "nrepl-server"]
                                                :attach {:host "localhost" :port 9999}}}}
               (project-nrepl-config/read-project-preferences dir))))))

  (testing "malformed local warns and falls back to shared"
    (with-temp-dir [dir "psi-project-nrepl-pref-"]
      (let [shared-f (io/file dir ".psi" "project.edn")
            local-f  (io/file dir ".psi" "project.local.edn")]
        (.mkdirs (.getParentFile shared-f))
        (spit shared-f (pr-str {:agent-session {:project-nrepl {:start-command ["bb" "nrepl-server"]}}}))
        (spit local-f "not valid edn")
        (let [err (capture-stderr
                   #(is (= {:version 1
                            :agent-session {:project-nrepl {:start-command ["bb" "nrepl-server"]}}}
                           (project-nrepl-config/read-project-preferences dir))))]
          (is (.contains err "WARNING: ignoring malformed project preferences file"))
          (is (.contains err "project.local.edn"))))))

  (testing "malformed shared warns and falls back to local"
    (with-temp-dir [dir "psi-project-nrepl-pref-"]
      (let [shared-f (io/file dir ".psi" "project.edn")
            local-f  (io/file dir ".psi" "project.local.edn")]
        (.mkdirs (.getParentFile shared-f))
        (spit shared-f "not valid edn")
        (spit local-f (pr-str {:agent-session {:project-nrepl {:attach {:port 7888}}}}))
        (let [err (capture-stderr
                   #(is (= {:version 1
                            :agent-session {:project-nrepl {:attach {:port 7888}}}}
                           (project-nrepl-config/read-project-preferences dir))))]
          (is (.contains err "WARNING: ignoring malformed project preferences file"))
          (is (.contains err "project.edn")))))))

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

(deftest resolved-start-readiness-timeout-ms-test
  (testing "returns nil when start-readiness-timeout-ms config absent"
    (is (nil? (project-nrepl-config/resolved-start-readiness-timeout-ms
               {:project-nrepl {}}))))

  (testing "returns a valid in-range integer timeout"
    (is (= 120000
           (project-nrepl-config/resolved-start-readiness-timeout-ms
            {:project-nrepl {:start-readiness-timeout-ms 120000}})))
    (is (= 1000
           (project-nrepl-config/resolved-start-readiness-timeout-ms
            {:project-nrepl {:start-readiness-timeout-ms 1000}})))
    (is (= 600000
           (project-nrepl-config/resolved-start-readiness-timeout-ms
            {:project-nrepl {:start-readiness-timeout-ms 600000}}))))

  (testing "rejects below-range, above-range, and non-integer timeouts as :phase :validate"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"range 1000-600000"
         (project-nrepl-config/resolved-start-readiness-timeout-ms
          {:project-nrepl {:start-readiness-timeout-ms 999}})))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"range 1000-600000"
         (project-nrepl-config/resolved-start-readiness-timeout-ms
          {:project-nrepl {:start-readiness-timeout-ms 600001}})))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"range 1000-600000"
         (project-nrepl-config/resolved-start-readiness-timeout-ms
          {:project-nrepl {:start-readiness-timeout-ms "120000"}})))
    (is (= :validate
           (:phase (ex-data
                    (try
                      (project-nrepl-config/resolved-start-readiness-timeout-ms
                       {:project-nrepl {:start-readiness-timeout-ms 0}})
                      (catch clojure.lang.ExceptionInfo e e))))))))

(deftest read-dot-nrepl-port-test
  (testing "reads integer port from target worktree .nrepl-port"
    (with-temp-dir [dir "psi-project-nrepl-"]
      (spit (io/file dir ".nrepl-port") "7888\n")
      (is (= {:port 7888 :port-source :dot-nrepl-port}
             (project-nrepl-config/read-dot-nrepl-port dir)))))

  ;; TR4: read-dot-nrepl-port is a mode-agnostic read+validate primitive; the
  ;; started-mode launch-instant mtime gate lives ONLY in started.clj. The shared
  ;; primitive must accept whatever .nrepl-port is present — even a stale
  ;; (old-mtime) one. Aging the file mirrors the started-mode stale fixture
  ;; (- now 60000); a future gate leak into this shared read would fail here.
  (testing "accepts a stale (old-mtime) .nrepl-port — no started-mode gate in shared read"
    (with-temp-dir [dir "psi-project-nrepl-"]
      (let [port-file (io/file dir ".nrepl-port")]
        (spit port-file "7888\n")
        (age-file-back! port-file))
      (is (= {:port 7888 :port-source :dot-nrepl-port}
             (project-nrepl-config/read-dot-nrepl-port dir)))))

  (testing "fails when .nrepl-port is absent"
    (with-temp-dir [dir "psi-project-nrepl-"]
      (is (thrown? clojure.lang.ExceptionInfo
                   (project-nrepl-config/read-dot-nrepl-port dir)))))

  (testing "fails when .nrepl-port content is malformed"
    (with-temp-dir [dir "psi-project-nrepl-"]
      (spit (io/file dir ".nrepl-port") "not-a-port")
      (is (thrown? clojure.lang.ExceptionInfo
                   (project-nrepl-config/read-dot-nrepl-port dir))))))
