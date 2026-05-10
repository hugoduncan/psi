(ns psi.shared-config.project-test
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest testing is]]
   [psi.shared-config.project :as project-prefs])
  (:import
   (java.nio.file Files)
   (java.nio.file.attribute FileAttribute)))

(defn- tmp-dir []
  (.toFile (Files/createTempDirectory "psi-project-prefs-test-"
                                      (into-array FileAttribute []))))

(defn- shared-file-in [dir]
  (io/file dir ".psi" "project.edn"))

(defn- local-file-in [dir]
  (io/file dir ".psi" "project.local.edn"))

(defn- capture-stderr [f]
  (let [w (java.io.StringWriter.)]
    (binding [*err* w]
      (f))
    (str w)))

(deftest project-preferences-file-test
  (let [dir (tmp-dir)]
    (is (= (.getCanonicalPath (shared-file-in dir))
           (.getCanonicalPath (project-prefs/project-preferences-file (.getAbsolutePath dir)))))
    (is (= (.getCanonicalPath (local-file-in dir))
           (.getCanonicalPath (project-prefs/project-local-preferences-file (.getAbsolutePath dir)))))))

(deftest deep-merge-test
  (testing "recursively merges maps and replaces non-map values"
    (is (= {:a {:b 1 :c 3}
            :v [2]
            :x {:y {:z 2}}
            :k :local}
           (project-prefs/deep-merge {:a {:b 1}
                                      :v [1]
                                      :x {:y {:z 1}}
                                      :k :shared}
                                     {:a {:c 3}
                                      :v [2]
                                      :x {:y {:z 2}}
                                      :k :local}))))

  (testing "local scalar replaces shared map on collision"
    (is (= {:a :local}
           (project-prefs/deep-merge {:a {:b 1}}
                                     {:a :local}))))

  (testing "local map replaces shared scalar on collision"
    (is (= {:a {:b 1}}
           (project-prefs/deep-merge {:a :shared}
                                     {:a {:b 1}})))))

(deftest read-preferences-test
  (testing "returns defaults when neither shared nor local file exists"
    (let [dir (tmp-dir)]
      (is (= {:version 1 :agent-session {}}
             (project-prefs/read-preferences (.getAbsolutePath dir))))))

  (testing "returns shared config when only shared file exists"
    (let [dir (tmp-dir)
          f   (shared-file-in dir)]
      (.mkdirs (.getParentFile f))
      (spit f (pr-str {:agent-session {:model-provider "anthropic"
                                       :model-id "claude"}}))
      (is (= {:version 1
              :agent-session {:model-provider "anthropic"
                              :model-id "claude"}}
             (project-prefs/read-preferences (.getAbsolutePath dir))))))

  (testing "returns local config when only local file exists"
    (let [dir (tmp-dir)
          f   (local-file-in dir)]
      (.mkdirs (.getParentFile f))
      (spit f (pr-str {:agent-session {:thinking-level :high}}))
      (is (= {:version 1
              :agent-session {:thinking-level :high}}
             (project-prefs/read-preferences (.getAbsolutePath dir))))))

  (testing "deep-merges shared then local with local precedence"
    (let [dir      (tmp-dir)
          shared-f (shared-file-in dir)
          local-f  (local-file-in dir)]
      (.mkdirs (.getParentFile shared-f))
      (spit shared-f (pr-str {:agent-session {:model-provider "anthropic"
                                              :model-id "claude"
                                              :project-nrepl {:start-command ["bb" "nrepl-server"]
                                                              :attach {:host "localhost" :port 7888}}}}))
      (spit local-f (pr-str {:agent-session {:thinking-level :medium
                                             :project-nrepl {:attach {:port 9999}}}}))
      (is (= {:version 1
              :agent-session {:model-provider "anthropic"
                              :model-id "claude"
                              :thinking-level :medium
                              :project-nrepl {:start-command ["bb" "nrepl-server"]
                                              :attach {:host "localhost" :port 9999}}}}
             (project-prefs/read-preferences (.getAbsolutePath dir))))))

  (testing "malformed local warns and falls back to shared"
    (let [dir      (tmp-dir)
          shared-f (shared-file-in dir)
          local-f  (local-file-in dir)]
      (.mkdirs (.getParentFile shared-f))
      (spit shared-f (pr-str {:agent-session {:model-provider "anthropic"
                                              :model-id "claude"}}))
      (spit local-f "not valid edn")
      (let [err (capture-stderr
                 #(is (= {:version 1
                          :agent-session {:model-provider "anthropic"
                                          :model-id "claude"}}
                         (project-prefs/read-preferences (.getAbsolutePath dir)))))]
        (is (.contains err "WARNING: ignoring malformed project preferences file"))
        (is (.contains err "project.local.edn")))))

  (testing "malformed shared warns and falls back to local"
    (let [dir      (tmp-dir)
          shared-f (shared-file-in dir)
          local-f  (local-file-in dir)]
      (.mkdirs (.getParentFile shared-f))
      (spit shared-f "not valid edn")
      (spit local-f (pr-str {:agent-session {:thinking-level :high}}))
      (let [err (capture-stderr
                 #(is (= {:version 1
                          :agent-session {:thinking-level :high}}
                         (project-prefs/read-preferences (.getAbsolutePath dir)))))]
        (is (.contains err "WARNING: ignoring malformed project preferences file"))
        (is (.contains err "project.edn")))))

  (testing "both malformed warns and falls back to defaults"
    (let [dir      (tmp-dir)
          shared-f (shared-file-in dir)
          local-f  (local-file-in dir)]
      (.mkdirs (.getParentFile shared-f))
      (spit shared-f "not valid edn")
      (spit local-f "also not valid edn")
      (let [err (capture-stderr
                 #(is (= {:version 1 :agent-session {}}
                         (project-prefs/read-preferences (.getAbsolutePath dir)))))]
        (is (.contains err "project.edn"))
        (is (.contains err "project.local.edn"))))))

(deftest update-agent-session!-test
  (testing "writes only local project config and preserves unrelated local keys"
    (let [dir      (tmp-dir)
          shared-f (shared-file-in dir)
          local-f  (local-file-in dir)]
      (.mkdirs (.getParentFile shared-f))
      (spit shared-f (pr-str {:version 1
                              :agent-session {:model-provider "anthropic"
                                              :model-id "claude"
                                              :project-nrepl {:start-command ["bb" "nrepl-server"]}}}))
      (spit local-f (pr-str {:version 1
                             :agent-session {:prompt-mode :prose
                                             :project-nrepl {:attach {:port 7888}}}}))
      (is (= {:version 1
              :agent-session {:prompt-mode :prose
                              :project-nrepl {:attach {:port 7888}}
                              :thinking-level :high}}
             (project-prefs/update-agent-session! (.getAbsolutePath dir)
                                                  {:thinking-level :high})))
      (is (= {:version 1
              :agent-session {:model-provider "anthropic"
                              :model-id "claude"
                              :project-nrepl {:start-command ["bb" "nrepl-server"]}}}
             (edn/read-string (slurp shared-f))))
      (is (= {:version 1
              :agent-session {:prompt-mode :prose
                              :project-nrepl {:attach {:port 7888}}
                              :thinking-level :high}}
             (edn/read-string (slurp local-f))))))

  (testing "creates local file on first write"
    (let [dir     (tmp-dir)
          local-f (local-file-in dir)]
      (is (= {:version 1
              :agent-session {:model-provider "openai"
                              :model-id "gpt-5.3-codex"}}
             (project-prefs/update-agent-session! (.getAbsolutePath dir)
                                                  {:model-provider "openai"
                                                   :model-id "gpt-5.3-codex"})))
      (is (.exists local-f))
      (is (= {:version 1
              :agent-session {:model-provider "openai"
                              :model-id "gpt-5.3-codex"}}
             (edn/read-string (slurp local-f))))))

  (testing "malformed existing local warns, treats local input as empty, and writes requested prefs"
    (let [dir     (tmp-dir)
          local-f (local-file-in dir)]
      (.mkdirs (.getParentFile local-f))
      (spit local-f "not valid edn")
      (let [err (capture-stderr
                 #(is (= {:version 1
                          :agent-session {:thinking-level :medium}}
                         (project-prefs/update-agent-session! (.getAbsolutePath dir)
                                                              {:thinking-level :medium}))))]
        (is (.contains err "WARNING: ignoring malformed project preferences file"))
        (is (.contains err "project.local.edn")))
      (is (= {:version 1
              :agent-session {:thinking-level :medium}}
             (edn/read-string (slurp local-f)))))))
