(ns psi.build-manifest
  "Build-manifest helpers shared by the tools.build script, launcher, and tests.

   The library jar published to Clojars must include every runtime source and
   resource path needed by the installed `psi` launcher. The authoritative path
   list lives in the top-level `deps.edn` `:psi` alias; these helpers derive the
   build copy list directly from that alias so packaging cannot silently drift.

   The released launcher also needs artifact-owned dependency metadata for the
   shipped psi runtime closure. These helpers derive that external dep closure
   from the same authoritative runtime path list so the launcher can consume a
   stable jar-owned release-deps payload rather than reconstructing release
   truth from the repository layout."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]))

(def release-deps-resource-path
  "Stable jar resource path for release-owned runtime dep metadata."
  "psi/release-deps.edn")

(defn read-deps-edn
  [deps-file]
  (-> deps-file
      io/file
      slurp
      edn/read-string))

(defn alias-extra-paths
  [deps-data alias-key]
  (or (get-in deps-data [:aliases alias-key :extra-paths])
      (throw (ex-info "Alias missing :extra-paths"
                      {:alias alias-key}))))

(defn alias-extra-deps
  [deps-data alias-key]
  (or (get-in deps-data [:aliases alias-key :extra-deps])
      {}))

(defn normalize-paths
  [paths]
  (->> paths
       (remove nil?)
       vec))

(defn build-lib-src-dirs
  ([]
   (build-lib-src-dirs "deps.edn"))
  ([deps-file]
   (-> (read-deps-edn deps-file)
       (alias-extra-paths :psi)
       normalize-paths)))

(defn- owning-deps-file
  [runtime-path]
  (cond
    (str/starts-with? runtime-path "components/")
    (let [[_ component _leaf] (str/split runtime-path #"/" 3)]
      (str "components/" component "/deps.edn"))

    (str/starts-with? runtime-path "extensions/")
    (let [[_ extension _leaf] (str/split runtime-path #"/" 3)]
      (str "extensions/" extension "/deps.edn"))

    (str/starts-with? runtime-path "bases/")
    (let [[_ base _leaf] (str/split runtime-path #"/" 3)]
      (str "bases/" base "/deps.edn"))

    :else nil))

(defn runtime-deps-files
  ([]
   (runtime-deps-files "deps.edn"))
  ([deps-file]
   (->> (build-lib-src-dirs deps-file)
        (keep owning-deps-file)
        distinct
        vec)))

(defn- external-deps
  [deps-map]
  (into {}
        (remove (fn [[_lib dep]]
                  (contains? dep :local/root)))
        deps-map))

(defn build-lib-runtime-extra-deps
  "Return third-party runtime deps required by installed psi that are declared
   only in component/extension-local deps.edn files, excluding deps already
   present in the top-level root :deps map or the authoritative :psi alias
   :extra-deps map."
  ([]
   (build-lib-runtime-extra-deps "deps.edn"))
  ([deps-file]
   (let [deps-data        (read-deps-edn deps-file)
         root-deps        (:deps deps-data)
         alias-deps       (alias-extra-deps deps-data :psi)
         top-level-known  (merge (external-deps root-deps)
                                 (external-deps alias-deps))
         nested-external  (->> (runtime-deps-files deps-file)
                               (map read-deps-edn)
                               (map :deps)
                               (map external-deps)
                               (apply merge {}))]
     (apply dissoc nested-external (keys top-level-known)))))

(defn build-lib-runtime-basis-deps
  "Return the complete external dependency map needed by the installed launcher
   runtime basis: top-level root :deps + authoritative :psi alias :extra-deps +
   nested-only runtime deps."
  ([]
   (build-lib-runtime-basis-deps "deps.edn"))
  ([deps-file]
   (let [deps-data       (read-deps-edn deps-file)
         root-external   (external-deps (:deps deps-data))
         alias-external  (external-deps (alias-extra-deps deps-data :psi))]
     (merge root-external
            alias-external
            (build-lib-runtime-extra-deps deps-file)))))

(defn release-deps-map
  "Artifact-owned release dep payload embedded in the published psi jar.
   This map is read by the released launcher under :jar policy."
  ([]
   (release-deps-map "deps.edn"))
  ([deps-file]
   {:deps (build-lib-runtime-basis-deps deps-file)}))

(defn release-deps-edn
  ([]
   (release-deps-edn "deps.edn"))
  ([deps-file]
   (str (pr-str (release-deps-map deps-file)) "\n")))
