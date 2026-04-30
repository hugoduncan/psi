(ns psi.launcher-main
  (:require
   [babashka.deps :as deps]
   [babashka.fs :as fs]
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
  "Returns the repo root when running from source (file: URL), nil from a jar."
  []
  (when-let [url (io/resource "psi/launcher_main.clj")]
    (when (= "file" (.getProtocol url))
      (some-> (.getPath url)
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
  [{:keys [cwd psi-args basis-edn basis manifest-info policy]}]
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
    (println (str "  command: clojure -Sdeps-file <tmpfile> -M -m psi.main " (str/join " " psi-args)))
    (println (str "  basis-edn: " basis-edn))))

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
    (let [basis-edn  (:basis-edn plan)
          ;; Stable file path keyed on content hash so .cpcache is reused across
          ;; invocations with the same deps (warm startup, CI cache hits).
          basis-hash (Integer/toHexString (hash basis-edn))
          basis-file (fs/file (fs/temp-dir) (str "psi-basis-" basis-hash ".edn"))]
      (when-not (fs/exists? basis-file)
        (spit basis-file basis-edn))
      @(apply deps/clojure
              {:inherit true :dir (:cwd plan)}
              (launcher/build-deps-clj-args
               {:basis-file (str basis-file)
                :psi-args   (:psi-args plan)})))))
