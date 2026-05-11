(ns psi.github.slug
  "Shared slug derivation for GitHub issue and PR titles/branch names.

   Used by `psi.github.find-issue` and `psi.github.find-pr`."
  (:require
   [clojure.string :as str]))

(defn derive-slug
  "Lower-case input → extract [a-z0-9]+ words → join with - →
   hard-truncate at 40 chars → strip trailing -.
   Result: [a-z0-9-], ≤ 40 chars, never ends with -."
  [s]
  (let [words   (re-seq #"[a-z0-9]+" (str/lower-case (or s "")))
        joined  (str/join "-" words)
        trimmed (subs joined 0 (min 40 (count joined)))]
    (str/replace trimmed #"-+$" "")))
