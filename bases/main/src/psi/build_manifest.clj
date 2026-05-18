(ns psi.build-manifest
  "Build-manifest helpers shared by the tools.build script and tests.

   The library jar published to Clojars must include every runtime source and
   resource path needed by the installed `psi` launcher. The authoritative path
   list lives in the top-level `deps.edn` `:psi` alias; these helpers derive the
   build copy list directly from that alias so packaging cannot silently drift."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]))

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
