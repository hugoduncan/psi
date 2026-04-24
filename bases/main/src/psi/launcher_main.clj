(ns psi.launcher-main
  (:require
   [babashka.process :as process]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [psi.launcher :as launcher]))

(defn- explicit-policy
  []
  (case (System/getenv "PSI_LAUNCHER_POLICY")
    "development" :development
    "installed" :installed
    nil :installed
    (throw (ex-info "Invalid PSI_LAUNCHER_POLICY"
                    {:env "PSI_LAUNCHER_POLICY"
                     :value (System/getenv "PSI_LAUNCHER_POLICY")
                     :expected #{"development" "installed"}}))))

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
    (println (str "  policy: " (name policy)))
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
    (when (:launcher-debug? plan)
      (print-debug-summary! plan))
    @(process/process (:command plan)
                      {:inherit true
                       :dir (:cwd plan)})))
