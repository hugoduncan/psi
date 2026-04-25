(ns psi.launcher-main
  (:require
   [babashka.process :as process]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [psi.launcher :as launcher]
   [psi.version :as version]))

(defn- explicit-policy
  "Resolve launcher policy from PSI_LAUNCHER_POLICY env var.
   Defaults to :jar when running a stamped release, :installed otherwise."
  []
  (case (System/getenv "PSI_LAUNCHER_POLICY")
    "development" :development
    "installed"   :installed
    "jar"         :jar
    nil           (if (not= "unreleased" (version/version-string))
                    :jar
                    :installed)
    (throw (ex-info "Invalid PSI_LAUNCHER_POLICY"
                    {:env "PSI_LAUNCHER_POLICY"
                     :value (System/getenv "PSI_LAUNCHER_POLICY")
                     :expected #{"development" "installed" "jar"}}))))

(defn- resource-root
  []
  (when-let [url (io/resource "psi/launcher_main.clj")]
    (let [path (.getPath (io/file url))]
      (some-> path
              (str/replace #"/bases/main/src/psi/launcher_main\.clj$" "")
              not-empty))))

(defn- launcher-root
  []
  (or (System/getenv "BBIN_REPO_ROOT")
      (resource-root)
      (some-> (System/getProperty "babashka.config")
              java.io.File.
              .getParentFile
              .getAbsolutePath)
      (System/getProperty "user.dir")
      (throw (ex-info "Unable to determine launcher root" {:stage :launcher-root}))))

(defn- print-debug-summary!
  [{:keys [cwd psi-args command basis manifest-info policy]}]
  (binding [*out* *err*]
    (println "psi launcher")
    (println (str "  cwd: " cwd))
    (println (str "  policy: " (name policy) (when (= :jar policy) " (Clojars mvn)")))
    (println (str "  user manifest present: " (:user-present? manifest-info)))
    (println (str "  project manifest present: " (:project-present? manifest-info)))
    (println (str "  merged manifest libs: " (pr-str (sort (keys (get-in manifest-info [:merged-manifest :deps]))))))
    (println (str "  psi-owned defaults: " (pr-str (sort (:defaulted-libs manifest-info)))))
    (println (str "  inferred :psi/init libs: " (pr-str (sort (:inferred-init-libs manifest-info)))))
    (println (str "  basis deps count: " (count (:deps basis))))
    (println (str "  forwarded psi args: " (pr-str psi-args)))
    (println (str "  command: " (str/join " " command)))))

(defn -main
  [& args]
  (let [plan (launcher/launch-plan args
                                   (System/getProperty "user.dir")
                                   (launcher-root)
                                   (explicit-policy))]
    (when (:version? plan)
      (println (str "psi " (version/version-string)))
      (System/exit 0))
    (when (:launcher-debug? plan)
      (print-debug-summary! plan))
    @(process/process (:command plan)
                      {:inherit true
                       :dir (:cwd plan)})))
