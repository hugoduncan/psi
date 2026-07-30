(ns ^:integration psi.bbin-install-smoke-test
  (:require
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [psi.build-smoke-support :as support]))

(defn- sh!
  [env & args]
  (let [{:keys [exit out err]} (apply shell/sh (concat args [:env env]))]
    (when-not (zero? exit)
      (throw (ex-info "subprocess failed"
                      {:args args :exit exit :out out :err err})))
    {:out out :err err}))

(defn- temp-dir!
  [prefix]
  (str (java.nio.file.Files/createTempDirectory prefix (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- version-string
  []
  (-> "bases/main/resources/psi/version.edn" slurp read-string :version))

(defn- isolated-env
  [root]
  (let [home (str root "/home")
        xdg-data (str root "/data")
        xdg-cache (str root "/cache")
        xdg-config (str root "/config")
        bbin-bin (str root "/bin")
        path (str bbin-bin java.io.File/pathSeparator (System/getenv "PATH"))]
    (doseq [dir [home xdg-data xdg-cache xdg-config bbin-bin]]
      (.mkdirs (io/file dir)))
    (spit (str root "/deps.edn") "{:paths []}\n")
    {:env {"HOME" home
           "XDG_DATA_HOME" xdg-data
           "XDG_CACHE_HOME" xdg-cache
           "XDG_CONFIG_HOME" xdg-config
           "BABASHKA_BBIN_BIN_DIR" bbin-bin
           "PATH" path}
     :cwd root}))

(deftest ^:integration bbin-install-smoke-test
  (testing "bbin can install the locally built psi library artifact and launch it from an isolated environment without repo-local startup assumptions"
    (support/with-build-lock
      (let [tmp-root (temp-dir! "psi-bbin-smoke-")
            {:keys [env cwd]} (isolated-env tmp-root)
            expected (str "psi " (version-string))]
        (sh! env "clojure" "-T:build" "install-local")
        (sh! env "bbin" "install" "org.hugoduncan/psi" "--as" "psi-smoke" "--mvn/version" (version-string))
        (let [installed (str (get env "BABASHKA_BBIN_BIN_DIR") "/psi-smoke")
              run-env (assoc env "PSI_LAUNCHER_POLICY" "jar")
              version-result (apply shell/sh (concat [installed "--cwd" cwd "--version" :env run-env :dir cwd]))
              help-result    (apply shell/sh (concat [installed "--cwd" cwd "--help" :env run-env :dir cwd]))]
          (is (.exists (io/file installed)))
          (is (= 0 (:exit version-result)) (:err version-result))
          (is (= expected
                 (str/trim (:out version-result))))
          (is (= 0 (:exit help-result)) (:err help-result))
          (is (or (str/includes? (:out help-result) "Usage:")
                  (str/includes? (:out help-result) "/help for commands"))))))))
