(ns ^:integration psi.release-packaging-smoke-test
  (:require
   [clojure.edn :as edn]
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

(defn- version-resource-path
  []
  "bases/main/resources/psi/version.edn")

(defn- read-version-string
  []
  (-> (version-resource-path) slurp edn/read-string :version))

(defn- write-version-string!
  [version]
  (spit (version-resource-path) (str "{:version " (pr-str version) "}\n")))

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
    {"HOME" home
     "XDG_DATA_HOME" xdg-data
     "XDG_CACHE_HOME" xdg-cache
     "XDG_CONFIG_HOME" xdg-config
     "BABASHKA_BBIN_BIN_DIR" bbin-bin
     "PATH" path}))

(deftest ^:integration stamped-release-bbin-help-smoke-test
  (testing "stamped release artifact installs via bbin and prints non-interactive help without repo layout"
    (support/with-build-lock
      (let [original-version (read-version-string)
            stamped-version  "0.1.2115-packaging-smoke"
            tmp-root         (temp-dir! "psi-release-packaging-")
            env              (isolated-env tmp-root)]
        (try
          (write-version-string! stamped-version)
          (sh! env "bb" "build:lib")
          (sh! env "clojure" "-T:build" "install-local")
          (sh! env "bbin" "install" "org.hugoduncan/psi" "--as" "psi-release-smoke" "--mvn/version" stamped-version)
          (let [installed (str (get env "BABASHKA_BBIN_BIN_DIR") "/psi-release-smoke")
                run-env (assoc env "PSI_LAUNCHER_POLICY" "jar")
                version-result (apply shell/sh (concat [installed "--cwd" tmp-root "--version" :env run-env :dir tmp-root]))
                help-result    (apply shell/sh (concat [installed "--cwd" tmp-root "--help" :env run-env :dir tmp-root]))]
            (is (.exists (io/file installed)))
            (is (= 0 (:exit version-result)) (:err version-result))
            (is (= (str "psi " stamped-version)
                   (str/trim (:out version-result))))
            (is (= 0 (:exit help-result)) (:err help-result))
            (is (str/includes? (:out help-result) "Usage:"))
            (is (str/includes? (:out help-result) "/help for commands")))
          (finally
            (write-version-string! original-version)))))))
