(ns psi.shared-config.user-test
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest testing is]]
   [psi.shared-config.user :as user-config])
  (:import
   (java.nio.file Files)
   (java.nio.file.attribute FileAttribute)))

(defn- tmp-dir []
  (.toFile (Files/createTempDirectory "psi-user-config-test-"
                                      (into-array FileAttribute []))))

(defn- config-file-in [dir]
  (io/file dir ".psi" "agent" "config.edn"))

(deftest user-config-file-test
  (testing "builds the config path under the current user.home"
    (let [dir           (tmp-dir)
          original-home (System/getProperty "user.home")]
      (try
        (System/setProperty "user.home" (.getAbsolutePath dir))
        (is (= (.getCanonicalPath (config-file-in dir))
               (.getCanonicalPath (user-config/user-config-file))))
        (finally
          (if original-home
            (System/setProperty "user.home" original-home)
            (System/clearProperty "user.home")))))))

(deftest read-config-test
  (testing "returns default config when file is missing"
    (let [dir (tmp-dir)
          f   (config-file-in dir)]
      (with-redefs [user-config/user-config-file (fn [] f)]
        (is (= {:version 1 :agent-session {}}
               (user-config/read-config))))))

  (testing "returns default config when file contains invalid edn"
    (let [dir (tmp-dir)
          f   (config-file-in dir)]
      (.mkdirs (.getParentFile f))
      (spit f "not valid edn\n")
      (with-redefs [user-config/user-config-file (fn [] f)]
        (is (= {:version 1 :agent-session {}}
               (user-config/read-config))))))

  (testing "returns default config when slurp throws during read"
    (let [dir (tmp-dir)
          f   (config-file-in dir)]
      (.mkdirs (.getParentFile f))
      (spit f "{:agent-session {:model-provider \"anthropic\"}}")
      (with-redefs [user-config/user-config-file (fn [] f)
                    clojure.core/slurp         (fn [_] (throw (ex-info "boom" {})))]
        (is (= {:version 1 :agent-session {}}
               (user-config/read-config))))))

  (testing "returns default config when file contains a non-map value"
    (let [dir (tmp-dir)
          f   (config-file-in dir)]
      (.mkdirs (.getParentFile f))
      (spit f "[:not-a-map]\n")
      (with-redefs [user-config/user-config-file (fn [] f)]
        (is (= {:version 1 :agent-session {}}
               (user-config/read-config))))))

  (testing "merges valid persisted config with defaults"
    (let [dir       (tmp-dir)
          f         (config-file-in dir)
          persisted {:agent-session {:model-provider "anthropic"
                                     :model-id "claude"
                                     :prompt-mode :lambda}}]
      (.mkdirs (.getParentFile f))
      (spit f (pr-str persisted))
      (with-redefs [user-config/user-config-file (fn [] f)]
        (is (= {:version 1
                :agent-session {:model-provider "anthropic"
                                :model-id "claude"
                                :prompt-mode :lambda}}
               (user-config/read-config)))))))

(deftest update-agent-session!-test
  (testing "creates parent directories and persists merged agent-session config"
    (let [dir (tmp-dir)
          f   (config-file-in dir)]
      (with-redefs [user-config/user-config-file (fn [] f)]
        (is (= {:version 1
                :agent-session {:model-provider "anthropic"
                                :model-id "claude-3-7"
                                :thinking-level :high}}
               (user-config/update-agent-session! {:model-provider "anthropic"
                                                   :model-id "claude-3-7"
                                                   :thinking-level :high})))
        (is (.exists f))
        (is (= {:version 1
                :agent-session {:model-provider "anthropic"
                                :model-id "claude-3-7"
                                :thinking-level :high}}
               (edn/read-string (slurp f)))))))

  (testing "merges new values into existing persisted agent-session config"
    (let [dir (tmp-dir)
          f   (config-file-in dir)]
      (.mkdirs (.getParentFile f))
      (spit f (pr-str {:version 1
                       :agent-session {:model-provider "anthropic"
                                       :model-id "claude-3-7"
                                       :prompt-mode :prose}}))
      (with-redefs [user-config/user-config-file (fn [] f)]
        (is (= {:version 1
                :agent-session {:model-provider "anthropic"
                                :model-id "claude-3-7"
                                :prompt-mode :prose
                                :thinking-level :medium}}
               (user-config/update-agent-session! {:thinking-level :medium})))
        (is (= {:version 1
                :agent-session {:model-provider "anthropic"
                                :model-id "claude-3-7"
                                :prompt-mode :prose
                                :thinking-level :medium}}
               (edn/read-string (slurp f))))))))
