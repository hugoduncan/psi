(ns psi.version
  "Reads the embedded version resource written at release time."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]))

(defn version-string
  "Return the psi version string, e.g. \"0.1.1985\".
   Returns \"unreleased\" when running from source without a stamped resource."
  []
  (if-let [url (io/resource "psi/version.edn")]
    (-> url slurp edn/read-string :version (or "unreleased"))
    "unreleased"))
